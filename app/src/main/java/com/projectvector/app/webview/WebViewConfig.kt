package com.projectvector.app.webview

import android.net.Uri
import com.projectvector.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebViewConfig @Inject constructor() {
    val startUrl: String = BuildConfig.VECTOR_WEB_URL
    private val trustedHosts = BuildConfig.VECTOR_TRUSTED_HOSTS.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    private val trustedSchemes = setOfNotNull("https", Uri.parse(startUrl).scheme?.lowercase())

    fun isTrusted(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return false
        return scheme in trustedSchemes && trustedHosts.any { host == it || host.endsWith(".$it") }
    }
}
