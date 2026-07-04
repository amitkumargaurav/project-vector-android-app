package com.projectvector.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationChannels @Inject constructor(@ApplicationContext private val context: Context) {
    fun create() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(REMINDERS, "Vector reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Project Vector local reminders and notification routing"
            }
        )
    }

    companion object {
        const val REMINDERS = "vector_reminders"
    }
}
