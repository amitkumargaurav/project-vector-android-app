package com.projectvector.app.auth

import com.projectvector.app.BuildConfig
import com.projectvector.app.core.security.AuthSession
import com.projectvector.app.core.security.TokenStore
import com.projectvector.app.notifications.DeviceRegistrationStore
import com.projectvector.app.notifications.GoalNotificationScheduler
import com.projectvector.app.notifications.LocalReminderScheduler
import com.projectvector.app.webview.WebStateStore
import com.projectvector.app.webview.WebSessionCleaner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
  private val tokenStore: TokenStore,
  private val webSessionCleaner: WebSessionCleaner,
  private val webStateStore: WebStateStore,
  private val goalNotificationScheduler: GoalNotificationScheduler,
  private val localReminderScheduler: LocalReminderScheduler,
  private val deviceRegistrationStore: DeviceRegistrationStore,
) {
  private val authHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .apply {
      if (BuildConfig.DEBUG) {
        addInterceptor(
          HttpLoggingInterceptor { message -> Timber.tag(OKHTTP_LOG_TAG).d(message) }
            .setLevel(HttpLoggingInterceptor.Level.BODY),
        )
      }
    }
    .build()

  private val refreshMutex = Mutex()
  private var refreshInFlight: CompletableDeferred<Result<AuthSession>>? = null
  private val _sessionInvalidated = MutableSharedFlow<SessionInvalidation>(extraBufferCapacity = 1)
  val sessionInvalidated: SharedFlow<SessionInvalidation> = _sessionInvalidated.asSharedFlow()

  suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> =
    withContext(Dispatchers.IO) {
      runCatching {
        if (BuildConfig.VECTOR_BACKEND_URL.isBlank()) {
          error("VECTOR_BACKEND_URL is not configured")
        }

        val request = Request.Builder()
          .url("${BuildConfig.VECTOR_BACKEND_URL.trimEnd('/')}/auth/google")
          .post(JSONObject().put("idToken", idToken).toString().toRequestBody(JSON))
          .header("Accept", "application/json")
          .build()

        val body = authHttpClient.newCall(request).execute().use { response ->
          val responseBody = response.body?.string().orEmpty()
          if (response.isSuccessful) {
            responseBody
          } else {
            error(
              "Login failed (${response.code})${
                responseBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
              }"
            )
          }
        }

        val json = JSONObject(body)
        AuthSession(
          accessToken = json.requireString("accessToken"),
          refreshToken = json.requireString("refreshToken"),
        ).also(tokenStore::storeSession)
      }
    }

  suspend fun refreshAuthToken(): Result<AuthSession> {
    var shouldStartRefresh = false
    val deferred = refreshMutex.withLock {
      refreshInFlight ?: CompletableDeferred<Result<AuthSession>>().also {
        refreshInFlight = it
        shouldStartRefresh = true
      }
    }

    if (!shouldStartRefresh) return deferred.await()

    return try {
      val result = refreshAuthTokenInternal()
      deferred.complete(result)
      result
    } catch (error: CancellationException) {
      deferred.completeExceptionally(error)
      throw error
    } catch (error: Throwable) {
      val result = Result.failure<AuthSession>(error)
      deferred.complete(result)
      result
    } finally {
      refreshMutex.withLock {
        if (refreshInFlight === deferred) refreshInFlight = null
      }
    }
  }

  fun clearSecureToken() {
    invalidateSession(message = null)
  }

  private suspend fun refreshAuthTokenInternal(): Result<AuthSession> =
    withContext(Dispatchers.IO) {
      runCatching {
        if (BuildConfig.VECTOR_BACKEND_URL.isBlank()) {
          error("VECTOR_BACKEND_URL is not configured")
        }

        val previousRefreshToken = tokenStore.getRefreshToken()?.takeIf { it.isNotBlank() }
          ?: throw RefreshTokenRejectedException("Refresh token is missing")

        val request = Request.Builder()
          .url("${BuildConfig.VECTOR_BACKEND_URL.trimEnd('/')}/auth/refresh")
          .post(
            JSONObject().put("refreshToken", previousRefreshToken).toString().toRequestBody(JSON)
          )
          .header("Accept", "application/json")
          .build()

        val body = authHttpClient.newCall(request).execute().use { response ->
          val responseBody = response.body?.string().orEmpty()
          if (response.isSuccessful) {
            responseBody
          } else {
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED || response.code == HttpURLConnection.HTTP_FORBIDDEN) {
              throw RefreshTokenRejectedException("Refresh token was rejected")
            }
            error(
              "Refresh failed (${response.code})${
                responseBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
              }"
            )
          }
        }

        if (body.isBlank()) error("Refresh response is empty")

        val json = JSONObject(body)
        val accessToken = json.requireString("accessToken")
        val refreshToken = json.requireString("refreshToken")
        AuthSession(
          accessToken = accessToken,
          refreshToken = refreshToken,
        ).also {
          tokenStore.storeSession(it)
        }
      }.onFailure {
        invalidateSession(message = "Session expired. Please sign in again.")
      }
    }

  private fun invalidateSession(message: String?) {
    tokenStore.clear()
    webSessionCleaner.clearPersistentData()
    webStateStore.clear()
    goalNotificationScheduler.clearAll()
    localReminderScheduler.cancelAll()
    deviceRegistrationStore.clearBackendDeviceId()
    _sessionInvalidated.tryEmit(SessionInvalidation(message))
  }

  private fun JSONObject.requireString(name: String): String =
    optString(name).takeIf { it.isNotBlank() }
      ?: error("Auth response is missing $name")

  private class RefreshTokenRejectedException(message: String) : IllegalStateException(message)

  private companion object {
    const val OKHTTP_LOG_TAG = "OkHttp"
    val JSON = "application/json; charset=utf-8".toMediaType()
  }
}

data class SessionInvalidation(val message: String?)
