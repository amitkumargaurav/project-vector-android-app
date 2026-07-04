package com.projectvector.app.bridge

import android.app.Activity
import android.content.Intent
import android.os.Build
import com.projectvector.app.BuildConfig
import com.projectvector.app.core.security.TokenStore
import com.projectvector.app.notifications.FcmTokenProvider
import com.projectvector.app.notifications.LocalReminderScheduler
import com.projectvector.app.notifications.NotificationPermissionManager
import com.projectvector.app.webview.BackPressController
import com.projectvector.app.webview.BackPressMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BridgeDispatcher @Inject constructor(
    private val fcmTokenProvider: FcmTokenProvider,
    private val permissionManager: NotificationPermissionManager,
    private val reminderScheduler: LocalReminderScheduler,
    private val tokenStore: TokenStore,
    private val backPressController: BackPressController,
    private val callbackSender: ReactCallbackSender,
) {
    suspend fun dispatch(activity: Activity, method: String, payload: JSONObject?): JSONObject = runCatching {
        when (method) {
            "getFcmToken" -> fcmTokenProvider.getToken().fold(
                onSuccess = { JSONObject().result(JSONObject().put("token", it)) },
                onFailure = { bridgeError(it.message ?: "Unable to fetch FCM token") },
            )
            "checkNotificationPermission" -> JSONObject().result(JSONObject().put("granted", permissionManager.isGranted()))
            "requestNotificationPermission" -> JSONObject().result(JSONObject().put("granted", permissionManager.request(activity)))
            "scheduleLocalReminder" -> {
                val reminder = requirePayload(payload).toReminderPayload()
                withContext(Dispatchers.Default) { reminderScheduler.schedule(reminder) }
                JSONObject().result(JSONObject().put("id", reminder.id))
            }
            "cancelLocalReminder" -> {
                val id = requirePayload(payload).requireString("id")
                reminderScheduler.cancel(id)
                JSONObject().result(JSONObject().put("cancelled", true))
            }
            "getAppInfo" -> JSONObject().result(JSONObject().apply {
                put("platform", "android")
                put("appVersion", BuildConfig.VERSION_NAME)
                put("osVersion", Build.VERSION.RELEASE)
            })
            "secureStoreToken" -> {
                // TODO: Replace single-token storage with the backend session model once auth exchange is defined
                // (access token, refresh token, device id, user/session metadata).
                tokenStore.storeAccessToken(requirePayload(payload).requireString("token"))
                JSONObject().result(JSONObject().put("stored", true))
            }
            "clearSecureToken" -> {
                tokenStore.clear()
                JSONObject().result(JSONObject().put("cleared", true))
            }
            "openPayment" -> {
                val payment = requirePayload(payload).toPaymentPayload()
                // TODO: Integrate the selected native payment SDK if Phase 0 moves away from React/web payments.
                // Backend webhook/subscription status must remain final truth.
                callbackSender.onPaymentFailed(reason = "Native payments are not configured", code = "not_configured")
                JSONObject().result(JSONObject().put("status", "not_configured").put("plan", payment.plan))
            }
            "share" -> {
                val share = requirePayload(payload).toSharePayload()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, share.title)
                    putExtra(Intent.EXTRA_SUBJECT, share.title)
                    putExtra(Intent.EXTRA_TEXT, share.text)
                }
                activity.startActivity(Intent.createChooser(intent, share.title))
                JSONObject().result(JSONObject().put("shared", true))
            }
            "setBackPressBehavior" -> {
                val mode = when (requirePayload(payload).toBackPressPayload().mode) {
                    "confirm-exit" -> BackPressMode.CONFIRM_EXIT
                    "disabled" -> BackPressMode.DISABLED
                    else -> BackPressMode.DEFAULT
                }
                backPressController.setMode(mode)
                JSONObject().result(JSONObject().put("applied", true))
            }
            else -> bridgeError("Unknown bridge method: $method")
        }
    }.getOrElse { bridgeError(it.message ?: "Bridge call failed") }

    private fun requirePayload(payload: JSONObject?): JSONObject = payload ?: throw IllegalArgumentException("Payload is required")
}
