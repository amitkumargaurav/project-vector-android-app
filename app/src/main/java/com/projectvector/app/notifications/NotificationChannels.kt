package com.projectvector.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.projectvector.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationChannels @Inject constructor(@ApplicationContext private val context: Context) {
    fun create() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(REMINDERS, context.getString(R.string.notification_channel_reminders_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notification_channel_reminders_description)
                setSound(NotificationSound.uri(context), NotificationSound.audioAttributes())
            }
        )
    }

    companion object {
        const val REMINDERS = "vector_reminders_high"
    }
}
