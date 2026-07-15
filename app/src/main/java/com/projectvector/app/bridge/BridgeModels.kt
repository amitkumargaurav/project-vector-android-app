package com.projectvector.app.bridge

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ReminderPayload(
    val id: String,
    val title: String,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: List<Int>?,
)

data class PaymentPayload(val plan: String, val userId: String)
data class SessionPayload(val accessToken: String, val refreshToken: String?, val expiresAt: String?, val userId: String?)
data class SharePayload(val title: String, val text: String)
data class BackPressPayload(val mode: String)

data class GoalNotificationState(
    val id: String,
    val title: String,
    val deadline: String,
    val notificationHour: Int,
    val notificationMinute: Int,
    val weeklyAvailableMinutes: Int,
    val progressPercentage: Int,
    val probabilityPercentage: Int,
    val privacyMode: String,
    val aggressive: Boolean,
    val repeatEveryMinutes: Int?,
    val requiresTodayProgressAddress: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject): GoalNotificationState = json.toGoalNotificationState()
    }
}

data class GoalNotificationPayload(
    val goals: List<GoalNotificationState>,
    val timezone: String,
    val timezoneOffsetMinutes: Int?,
    val syncedAt: String,
)

data class MarkGoalProgressAddressedPayload(
    val goalId: String,
    val addressedAt: String,
)

data class NotificationRoutePayload(
    val route: String,
    val date: String? = null,
    val taskId: String? = null,
    val goalId: String? = null,
)

fun JSONObject.result(data: JSONObject? = null): JSONObject = JSONObject().put("ok", true).apply {
    if (data != null) put("data", data)
}

fun bridgeError(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)

fun JSONObject.requireString(name: String): String = optString(name).takeIf { it.isNotBlank() }
    ?: throw IllegalArgumentException("Missing required string: $name")

fun JSONObject.requireIntInRange(name: String, min: Int, max: Int): Int {
    val value = if (has(name)) getInt(name) else throw IllegalArgumentException("Missing required int: $name")
    require(value in min..max) { "$name must be between $min and $max" }
    return value
}

fun JSONObject.toReminderPayload(): ReminderPayload = ReminderPayload(
    id = requireString("id"),
    title = requireString("title"),
    hour = requireIntInRange("hour", 0, 23),
    minute = requireIntInRange("minute", 0, 59),
    daysOfWeek = optJSONArray("daysOfWeek")?.toIntList()?.also { days ->
        require(days.all { it in 1..7 }) { "daysOfWeek values must be between 1 and 7" }
    },
)

fun JSONObject.toPaymentPayload(): PaymentPayload {
    val plan = requireString("plan")
    require(plan == "premium_monthly" || plan == "premium_yearly") { "Invalid payment plan" }
    return PaymentPayload(plan = plan, userId = requireString("userId"))
}

fun JSONObject.toSessionPayload(): SessionPayload = SessionPayload(
    accessToken = requireString("accessToken"),
    refreshToken = optString("refreshToken").takeIf { it.isNotBlank() },
    expiresAt = optString("expiresAt").takeIf { it.isNotBlank() },
    userId = optString("userId").takeIf { it.isNotBlank() },
)

fun JSONObject.toSharePayload(): SharePayload = SharePayload(title = requireString("title"), text = requireString("text"))
fun JSONObject.toBackPressPayload(): BackPressPayload {
    val mode = requireString("mode")
    require(mode == "default" || mode == "confirm-exit" || mode == "disabled") { "Invalid back press mode" }
    return BackPressPayload(mode)
}

fun JSONObject.toGoalNotificationPayload(): GoalNotificationPayload {
    val goalsArray = optJSONArray("goals") ?: throw IllegalArgumentException("Missing required array: goals")
    val emptyPayload = goalsArray.length() == 0
    val timezone = optString("timezone").takeIf { it.isNotBlank() }
        ?: if (emptyPayload) TimeZone.getDefault().id else throw IllegalArgumentException("Missing required string: timezone")
    requireValidTimeZone(timezone)
    val timezoneOffsetMinutes = optNullableInt("timezoneOffsetMinutes")
    val syncedAt = optString("syncedAt").takeIf { it.isNotBlank() }
        ?: if (emptyPayload) utcTimestampNow() else throw IllegalArgumentException("Missing required string: syncedAt")
    parseUtcTimestampMillis(syncedAt) ?: throw IllegalArgumentException("Invalid syncedAt")
    return GoalNotificationPayload(
        goals = goalsArray.toGoalNotificationStateList(),
        timezone = timezone,
        timezoneOffsetMinutes = timezoneOffsetMinutes,
        syncedAt = syncedAt,
    )
}

fun JSONObject.toGoalNotificationState(): GoalNotificationState {
    val privacyMode = requireString("privacyMode")
    require(privacyMode == "standard" || privacyMode == "sensitive") { "Invalid privacyMode" }
    val repeatEveryMinutes = optNullablePositiveInt("repeatEveryMinutes")
    val notificationTime = requireNotificationTime()
    return GoalNotificationState(
        id = requireString("id"),
        title = requireString("title"),
        deadline = requireIsoDate("deadline"),
        notificationHour = notificationTime.hour,
        notificationMinute = notificationTime.minute,
        weeklyAvailableMinutes = getRequiredNonNegativeInt("weeklyAvailableMinutes"),
        progressPercentage = requireIntInRange("progressPercentage", 0, 100),
        probabilityPercentage = requireIntInRange("probabilityPercentage", 0, 100),
        privacyMode = privacyMode,
        aggressive = optBoolean("aggressive", false),
        repeatEveryMinutes = repeatEveryMinutes,
        requiresTodayProgressAddress = optBoolean("requiresTodayProgressAddress", false),
    )
}

fun JSONObject.toMarkGoalProgressAddressedPayload(): MarkGoalProgressAddressedPayload {
    val addressedAt = requireString("addressedAt")
    parseUtcTimestampMillis(addressedAt) ?: throw IllegalArgumentException("Invalid addressedAt")
    return MarkGoalProgressAddressedPayload(goalId = requireString("goalId"), addressedAt = addressedAt)
}

private data class NotificationTime(val hour: Int, val minute: Int) {
    override fun toString(): String = "%02d:%02d".format(Locale.US, hour, minute)
}

private fun JSONObject.getRequiredNonNegativeInt(name: String): Int {
    val value = if (has(name)) getInt(name) else throw IllegalArgumentException("Missing required int: $name")
    require(value >= 0) { "$name must be non-negative" }
    return value
}

private fun JSONObject.optNullablePositiveInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    val value = getInt(name)
    require(value > 0) { "$name must be positive" }
    return value
}

private fun JSONObject.optNullableInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return getInt(name)
}

private fun JSONObject.requireNotificationTime(): NotificationTime {
    val hasHour = has("notificationHour") && !isNull("notificationHour")
    val hasMinute = has("notificationMinute") && !isNull("notificationMinute")
    if (hasHour || hasMinute) {
        return NotificationTime(
            hour = requireIntInRange("notificationHour", 0, 23),
            minute = requireIntInRange("notificationMinute", 0, 59),
        )
    }

    val value = optString("notificationTime").takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing notificationHour/notificationMinute or notificationTime")
    val match = Regex("""^(\d{2}):(\d{2})(?::\d{2})?$""").matchEntire(value)
        ?: throw IllegalArgumentException("Invalid notificationTime")
    return NotificationTime(
        hour = match.groupValues[1].toInt().also { require(it in 0..23) { "Invalid notificationTime" } },
        minute = match.groupValues[2].toInt().also { require(it in 0..59) { "Invalid notificationTime" } },
    )
}

private fun JSONArray.toGoalNotificationStateList(): List<GoalNotificationState> {
    val goals = mutableListOf<GoalNotificationState>()
    for (index in 0 until length()) {
        runCatching {
            getJSONObject(index).toGoalNotificationState()
        }.onSuccess { goal ->
            goals += goal
        }.onFailure { error ->
            Timber.w(error, "Ignoring invalid goal notification payload at index %d", index)
        }
    }
    return goals
}

private fun JSONArray.toIntList(): List<Int> = List(length()) { index -> getInt(index) }

private fun JSONObject.requireIsoDate(name: String): String {
    val value = requireString(name)
    parseIsoDate(value) ?: throw IllegalArgumentException("Invalid $name")
    return value
}

private fun parseIsoDate(value: String): Date? =
    parseStrict(value, "yyyy-MM-dd", TimeZone.getTimeZone("UTC"))

private fun parseUtcTimestampMillis(value: String): Long? =
    listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSX",
        "yyyy-MM-dd'T'HH:mm:ssX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
    ).firstNotNullOfOrNull { pattern ->
        parseStrict(value, pattern, TimeZone.getTimeZone("UTC"))?.time
    }

private fun utcTimestampNow(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }.format(Date())

private fun parseStrict(value: String, pattern: String, timeZone: TimeZone): Date? {
    val position = ParsePosition(0)
    val parsed = SimpleDateFormat(pattern, Locale.US).apply {
        this.timeZone = timeZone
        isLenient = false
    }.parse(value, position)
    return parsed?.takeIf { position.index == value.length }
}

private fun requireValidTimeZone(timezone: String) {
    val valid = timezone == "GMT" || TimeZone.getAvailableIDs().contains(timezone)
    require(valid) { "Invalid timezone" }
}
