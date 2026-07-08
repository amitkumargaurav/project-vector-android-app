package com.projectvector.app.notifications

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRegistrationStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("vector_device_registration", Context.MODE_PRIVATE)

    val installId: String
        get() = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also { generated ->
            prefs.edit { putString(KEY_INSTALL_ID, generated) }
        }

    fun getBackendDeviceId(): String? = prefs.getString(KEY_BACKEND_DEVICE_ID, null)?.takeIf { it.isNotBlank() }

    fun storeBackendDeviceId(deviceId: String) {
        prefs.edit { putString(KEY_BACKEND_DEVICE_ID, deviceId) }
    }

    fun clearBackendDeviceId() {
        prefs.edit { remove(KEY_BACKEND_DEVICE_ID) }
    }

    fun hasSeenPrePermissionPrompt(): Boolean = prefs.getBoolean(KEY_SEEN_PRE_PERMISSION_PROMPT, false)

    fun markPrePermissionPromptSeen() {
        prefs.edit { putBoolean(KEY_SEEN_PRE_PERMISSION_PROMPT, true) }
    }

    fun getLastPermissionState(): String? = prefs.getString(KEY_LAST_PERMISSION_STATE, null)

    fun storeLastPermissionState(state: String) {
        prefs.edit { putString(KEY_LAST_PERMISSION_STATE, state) }
    }

    private companion object {
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_BACKEND_DEVICE_ID = "backend_device_id"
        const val KEY_SEEN_PRE_PERMISSION_PROMPT = "seen_pre_permission_prompt"
        const val KEY_LAST_PERMISSION_STATE = "last_permission_state"
    }
}
