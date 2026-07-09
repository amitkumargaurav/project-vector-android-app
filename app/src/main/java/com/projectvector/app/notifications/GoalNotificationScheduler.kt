package com.projectvector.app.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.projectvector.app.MainActivity
import com.projectvector.app.R
import com.projectvector.app.bridge.GoalNotificationPayload
import com.projectvector.app.bridge.GoalNotificationState
import com.projectvector.app.bridge.MarkGoalProgressAddressedPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalNotificationScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val store = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setGoalNotifications(payload: GoalNotificationPayload): Int {
        val previousGoalIds = readState().map { it.id }.toSet()
        val desiredGoalIds = payload.goals.map { it.id }.toSet()
        previousGoalIds.minus(desiredGoalIds).forEach(::cancelAllForGoal)

        persist(payload)
        return payload.goals.count { goal -> scheduleDaily(goal, payload.timezone) }
    }

    fun markGoalProgressAddressed(payload: MarkGoalProgressAddressedPayload): Boolean {
        val addressedDate = Instant.parse(payload.addressedAt).atZone(currentZone()).toLocalDate()
        markAddressed(payload.goalId, addressedDate)
        cancelAggressiveRepeat(payload.goalId)
        readState().firstOrNull { it.id == payload.goalId }?.let { scheduleDaily(it, currentZone().id) }
        return true
    }

    fun restorePersistedAlarms() {
        val timezone = currentZone().id
        readState().forEach { goal ->
            cancelAllForGoal(goal.id)
            scheduleDaily(goal, timezone)
        }
    }

    fun onAlarm(goalId: String, alarmKind: String) {
        val goal = readState().firstOrNull { it.id == goalId } ?: return
        val timezone = currentZone().id
        val today = LocalDate.now(currentZone())
        if (goal.deadline.isBefore(today)) {
            cancelAllForGoal(goal.id)
            return
        }

        showNotification(goal)
        if (alarmKind == ALARM_KIND_DAILY) {
            scheduleDaily(goal, timezone)
        }
        if (goal.shouldScheduleAggressiveRepeat(today)) {
            scheduleAggressiveRepeat(goal)
        } else {
            cancelAggressiveRepeat(goal.id)
        }
    }

    fun onNotificationTapped(goalId: String) {
        markAddressed(goalId, LocalDate.now(currentZone()))
        cancelAggressiveRepeat(goalId)
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun scheduleDaily(goal: GoalNotificationState, timezone: String): Boolean {
        val zone = zoneOrDefault(timezone)
        val today = LocalDate.now(zone)
        if (goal.deadline.isBefore(today)) {
            cancelAllForGoal(goal.id)
            return false
        }

        setAlarm(
            goalId = goal.id,
            alarmKind = ALARM_KIND_DAILY,
            triggerAt = nextDailyTriggerAt(goal, zone).toEpochMilli(),
        )
        return true
    }

    private fun scheduleAggressiveRepeat(goal: GoalNotificationState) {
        val delayMinutes = goal.repeatEveryMinutes ?: DEFAULT_REPEAT_EVERY_MINUTES
        setAlarm(
            goalId = goal.id,
            alarmKind = ALARM_KIND_AGGRESSIVE,
            triggerAt = Instant.now().plusSeconds(delayMinutes * 60L).toEpochMilli(),
        )
    }

    private fun setAlarm(goalId: String, alarmKind: String, triggerAt: Long) {
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            alarmPendingIntent(goalId, alarmKind, PendingIntent.FLAG_UPDATE_CURRENT) ?: return,
        )
    }

    private fun cancelAllForGoal(goalId: String) {
        cancelDaily(goalId)
        cancelAggressiveRepeat(goalId)
    }

    private fun cancelDaily(goalId: String) {
        alarmPendingIntent(goalId, ALARM_KIND_DAILY, PendingIntent.FLAG_NO_CREATE)?.let(alarmManager::cancel)
    }

    private fun cancelAggressiveRepeat(goalId: String) {
        alarmPendingIntent(goalId, ALARM_KIND_AGGRESSIVE, PendingIntent.FLAG_NO_CREATE)?.let(alarmManager::cancel)
    }

    private fun alarmPendingIntent(goalId: String, alarmKind: String, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        requestCode(goalId, alarmKind),
        Intent(context, GoalNotificationReceiver::class.java).apply {
            action = ACTION_GOAL_REMINDER
            putExtra(EXTRA_GOAL_ID, goalId)
            putExtra(EXTRA_ALARM_KIND, alarmKind)
        },
        flag or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun tapPendingIntent(goalId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(goalId, ALARM_KIND_TAP),
        Intent(context, GoalNotificationTapReceiver::class.java).apply {
            action = ACTION_GOAL_REMINDER_TAPPED
            putExtra(EXTRA_GOAL_ID, goalId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun nextDailyTriggerAt(goal: GoalNotificationState, zone: ZoneId): Instant {
        val now = LocalDateTime.now(zone)
        val target = now.with(goal.notificationTime)
        val next = if (target.isAfter(now)) target else target.plusDays(1)
        return next.atZone(zone).toInstant()
    }

    private fun showNotification(goal: GoalNotificationState) {
        val sensitive = goal.privacyMode == PRIVACY_SENSITIVE
        val contentTitle = if (sensitive) "Vector reminder" else "Vector reminder: ${goal.title}"
        val contentText = if (sensitive) {
            "Open Vector to review your plan."
        } else {
            "Open Vector to review your plan for ${goal.title}."
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(tapPendingIntent(goal.id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(NotificationSound.uri(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(requestCode(goal.id, ALARM_KIND_DAILY), notification)
    }

    private fun GoalNotificationState.shouldScheduleAggressiveRepeat(today: LocalDate): Boolean =
        aggressive && requiresTodayProgressAddress && deadline >= today && addressedDate(id) != today

    private fun markAddressed(goalId: String, date: LocalDate) {
        store.edit().putString(addressedKey(goalId), date.toString()).apply()
    }

    private fun addressedDate(goalId: String): LocalDate? = store.getString(addressedKey(goalId), null)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }

    private fun persist(payload: GoalNotificationPayload) {
        store.edit()
            .putString(KEY_GOALS, JSONArray(payload.goals.map { it.toJson() }).toString())
            .putString(KEY_TIMEZONE, payload.timezone)
            .putString(KEY_SYNCED_AT, payload.syncedAt)
            .apply()
    }

    private fun readState(): List<GoalNotificationState> {
        val raw = store.getString(KEY_GOALS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> GoalNotificationState.fromJson(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    private fun currentZone(): ZoneId = zoneOrDefault(store.getString(KEY_TIMEZONE, null).orEmpty())
    private fun zoneOrDefault(timezone: String): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
    private fun addressedKey(goalId: String): String = "${KEY_ADDRESSED_PREFIX}${requestCode(goalId, ALARM_KIND_DAILY)}"

    companion object {
        const val ACTION_GOAL_REMINDER = "com.projectvector.app.notifications.GOAL_REMINDER"
        const val ACTION_GOAL_REMINDER_TAPPED = "com.projectvector.app.notifications.GOAL_REMINDER_TAPPED"
        const val EXTRA_GOAL_ID = "goal_id"
        const val EXTRA_ALARM_KIND = "alarm_kind"
        private const val PREFS_NAME = "goal_notifications"
        private const val KEY_GOALS = "goals"
        private const val KEY_TIMEZONE = "timezone"
        private const val KEY_SYNCED_AT = "synced_at"
        private const val KEY_ADDRESSED_PREFIX = "addressed_"
        private const val DEFAULT_REPEAT_EVERY_MINUTES = 30
        private const val PRIVACY_SENSITIVE = "sensitive"
        private const val ALARM_KIND_DAILY = "daily"
        private const val ALARM_KIND_AGGRESSIVE = "aggressive"
        private const val ALARM_KIND_TAP = "tap"

        fun requestCode(goalId: String, alarmKind: String): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest("$alarmKind:$goalId".toByteArray(Charsets.UTF_8))
            return ByteBuffer.wrap(digest).int and Int.MAX_VALUE
        }
    }
}

class GoalNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val goalId = intent.getStringExtra(GoalNotificationScheduler.EXTRA_GOAL_ID) ?: return
        val alarmKind = intent.getStringExtra(GoalNotificationScheduler.EXTRA_ALARM_KIND).orEmpty()
        GoalNotificationScheduler(context).onAlarm(goalId, alarmKind)
    }
}

class GoalNotificationTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val goalId = intent.getStringExtra(GoalNotificationScheduler.EXTRA_GOAL_ID) ?: return
        GoalNotificationScheduler(context).onNotificationTapped(goalId)
    }
}

class GoalNotificationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            GoalNotificationScheduler(context).restorePersistedAlarms()
        }
    }
}

private fun GoalNotificationState.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("deadline", deadline.toString())
    .put("notificationTime", notificationTime.toString())
    .put("weeklyAvailableMinutes", weeklyAvailableMinutes)
    .put("progressPercentage", progressPercentage)
    .put("probabilityPercentage", probabilityPercentage)
    .put("privacyMode", privacyMode)
    .put("aggressive", aggressive)
    .put("repeatEveryMinutes", repeatEveryMinutes)
    .put("requiresTodayProgressAddress", requiresTodayProgressAddress)
