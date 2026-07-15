package com.projectvector.app.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.projectvector.app.MainActivity
import com.projectvector.app.R
import com.projectvector.app.bridge.GoalNotificationPayload
import com.projectvector.app.bridge.GoalNotificationState
import com.projectvector.app.bridge.MarkGoalProgressAddressedPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoalNotificationScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val store = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setGoalNotifications(payload: GoalNotificationPayload): Int {
        readState().forEach { goal -> cancelAllForGoal(goal.id) }
        persist(payload)
        if (payload.goals.isEmpty()) return 0

        val today = todayIsoDate(payload.timezone)
        var scheduled = 0
        payload.goals.forEach { goal ->
            resetAggressiveRepeatCount(goal.id, today)
            if (scheduleDaily(goal, payload.timezone)) scheduled += 1
        }
        return scheduled
    }

    fun markGoalProgressAddressed(payload: MarkGoalProgressAddressedPayload): Boolean {
        val timezone = currentTimezoneId()
        val addressedDate = isoDateFromTimestamp(payload.addressedAt, timezone) ?: todayIsoDate(timezone)
        markAddressed(payload.goalId, addressedDate)
        cancelAggressiveRepeat(payload.goalId)
        readState().firstOrNull { it.id == payload.goalId }?.let { scheduleDaily(it, timezone) }
        return true
    }

    fun restorePersistedAlarms() {
        val timezone = currentTimezoneId()
        readState().forEach { goal ->
            cancelAllForGoal(goal.id)
            scheduleDaily(goal, timezone)
        }
    }

    fun clearAll() {
        readState().forEach { goal -> cancelAllForGoal(goal.id) }
        store.edit().clear().apply()
    }

    fun onAlarm(goalId: String, alarmKind: String) {
        val goal = readState().firstOrNull { it.id == goalId } ?: return
        val timezone = currentTimezoneId()
        val today = todayIsoDate(timezone)
        if (compareIsoDates(goal.deadline, today) < 0) {
            cancelAllForGoal(goal.id)
            return
        }

        when (alarmKind) {
            ALARM_KIND_DAILY -> {
                resetAggressiveRepeatCount(goal.id, today)
                showNotification(goal)
                scheduleDaily(goal, timezone)
                if (goal.shouldScheduleAggressiveRepeat(today)) {
                    scheduleAggressiveRepeat(goal)
                } else {
                    cancelAggressiveRepeat(goal.id)
                }
            }
            ALARM_KIND_AGGRESSIVE -> {
                if (!goal.shouldScheduleAggressiveRepeat(today)) {
                    cancelAggressiveRepeat(goal.id)
                    return
                }

                val repeatCount = incrementAggressiveRepeatCount(goal.id, today)
                if (repeatCount > MAX_AGGRESSIVE_REPEATS) {
                    cancelAggressiveRepeat(goal.id)
                    return
                }

                showNotification(goal)
                if (repeatCount < MAX_AGGRESSIVE_REPEATS && goal.shouldScheduleAggressiveRepeat(today)) {
                    scheduleAggressiveRepeat(goal)
                } else {
                    cancelAggressiveRepeat(goal.id)
                }
            }
        }
    }

    fun onNotificationTapped(goalId: String) {
        markAddressed(goalId, todayIsoDate(currentTimezoneId()))
        cancelAggressiveRepeat(goalId)
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun scheduleDaily(goal: GoalNotificationState, timezone: String): Boolean {
        val today = todayIsoDate(timezone)
        if (compareIsoDates(goal.deadline, today) < 0) {
            cancelAllForGoal(goal.id)
            return false
        }
        val triggerAt = nextDailyTriggerAt(goal, timezone)
        if (compareIsoDates(isoDateAt(triggerAt, timezone), goal.deadline) > 0) {
            cancelAllForGoal(goal.id)
            return false
        }

        setAlarm(
            goalId = goal.id,
            alarmKind = ALARM_KIND_DAILY,
            triggerAt = triggerAt,
        )
        return true
    }

    private fun scheduleAggressiveRepeat(goal: GoalNotificationState) {
        val delayMinutes = goal.repeatEveryMinutes ?: return
        setAlarm(
            goalId = goal.id,
            alarmKind = ALARM_KIND_AGGRESSIVE,
            triggerAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.toLong()),
        )
    }

    private fun setAlarm(goalId: String, alarmKind: String, triggerAt: Long) {
        val pendingIntent = alarmPendingIntent(goalId, alarmKind, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
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

    private fun nextDailyTriggerAt(goal: GoalNotificationState, timezone: String): Long {
        val now = Calendar.getInstance(timezoneOrDefault(timezone))
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, goal.notificationHour)
            set(Calendar.MINUTE, goal.notificationMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DATE, 1)
        }
        return target.timeInMillis
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
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(NotificationSound.uri(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(requestCode(goal.id, ALARM_KIND_DAILY), notification)
    }

    private fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun GoalNotificationState.shouldScheduleAggressiveRepeat(today: String): Boolean =
        aggressive && repeatEveryMinutes != null && requiresTodayProgressAddress && compareIsoDates(deadline, today) >= 0 && addressedDate(id) != today

    private fun resetAggressiveRepeatCount(goalId: String, date: String) {
        store.edit()
            .putString(aggressiveRepeatDateKey(goalId), date)
            .putInt(aggressiveRepeatCountKey(goalId), 0)
            .apply()
    }

    private fun incrementAggressiveRepeatCount(goalId: String, date: String): Int {
        val count = aggressiveRepeatCount(goalId, date) + 1
        store.edit()
            .putString(aggressiveRepeatDateKey(goalId), date)
            .putInt(aggressiveRepeatCountKey(goalId), count)
            .apply()
        return count
    }

    private fun aggressiveRepeatCount(goalId: String, date: String): Int {
        val storedDate = store.getString(aggressiveRepeatDateKey(goalId), null)
        return if (storedDate == date) store.getInt(aggressiveRepeatCountKey(goalId), 0) else 0
    }

    private fun markAddressed(goalId: String, date: String) {
        store.edit().putString(addressedKey(goalId), date).apply()
    }

    private fun addressedDate(goalId: String): String? = store.getString(addressedKey(goalId), null)?.takeIf(::isIsoDate)

    private fun persist(payload: GoalNotificationPayload) {
        val editor = store.edit()
            .putString(KEY_GOALS, JSONArray(payload.goals.map { it.toJson() }).toString())
            .putString(KEY_TIMEZONE, payload.timezone)
            .putString(KEY_SYNCED_AT, payload.syncedAt)
        payload.timezoneOffsetMinutes?.let { editor.putInt(KEY_TIMEZONE_OFFSET_MINUTES, it) }
            ?: editor.remove(KEY_TIMEZONE_OFFSET_MINUTES)
        editor.apply()
    }

    private fun readState(): List<GoalNotificationState> {
        val raw = store.getString(KEY_GOALS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> GoalNotificationState.fromJson(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    private fun currentTimezoneId(): String = timezoneOrDefault(store.getString(KEY_TIMEZONE, null).orEmpty()).id
    private fun timezoneOrDefault(timezone: String): TimeZone =
        if (timezone == "GMT" || TimeZone.getAvailableIDs().contains(timezone)) TimeZone.getTimeZone(timezone) else TimeZone.getDefault()
    private fun todayIsoDate(timezone: String): String = isoDateAt(System.currentTimeMillis(), timezone)
    private fun isoDateAt(epochMillis: Long, timezone: String): String =
        isoDateFormat(timezoneOrDefault(timezone)).format(Date(epochMillis))
    private fun compareIsoDates(left: String, right: String): Int = left.compareTo(right)
    private fun isIsoDate(value: String): Boolean = parseIsoDate(value) != null
    private fun parseIsoDate(value: String): Date? = parseStrict(value, "yyyy-MM-dd", TimeZone.getTimeZone("UTC"))
    private fun isoDateFromTimestamp(value: String, timezone: String): String? {
        val millis = parseTimestampMillis(value) ?: return null
        return isoDateAt(millis, timezone)
    }
    private fun parseTimestampMillis(value: String): Long? =
        listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        ).firstNotNullOfOrNull { pattern ->
            parseStrict(value, pattern, TimeZone.getTimeZone("UTC"))?.time
        }
    private fun parseStrict(value: String, pattern: String, timeZone: TimeZone): Date? {
        val position = ParsePosition(0)
        val parsed = SimpleDateFormat(pattern, Locale.US).apply {
            this.timeZone = timeZone
            isLenient = false
        }.parse(value, position)
        return parsed?.takeIf { position.index == value.length }
    }
    private fun isoDateFormat(timeZone: TimeZone): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
            isLenient = false
        }
    private fun addressedKey(goalId: String): String = "${KEY_ADDRESSED_PREFIX}${requestCode(goalId, ALARM_KIND_DAILY)}"
    private fun aggressiveRepeatCountKey(goalId: String): String = "${KEY_AGGRESSIVE_REPEAT_COUNT_PREFIX}${requestCode(goalId, ALARM_KIND_AGGRESSIVE)}"
    private fun aggressiveRepeatDateKey(goalId: String): String = "${KEY_AGGRESSIVE_REPEAT_DATE_PREFIX}${requestCode(goalId, ALARM_KIND_AGGRESSIVE)}"

    companion object {
        const val ACTION_GOAL_REMINDER = "com.projectvector.app.notifications.GOAL_REMINDER"
        const val ACTION_GOAL_REMINDER_TAPPED = "com.projectvector.app.notifications.GOAL_REMINDER_TAPPED"
        const val EXTRA_GOAL_ID = "goal_id"
        const val EXTRA_ALARM_KIND = "alarm_kind"
        private const val PREFS_NAME = "goal_notifications"
        private const val KEY_GOALS = "goals"
        private const val KEY_TIMEZONE = "timezone"
        private const val KEY_TIMEZONE_OFFSET_MINUTES = "timezone_offset_minutes"
        private const val KEY_SYNCED_AT = "synced_at"
        private const val KEY_ADDRESSED_PREFIX = "addressed_"
        private const val KEY_AGGRESSIVE_REPEAT_COUNT_PREFIX = "aggressive_repeat_count_"
        private const val KEY_AGGRESSIVE_REPEAT_DATE_PREFIX = "aggressive_repeat_date_"
        private const val MAX_AGGRESSIVE_REPEATS = 4
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
    .put("deadline", deadline)
    .put("notificationHour", notificationHour)
    .put("notificationMinute", notificationMinute)
    .put("notificationTime", "%02d:%02d".format(Locale.US, notificationHour, notificationMinute))
    .put("weeklyAvailableMinutes", weeklyAvailableMinutes)
    .put("progressPercentage", progressPercentage)
    .put("probabilityPercentage", probabilityPercentage)
    .put("privacyMode", privacyMode)
    .put("aggressive", aggressive)
    .put("repeatEveryMinutes", repeatEveryMinutes)
    .put("requiresTodayProgressAddress", requiresTodayProgressAddress)
