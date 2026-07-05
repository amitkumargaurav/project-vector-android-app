package com.projectvector.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.projectvector.app.bridge.ReactCallbackSender
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callbackSender: ReactCallbackSender,
) {
    private var launcher: ActivityResultLauncher<String>? = null

    fun bindLauncher(launcher: ActivityResultLauncher<String>) {
        this.launcher = launcher
    }

    fun onPermissionResult(granted: Boolean) {
        callbackSender.onPermissionResult("notifications", granted)
    }

    fun isGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun request(): Boolean {
        if (isGranted()) {
            callbackSender.onPermissionResult("notifications", true)
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher?.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return false
    }
}
