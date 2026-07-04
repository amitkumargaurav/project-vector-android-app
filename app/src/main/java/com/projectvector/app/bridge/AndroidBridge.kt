package com.projectvector.app.bridge

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class AndroidBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val dispatcher: BridgeDispatcher,
) {
    @JavascriptInterface
    fun postMessage(rawMessage: String) {
        scope.launch {
            val parsed = runCatching { JSONObject(rawMessage) }.getOrNull()
            val id = parsed?.optString("id").orEmpty()
            val method = parsed?.optString("method").orEmpty()
            val payload = parsed?.optJSONObject("payload")
            val result = if (id.isBlank() || method.isBlank()) {
                bridgeError("Invalid bridge message")
            } else {
                dispatcher.dispatch(activity, method, payload)
            }
            resolve(id, result)
        }
    }

    private fun resolve(id: String, result: JSONObject) {
        val script = "window.__VectorMobileBridgeResolve && window.__VectorMobileBridgeResolve(${JSONObject.quote(id)}, $result);"
        webView.post { webView.evaluateJavascript(script, null) }
    }
}
