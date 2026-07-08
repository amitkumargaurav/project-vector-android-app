package com.projectvector.app.notifications

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri

object NotificationSound {
    private const val RAW_SOUND_RESOURCE_NAME = "notification_ping"

    fun uri(context: Context): Uri = Uri.parse("android.resource://${context.packageName}/raw/$RAW_SOUND_RESOURCE_NAME")

    fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
