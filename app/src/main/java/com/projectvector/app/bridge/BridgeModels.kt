package com.projectvector.app.bridge

import org.json.JSONArray
import org.json.JSONObject

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

private fun JSONArray.toIntList(): List<Int> = List(length()) { index -> getInt(index) }
