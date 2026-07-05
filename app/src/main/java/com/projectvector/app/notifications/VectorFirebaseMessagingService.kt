package com.projectvector.app.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.projectvector.app.MainActivity
import com.projectvector.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.projectvector.app.bridge.NotificationRoutePayload
import com.projectvector.app.bridge.ReactCallbackSender
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VectorFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var callbackSender: ReactCallbackSender

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
}
