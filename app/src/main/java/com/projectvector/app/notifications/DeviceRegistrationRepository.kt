package com.projectvector.app.notifications

import com.projectvector.app.BuildConfig
import com.projectvector.app.core.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRegistrationRepository @Inject constructor(
    private val tokenStore: TokenStore,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun registerDevice(payload: DeviceRegistrationPayload): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = authedRequestBuilder("/devices/register")
                .post(payload.toJson().toString().toRequestBody(JSON))
                .build()
                .executeJson()
            body.extractDeviceId() ?: error("Device registration response is missing deviceId")
        }.onFailure { Timber.w(it, "Device registration failed") }
    }

    suspend fun updateFcmToken(deviceId: String, token: String): Result<Unit> = updateDevice(deviceId, "/fcm-token", JSONObject().put("fcmToken", token))

    suspend fun updateNotificationPermission(deviceId: String, permission: String): Result<Unit> =
        updateDevice(deviceId, "/notification-permission", JSONObject().put("notificationPermission", permission))

    private suspend fun updateDevice(deviceId: String, suffix: String, payload: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            authedRequestBuilder("/devices/${deviceId}$suffix")
                .put(payload.toString().toRequestBody(JSON))
                .build()
                .executeJson()
            Unit
        }.onFailure { Timber.w(it, "Device update failed") }
    }

    private fun authedRequestBuilder(path: String): Request.Builder {
        if (BuildConfig.VECTOR_BACKEND_URL.isBlank()) error("VECTOR_BACKEND_URL is not configured")
        val accessToken = tokenStore.getAccessToken()?.takeIf { it.isNotBlank() } ?: error("User is not authenticated")
        return Request.Builder()
            .url("${BuildConfig.VECTOR_BACKEND_URL.trimEnd('/')}$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken")
    }

    private fun JSONObject.extractDeviceId(): String? =
        optString("deviceId").takeIf { it.isNotBlank() }
            ?: optString("id").takeIf { it.isNotBlank() }
            ?: optJSONObject("data")?.optString("deviceId")?.takeIf { it.isNotBlank() }
            ?: optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }

    private fun Request.executeJson(): JSONObject {
        client.newCall(this).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.isSuccessful) return responseBody.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
            if (response.code == HttpURLConnection.HTTP_NOT_FOUND) throw DeviceNotFoundException()
            error("Device request failed (${response.code})${responseBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class DeviceNotFoundException : IllegalStateException("Backend device was not found")

data class DeviceRegistrationPayload(
    val installId: String,
    val platform: String,
    val deviceName: String?,
    val timezone: String,
    val appVersion: String,
    val osVersion: String,
    val notificationPermission: String,
    val fcmToken: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("installId", installId)
        .put("platform", platform)
        .put("timezone", timezone)
        .put("appVersion", appVersion)
        .put("osVersion", osVersion)
        .put("notificationPermission", notificationPermission)
        .apply {
            deviceName?.let { put("deviceName", it) }
            fcmToken?.let { put("fcmToken", it) }
        }
}
