package com.projectvector.app.notifications

import android.os.Build
import com.projectvector.app.BuildConfig
import com.projectvector.app.core.security.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationOnboardingManager @Inject constructor(
    private val tokenStore: TokenStore,
    private val store: DeviceRegistrationStore,
    private val permissionManager: NotificationPermissionManager,
    private val fcmTokenProvider: FcmTokenProvider,
    private val repository: DeviceRegistrationRepository,
) {
    private val setupMutex = Mutex()
    private val _uiState = MutableStateFlow<NotificationOnboardingUiState>(NotificationOnboardingUiState.Idle)
    val uiState: StateFlow<NotificationOnboardingUiState> = _uiState.asStateFlow()

    fun onLoggedInAppOpened(scope: CoroutineScope) {
        if (!tokenStore.hasSession()) return
        scope.launch { setupMutex.withLock { reconcileOnLaunch() } }
    }

    fun onPrePermissionAccepted(scope: CoroutineScope) {
        store.markPrePermissionPromptSeen()
        _uiState.value = NotificationOnboardingUiState.Idle
        scope.launch {
            runCatching {
                val granted = permissionManager.request()
                setupMutex.withLock {
                    if (granted) registerOrRecover(permissionState = PERMISSION_GRANTED)
                    else handleDenied()
                }
            }.onFailure { Timber.w(it, "Notification permission request failed") }
        }
    }

    fun onPrePermissionDismissed() {
        store.markPrePermissionPromptSeen()
        _uiState.value = NotificationOnboardingUiState.Idle
    }

    fun dismissSettingsExplanation() {
        _uiState.value = NotificationOnboardingUiState.Idle
    }

    fun onAppForegrounded(scope: CoroutineScope) {
        if (!tokenStore.hasSession()) return
        scope.launch {
            setupMutex.withLock {
                val state = currentPermissionState()
                if (state != store.getLastPermissionState()) {
                    store.storeLastPermissionState(state)
                    updatePermissionOrRegister(state)
                }
            }
        }
    }

    suspend fun onFcmTokenRefreshed(token: String) {
        setupMutex.withLock {
            val deviceId = store.getBackendDeviceId()
            if (deviceId != null) {
                repository.updateFcmToken(deviceId, token).recoverCatching { error ->
                    if (error is DeviceNotFoundException) {
                        store.clearBackendDeviceId()
                        registerOrRecover(currentPermissionState(), token)
                    } else throw error
                }.onFailure { Timber.w(it, "Unable to update refreshed FCM token") }
            }
        }
    }

    private suspend fun reconcileOnLaunch() {
        val deviceId = store.getBackendDeviceId()
        val permissionState = currentPermissionState()
        store.storeLastPermissionState(permissionState)

        if (deviceId != null) {
            updatePermissionOrRegister(permissionState)
            if (permissionState == PERMISSION_GRANTED) {
                fcmTokenProvider.getToken().onSuccess { token ->
                    repository.updateFcmToken(deviceId, token).recoverCatching { error ->
                        if (error is DeviceNotFoundException) {
                            store.clearBackendDeviceId()
                            registerOrRecover(permissionState, token)
                        } else throw error
                    }
                }.onFailure { Timber.w(it, "Unable to refresh FCM token on launch") }
            }
            return
        }

        if (permissionState == PERMISSION_GRANTED) {
            registerOrRecover(permissionState)
        } else if (!store.hasSeenPrePermissionPrompt()) {
            _uiState.value = NotificationOnboardingUiState.PrePermission
        } else {
            handleDenied()
        }
    }

    private suspend fun handleDenied() {
        val permissionState = PERMISSION_DENIED
        store.storeLastPermissionState(permissionState)
        updatePermissionOrRegister(permissionState)
        _uiState.value = NotificationOnboardingUiState.PermissionDenied
    }

    private suspend fun updatePermissionOrRegister(permissionState: String) {
        val deviceId = store.getBackendDeviceId()
        if (deviceId == null) {
            if (permissionState == PERMISSION_GRANTED) registerOrRecover(permissionState)
            return
        }
        repository.updateNotificationPermission(deviceId, permissionState).recoverCatching { error ->
            if (error is DeviceNotFoundException) {
                store.clearBackendDeviceId()
                registerOrRecover(permissionState)
            } else throw error
        }.onFailure { Timber.w(it, "Unable to update notification permission") }
    }

    private suspend fun registerOrRecover(permissionState: String, knownToken: String? = null) {
        val fcmToken = knownToken ?: if (permissionState == PERMISSION_GRANTED) {
            fcmTokenProvider.getToken().getOrElse { error ->
                Timber.w(error, "Unable to fetch FCM token for device registration")
                null
            }
        } else null

        if (fcmToken.isNullOrBlank()) return

        repository.registerDevice(buildPayload(permissionState, fcmToken)).onSuccess { deviceId ->
            store.storeBackendDeviceId(deviceId)
        }.onFailure { Timber.w(it, "Unable to register device") }
    }

    private fun buildPayload(permissionState: String, fcmToken: String?) = DeviceRegistrationPayload(
        installId = store.installId,
        platform = "android",
        deviceName = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").takeIf { it.isNotBlank() },
        timezone = TimeZone.getDefault().id,
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = Build.VERSION.RELEASE,
        notificationPermission = permissionState,
        fcmToken = fcmToken,
    )

    private fun currentPermissionState(): String = if (permissionManager.isGranted()) PERMISSION_GRANTED else PERMISSION_DENIED

    companion object {
        const val PERMISSION_GRANTED = "granted"
        const val PERMISSION_DENIED = "denied"
    }
}

sealed interface NotificationOnboardingUiState {
    data object Idle : NotificationOnboardingUiState
    data object PrePermission : NotificationOnboardingUiState
    data object PermissionDenied : NotificationOnboardingUiState
}
