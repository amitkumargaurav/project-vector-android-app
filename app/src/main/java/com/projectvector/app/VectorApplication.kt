package com.projectvector.app

import android.app.Application
import com.projectvector.app.notifications.GoalNotificationScheduler
import com.projectvector.app.notifications.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VectorApplication : Application() {
    @Inject lateinit var notificationChannels: NotificationChannels
    @Inject lateinit var goalNotificationScheduler: GoalNotificationScheduler

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        notificationChannels.create()
        goalNotificationScheduler.restorePersistedAlarms()
    }
}
