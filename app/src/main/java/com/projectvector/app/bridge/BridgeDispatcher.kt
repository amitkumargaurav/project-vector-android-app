package com.projectvector.app.bridge

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import com.projectvector.app.BuildConfig
import com.projectvector.app.browser.InAppBrowserActivity
import com.projectvector.app.auth.AuthRepository
import com.projectvector.app.auth.GoogleAuthManager
import com.projectvector.app.core.security.TokenStore
import com.projectvector.app.notifications.FcmTokenProvider
import com.projectvector.app.notifications.GoalNotificationScheduler
import com.projectvector.app.notifications.LocalReminderScheduler
import com.projectvector.app.notifications.NotificationPermissionManager
import com.projectvector.app.webview.BackPressController
import com.projectvector.app.webview.BackPressMode
import com.projectvector.app.webview.WebStateStore
import com.projectvector.app.webview.WebSessionCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BridgeDispatcher @Inject constructor(
    private val fcmTokenProvider: FcmTokenProvider,
    private val permissionManager: NotificationPermissionManager,
    private val reminderScheduler: LocalReminderScheduler,
    private val goalNotificationScheduler: GoalNotificationScheduler,
    private val tokenStore: TokenStore,
    private val backPressController: BackPressController,
    private val callbackSender: ReactCallbackSender,
    private val googleAuthManager: GoogleAuthManager,
    private val authRepository: AuthRepository,
    private val webSessionCleaner: WebSessionCleaner,
    private val webStateStore: WebStateStore,
) {
    suspend fun dispatch(activity: Activity, method: String, payload: JSONObject?): JSONObject = runCatching {
        when (method) {
            "requestGoogleIdToken" -> googleAuthManager.requestGoogleIdToken(activity).fold(
                onSuccess = { JSONObject().result(JSONObject().put("idToken", it)) },
                onFailure = { bridgeError(it.message ?: "Unable to request Google ID token") },
            )
            "getFcmToken" -> fcmTokenProvider.getToken().fold(
                onSuccess = { JSONObject().result(JSONObject().put("token", it)) },
                onFailure = { bridgeError(it.message ?: "Unable to fetch FCM token") },
            )
            "checkNotificationPermission" -> JSONObject().result(JSONObject().put("granted", permissionManager.isGranted()))
            "requestNotificationPermission" -> JSONObject().result(JSONObject().put("granted", permissionManager.request()))
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
            "setGoalNotifications" -> {
                val goalNotifications = requirePayload(payload).toGoalNotificationPayload()
                val scheduled = goalNotificationScheduler.setGoalNotifications(goalNotifications)
                JSONObject().result(JSONObject().put("scheduled", scheduled))
            }
            "markGoalProgressAddressed" -> {
                val addressed = requirePayload(payload).toMarkGoalProgressAddressedPayload()
                val applied = goalNotificationScheduler.markGoalProgressAddressed(addressed)
                JSONObject().result(JSONObject().put("applied", applied))
            }
            "getAppInfo" -> JSONObject().result(JSONObject().apply {
                put("platform", "android")
                put("appVersion", BuildConfig.VERSION_NAME)
                put("osVersion", Build.VERSION.RELEASE)
                put("backendUrl", BuildConfig.VECTOR_BACKEND_URL)
            })
            "secureStoreToken" -> {
                tokenStore.storeAccessToken(requirePayload(payload).requireString("token"))
                JSONObject().result(JSONObject().put("stored", true))
            }
            "secureStoreSession" -> {
                val session = requirePayload(payload).toSessionPayload()
                tokenStore.storeSession(session.accessToken, session.refreshToken, session.expiresAt, session.userId)
                JSONObject().result(JSONObject().put("stored", true))
            }
            "getSecureSession" -> JSONObject().result(JSONObject().apply {
                tokenStore.getSession().forEach { (key, value) -> value?.let { put(key, it) } }
            })
            "refreshAuthToken" -> authRepository.refreshAuthToken().fold(
                onSuccess = { session ->
                    JSONObject().result(JSONObject().put("accessToken", session.accessToken).apply {
                        session.refreshToken?.let { put("refreshToken", it) }
                    })
                },
                onFailure = { bridgeError(it.message ?: "Unable to refresh auth token") },
            )
            "clearSecureToken" -> {
                authRepository.clearSecureToken()
                webStateStore.clear()
                JSONObject().result(JSONObject().put("cleared", true))
            }
            "saveAppState" -> {
                webStateStore.saveAppState(requirePayload(payload))
                JSONObject().result(JSONObject().put("stored", true))
            }
            "getAppState" -> JSONObject().result(JSONObject().apply {
                webStateStore.getAppState()?.let { put("state", it) }
                val updatedAt = webStateStore.getAppStateUpdatedAt()
                if (updatedAt > 0L) put("updatedAt", updatedAt)
            })
            "clearAppState" -> {
                webStateStore.clearAppState()
                JSONObject().result(JSONObject().put("cleared", true))
            }
            "openPayment" -> {
                val payment = requirePayload(payload).toPaymentPayload()
                callbackSender.onPaymentFailed(reason = "Payments are handled by the web checkout flow", code = "web_checkout_required")
                JSONObject().result(JSONObject().put("status", "web_checkout_required").put("plan", payment.plan).put("userId", payment.userId))
            }
            "openUrl" -> {
                val openUrl = runCatching { requirePayload(payload).toOpenUrlPayload() }.getOrElse { error ->
                    return@runCatching openUrlError(error.message ?: "Bridge call failed")
                }
                val requestId = InAppBrowserActivity.createRequestId()
                val launchError = try {
                    activity.startActivity(InAppBrowserActivity.createIntent(activity, openUrl.url, requestId))
                    null
                } catch (_: ActivityNotFoundException) {
                    InAppBrowserActivity.cancelRequest(requestId)
                    JSONObject()
                        .put("ok", false)
                        .put("data", JSONObject().put("opened", false).put("copiedTexts", JSONArray()))
                        .put("error", "In-app browser unavailable")
                } catch (_: SecurityException) {
                    InAppBrowserActivity.cancelRequest(requestId)
                    JSONObject()
                        .put("ok", false)
                        .put("data", JSONObject().put("opened", false).put("copiedTexts", JSONArray()))
                        .put("error", "In-app browser unavailable")
                }
                launchError ?: run {
                    val copiedTexts = InAppBrowserActivity.awaitResult(requestId)
                    JSONObject().result(
                        JSONObject()
                            .put("opened", true)
                            .put("copiedTexts", JSONArray(copiedTexts)),
                    )
                }
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

    fun clearCurrentWebView(webView: WebView) {
        webSessionCleaner.clearCurrentWebView(webView)
    }

    private fun requirePayload(payload: JSONObject?): JSONObject = payload ?: throw IllegalArgumentException("Payload is required")

    private fun openUrlError(message: String): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("data", JSONObject().put("opened", false).put("copiedTexts", JSONArray()))
            .put("error", message)
}
