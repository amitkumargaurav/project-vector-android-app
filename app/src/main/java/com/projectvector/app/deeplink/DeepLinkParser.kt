package com.projectvector.app.deeplink

import android.net.Uri
import com.projectvector.app.bridge.NotificationRoutePayload
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepLinkParser @Inject constructor() {
    fun parse(uri: Uri?): NotificationRoutePayload? {
        if (uri == null || uri.scheme != "vector") return null
        val route = buildString {
            append('/')
            append(uri.host.orEmpty())
            if (!uri.path.isNullOrBlank()) append(uri.path)
        }.ifBlank { "/" }
        return NotificationRoutePayload(
            route = route,
            date = uri.getQueryParameter("date"),
            taskId = uri.getQueryParameter("taskId"),
            goalId = uri.getQueryParameter("goalId"),
        )
    }
}
