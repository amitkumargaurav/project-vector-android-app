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
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
        previousGoalIds.minus(desiredGoalIds).forEach(::cancel)

        persist(payload)
        return payload.goals.sumOf { goal ->
            if (schedule(goal, payload.timezone)) 1 else 0
        }
    }

    fun restorePersistedAlarms() {
        val timezone = store.getString(KEY_TIMEZONE, null).orEmpty()
        readState().forEach { schedule(it, timezone) }
    }

    fun showAndReschedule(goalId: String) {
        val timezone = store.getString(KEY_TIMEZONE, null).orEmpty()
        val goal = readState().firstOrNull { it.id == goalId } ?: return
        showNotification(goal)
        schedule(goal, timezone)
    }

    private fun schedule(goal: GoalNotificationState, timezone: String): Boolean {
        val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
        val today = LocalDate.now(zone)
        if (goal.deadline.isBefore(today)) {
            cancel(goal.id)
            return false
        }

        val triggerAt = nextTriggerAt(zone).toEpochMilli()
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent(goal.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return false,
        )
        return true
    }

    private fun cancel(goalId: String) {
        pendingIntent(goalId, PendingIntent.FLAG_NO_CREATE)?.let(alarmManager::cancel)
    }

    private fun pendingIntent(goalId: String, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        requestCode(goalId),
        Intent(context, GoalNotificationReceiver::class.java).apply {
            action = ACTION_GOAL_REMINDER
            putExtra(EXTRA_GOAL_ID, goalId)
        },
        flag or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun nextTriggerAt(zone: ZoneId): Instant {
        val now = LocalDateTime.now(zone)
        val target = now.with(LocalTime.of(DEFAULT_REMINDER_HOUR, 0))
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
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode(goal.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(NotificationSound.uri(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(requestCode(goal.id), notification)
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

    companion object {
        const val ACTION_GOAL_REMINDER = "com.projectvector.app.notifications.GOAL_REMINDER"
        const val EXTRA_GOAL_ID = "goal_id"
        private const val PREFS_NAME = "goal_notifications"
        private const val KEY_GOALS = "goals"
        private const val KEY_TIMEZONE = "timezone"
        private const val KEY_SYNCED_AT = "synced_at"
        private const val DEFAULT_REMINDER_HOUR = 9
        private const val PRIVACY_SENSITIVE = "sensitive"

        fun requestCode(goalId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(goalId.toByteArray(Charsets.UTF_8))
            return ByteBuffer.wrap(digest).int and Int.MAX_VALUE
        }
    }
}

class GoalNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val goalId = intent.getStringExtra(GoalNotificationScheduler.EXTRA_GOAL_ID) ?: return
        GoalNotificationScheduler(context).showAndReschedule(goalId)
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
    .put("weeklyAvailableMinutes", weeklyAvailableMinutes)
    .put("progressPercentage", progressPercentage)
    .put("probabilityPercentage", probabilityPercentage)
    .put("privacyMode", privacyMode)
