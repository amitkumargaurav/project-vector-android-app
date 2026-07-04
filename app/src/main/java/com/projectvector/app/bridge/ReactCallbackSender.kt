package com.projectvector.app.bridge

import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReactCallbackSender @Inject constructor() {
    private val pending = ConcurrentLinkedQueue<String>()
    private var webView: WebView? = null
    private var ready = false

    fun attach(webView: WebView) {
        this.webView = webView
        flushIfReady()
    }

    fun markReady() {
        ready = true
        flushIfReady()
    }

    fun detach(webView: WebView) {
        if (this.webView === webView) this.webView = null
    }

    fun onNotificationClicked(payload: NotificationRoutePayload) = send("onNotificationClicked", JSONObject().apply {
        put("route", payload.route)
        payload.date?.let { put("date", it) }
        payload.taskId?.let { put("taskId", it) }
        payload.goalId?.let { put("goalId", it) }
    })

    fun onAppForeground() = send("onAppForeground", null)
    fun onAppBackground() = send("onAppBackground", null)
    fun onConnectivityChanged(online: Boolean, connectionType: String?) = send("onConnectivityChanged", JSONObject().apply {
        put("online", online)
        connectionType?.let { put("connectionType", it) }
    })
    fun onPaymentSuccess(transactionId: String? = null, plan: String? = null) = send("onPaymentSuccess", JSONObject().apply {
        transactionId?.let { put("transactionId", it) }
        plan?.let { put("plan", it) }
    })
    fun onPaymentFailed(reason: String? = null, code: String? = null) = send("onPaymentFailed", JSONObject().apply {
        reason?.let { put("reason", it) }
        code?.let { put("code", it) }
    })
    fun onPermissionResult(permission: String, granted: Boolean) = send("onPermissionResult", JSONObject().put("permission", permission).put("granted", granted))

    private fun send(name: String, payload: JSONObject?) {
        val payloadSource = payload?.toString() ?: "undefined"
        val script = """
            (function() {
              var payload = $payloadSource;
              var namespaced = window.VectorMobileCallbacks && window.VectorMobileCallbacks['$name'];
              var global = window['$name'];
              if (typeof namespaced === 'function') namespaced(payload);
              if (typeof global === 'function') global(payload);
              if (typeof window.dispatchEvent === 'function') {
                window.dispatchEvent(new CustomEvent('VectorMobile:$name', { detail: payload }));
              }
            })();
        """.trimIndent()
        if (!ready || webView == null) {
            pending.add(script)
            return
        }
        evaluate(script)
    }

    private fun flushIfReady() {
        if (!ready || webView == null) return
        while (true) evaluate(pending.poll() ?: break)
    }

    private fun evaluate(script: String) {
        webView?.post { webView?.evaluateJavascript(script, null) }
    }
}
