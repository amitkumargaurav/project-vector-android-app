package com.projectvector.app.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSessionCleaner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun clearPersistentData() {
        runOnMain {
            clearPersistentDataOnMain()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun clearCurrentWebView(webView: WebView) {
        runOnMain {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.clearCache(true)
                webView.clearFormData()
                webView.clearSslPreferences()
            }.onFailure { Timber.w(it, "Unable to clear current WebView") }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    private fun clearPersistentDataOnMain() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }.onFailure { Timber.w(it, "Unable to clear WebView cookies") }

        runCatching {
            WebStorage.getInstance().deleteAllData()
        }.onFailure { Timber.w(it, "Unable to clear WebView storage") }

        runCatching {
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        }.onFailure { Timber.w(it, "Unable to clear WebView HTTP auth data") }

        @Suppress("DEPRECATION")
        runCatching {
            WebViewDatabase.getInstance(context).clearFormData()
        }.onFailure { Timber.w(it, "Unable to clear WebView form data") }
    }
}
