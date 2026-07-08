package com.projectvector.app.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.projectvector.app.MainActivity
import com.projectvector.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.projectvector.app.bridge.NotificationRoutePayload
import com.projectvector.app.bridge.ReactCallbackSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class VectorFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var callbackSender: ReactCallbackSender
    @Inject lateinit var notificationOnboardingManager: NotificationOnboardingManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        serviceScope.launch { notificationOnboardingManager.onFcmTokenRefreshed(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val route = message.data["route"] ?: return
        val payload = NotificationRoutePayload(
            route = route,
            date = message.data["date"],
            taskId = message.data["taskId"],
            goalId = message.data["goalId"],
        )
        callbackSender.onNotificationClicked(payload)
        showNotification(message, payload)
    }

    private fun showNotification(message: RemoteMessage, payload: NotificationRoutePayload) {
        if (!canPostNotifications()) return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("route", payload.route)
            payload.date?.let { putExtra("date", it) }
            payload.taskId?.let { putExtra("taskId", it) }
            payload.goalId?.let { putExtra("goalId", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            payload.route.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(message.notification?.title ?: message.data["title"] ?: "Project Vector")
            .setContentText(message.notification?.body ?: message.data["body"] ?: "Open Project Vector")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(message.messageId?.hashCode() ?: payload.route.hashCode(), notification)
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
