package com.projectvector.app.browser

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.projectvector.app.bridge.isSupportedHttpUrl
import com.projectvector.app.ui.theme.VectorColors
import com.projectvector.app.ui.theme.VectorTheme
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InAppBrowserActivity : ComponentActivity() {
    private val copiedTextCollector = CopiedTextCollector()
    private var requestId: String? = null
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (!isSupportedHttpUrl(initialUrl)) {
            finish()
            return
        }
        installClipboardObserver()
        setContent {
            VectorTheme {
                InAppBrowserScreen(initialUrl = initialUrl, onClose = ::finish)
            }
        }
    }

    override fun onDestroy() {
        removeClipboardObserver()
        completeResult()
        super.onDestroy()
    }

    private fun installClipboardObserver() {
        val manager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val listener = ClipboardManager.OnPrimaryClipChangedListener { capturePrimaryClip(manager) }
        clipboardManager = manager
        clipboardListener = listener
        manager.addPrimaryClipChangedListener(listener)
    }

    private fun removeClipboardObserver() {
        val manager = clipboardManager
        val listener = clipboardListener
        if (manager != null && listener != null) {
            manager.removePrimaryClipChangedListener(listener)
        }
        clipboardManager = null
        clipboardListener = null
    }

    private fun capturePrimaryClip(manager: ClipboardManager) {
        val clip = manager.primaryClip ?: return
        for (index in 0 until clip.itemCount) {
            clip.getItemAt(index)
                ?.coerceToText(this)
                ?.let(copiedTextCollector::add)
        }
    }

    private fun completeResult() {
        if (completed) return
        completed = true
        requestId?.let { completeRequest(it, copiedTextCollector.toList()) }
    }

    companion object {
        private const val EXTRA_URL = "com.projectvector.app.browser.EXTRA_URL"
        private const val EXTRA_REQUEST_ID = "com.projectvector.app.browser.EXTRA_REQUEST_ID"
        private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<List<String>>>()

        fun createRequestId(): String =
            UUID.randomUUID().toString().also { pendingResults[it] = CompletableDeferred() }

        fun createIntent(context: Context, url: String, requestId: String): Intent =
            Intent(context, InAppBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_REQUEST_ID, requestId)

        suspend fun awaitResult(requestId: String): List<String> =
            try {
                pendingResults[requestId]?.await().orEmpty()
            } finally {
                pendingResults.remove(requestId)
            }

        fun cancelRequest(requestId: String) {
            pendingResults.remove(requestId)?.cancel()
        }

        private fun completeRequest(requestId: String, copiedTexts: List<String>) {
            pendingResults.remove(requestId)?.complete(copiedTexts)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppBrowserScreen(initialUrl: String, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(initialUrl) }
    var currentUrl by remember { mutableStateOf(initialUrl) }

    BackHandler {
        val currentWebView = webView
        if (currentWebView?.canGoBack() == true) {
            currentWebView.goBack()
        } else {
            onClose()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(VectorColors.Vector25)) {
        BrowserTopBar(
            title = title,
            subtitle = currentUrl,
            canGoBack = canGoBack,
            onBack = { webView?.goBack() },
            onClose = onClose,
        )
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = VectorColors.Vector500)
        } else {
            Spacer(Modifier.height(4.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).also { created ->
                    webView = created
                    created.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    created.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        mediaPlaybackRequiresUserGesture = true
                    }
                    created.webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, receivedTitle: String?) {
                            title = receivedTitle?.takeIf { it.isNotBlank() } ?: view.url.orEmpty()
                        }
                    }
                    created.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            return !isSupportedHttpUrl(url)
                        }

                        @Deprecated("Deprecated in Android API")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                            return !isSupportedHttpUrl(url)
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            loading = true
                            currentUrl = url.orEmpty()
                            canGoBack = view.canGoBack()
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            currentUrl = url.orEmpty()
                            title = view.title?.takeIf { it.isNotBlank() } ?: currentUrl
                            canGoBack = view.canGoBack()
                        }
                    }
                    created.loadUrl(initialUrl)
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }
}

@Composable
private fun BrowserTopBar(
    title: String,
    subtitle: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VectorColors.Vector25)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, enabled = canGoBack) {
            Text("Back")
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { subtitle },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = VectorColors.Vector900,
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = VectorColors.Vector700,
            )
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onClose) {
            Text("Close")
        }
    }
}
