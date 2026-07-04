package com.projectvector.app.notifications

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
        // TODO: Display a system notification with a PendingIntent for background delivery.
        // This currently forwards route data only when the app process can receive the FCM callback.
        val route = message.data["route"] ?: return
        callbackSender.onNotificationClicked(
            NotificationRoutePayload(
                route = route,
                date = message.data["date"],
                taskId = message.data["taskId"],
                goalId = message.data["goalId"],
            )
        )
    }
}
