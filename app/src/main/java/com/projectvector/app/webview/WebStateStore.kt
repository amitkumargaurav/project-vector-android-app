package com.projectvector.app.webview

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vector_web_state", Context.MODE_PRIVATE)
    private val webViewStateFile = File(appContext.filesDir, "vector_webview_state.bin")

    fun saveLastTrustedUrl(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        prefs.edit()
            .putString(KEY_LAST_URL, url)
            .putLong(KEY_LAST_URL_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getLastTrustedUrl(): String? = prefs.getString(KEY_LAST_URL, null)?.takeIf { it.isNotBlank() }

    fun saveAppState(payload: JSONObject) {
        prefs.edit()
            .putString(KEY_APP_STATE_JSON, payload.toString())
            .putLong(KEY_APP_STATE_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun getAppState(): JSONObject? {
        val raw = prefs.getString(KEY_APP_STATE_JSON, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(raw) }
            .onFailure { Timber.w(it, "Unable to read persisted app state") }
            .getOrNull()
    }

    fun getAppStateUpdatedAt(): Long = prefs.getLong(KEY_APP_STATE_UPDATED_AT, 0L)

    fun clearAppState() {
        prefs.edit()
            .remove(KEY_APP_STATE_JSON)
            .remove(KEY_APP_STATE_UPDATED_AT)
            .apply()
    }

    fun saveWebViewState(state: Bundle): Boolean {
        val parcel = Parcel.obtain()
        return runCatching {
            parcel.writeBundle(state)
            webViewStateFile.writeBytes(parcel.marshall())
            true
        }.onFailure {
            Timber.w(it, "Unable to persist WebView state")
        }.getOrDefault(false).also {
            parcel.recycle()
        }
    }

    fun restoreWebViewState(): Bundle? {
        if (!webViewStateFile.exists()) return null
        val bytes = runCatching { webViewStateFile.readBytes() }
            .onFailure { Timber.w(it, "Unable to read persisted WebView state") }
            .getOrNull()
            ?: return null
        val parcel = Parcel.obtain()
        return runCatching {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readBundle(javaClass.classLoader)
        }.onFailure {
            Timber.w(it, "Unable to restore persisted WebView state")
        }.getOrNull().also {
            parcel.recycle()
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching {
            if (webViewStateFile.exists()) webViewStateFile.delete()
        }.onFailure {
            Timber.w(it, "Unable to delete persisted WebView state")
        }
    }

    private companion object {
        const val KEY_LAST_URL = "last_url"
        const val KEY_LAST_URL_UPDATED_AT = "last_url_updated_at"
        const val KEY_APP_STATE_JSON = "app_state_json"
        const val KEY_APP_STATE_UPDATED_AT = "app_state_updated_at"
    }
}
