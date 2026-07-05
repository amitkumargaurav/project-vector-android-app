package com.projectvector.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectvector.app.bridge.AndroidBridge
import com.projectvector.app.bridge.BridgeDispatcher
import com.projectvector.app.bridge.NotificationRoutePayload
import com.projectvector.app.bridge.ReactCallbackSender
import com.projectvector.app.bridge.VectorBridgeInstaller
import com.projectvector.app.deeplink.DeepLinkParser
import com.projectvector.app.lifecycle.AppLifecycleObserver
import com.projectvector.app.lifecycle.ConnectivityObserver
import com.projectvector.app.notifications.NotificationPermissionManager
import com.projectvector.app.ui.theme.VectorColors
import com.projectvector.app.ui.theme.VectorTheme
import com.projectvector.app.webview.BackPressController
import com.projectvector.app.webview.BackPressMode
import com.projectvector.app.webview.WebViewConfig
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var bridgeDispatcher: BridgeDispatcher
    @Inject lateinit var callbackSender: ReactCallbackSender
    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var lifecycleObserver: AppLifecycleObserver
    @Inject lateinit var deepLinkParser: DeepLinkParser
    @Inject lateinit var permissionManager: NotificationPermissionManager
    @Inject lateinit var webViewConfig: WebViewConfig
    @Inject lateinit var backPressController: BackPressController

    private val viewModel: MainViewModel by viewModels()
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        connectivityObserver.start()
        handleIntent(intent)

        setContent {
            VectorTheme {
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    permissionManager.onPermissionResult(granted)
                }
                LaunchedEffect(Unit) { permissionManager.bindLauncher(permissionLauncher) }
                VectorWebShell()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        webView?.let(callbackSender::detach)
        webView = null
        connectivityObserver.stop()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val payload = deepLinkParser.parse(intent?.data) ?: intent?.extras?.toNotificationPayload()
        if (payload != null) callbackSender.onNotificationClicked(payload)
    }

    @Composable
    private fun VectorWebShell() {
        val backMode by backPressController.mode.collectAsState()
        var canGoBack by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }

        BackHandler(enabled = backMode != BackPressMode.DISABLED) {
            when {
                backMode == BackPressMode.DISABLED -> Unit
                canGoBack -> webView?.goBack()
                backMode == BackPressMode.CONFIRM_EXIT -> moveTaskToBack(true)
                else -> moveTaskToBack(true)
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(VectorColors.Vector25)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).also { created ->
                        webView = created
                        configureWebView(created, onLoading = { loading = it }, onError = { error = it }, onCanGoBack = { canGoBack = it })
                        callbackSender.attach(created)
                        created.loadUrl(webViewConfig.startUrl)
                    }
                },
            )
            if (loading) LoadingOverlay()
            error?.let { message ->
                ErrorOverlay(message = message, onRetry = {
                    error = null
                    loading = true
                    webView?.reload() ?: webView?.loadUrl(webViewConfig.startUrl)
                })
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                webView?.let(callbackSender::detach)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(
        webView: WebView,
        onLoading: (Boolean) -> Unit,
        onError: (String) -> Unit,
        onCanGoBack: (Boolean) -> Unit,
    ) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        webView.addJavascriptInterface(AndroidBridge(this, webView, viewModel.viewModelScope, bridgeDispatcher), VectorBridgeInstaller.INTERFACE_NAME)
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (webViewConfig.isTrusted(url)) false else openExternal(url)
            }

            @Deprecated("Deprecated in Android API")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return if (webViewConfig.isTrusted(url)) false else openExternal(url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                onLoading(true)
                onCanGoBack(view.canGoBack())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(VectorBridgeInstaller.script, null)
                callbackSender.markReady()
                onLoading(false)
                onCanGoBack(view.canGoBack())
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onError(error.description?.toString() ?: "Unable to load Project Vector")
            }
        }
    }

    private fun openExternal(url: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: ActivityNotFoundException) {
            true
        }
    }

    private fun Bundle.toNotificationPayload(): NotificationRoutePayload? {
        val route = getString("route") ?: return null
        return NotificationRoutePayload(route = route, date = getString("date"), taskId = getString("taskId"), goalId = getString("goalId"))
    }
}

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel()

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize().background(VectorColors.Vector25),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = VectorColors.Vector500)
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VectorColors.Vector25, VectorColors.Vector100))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(28.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Project Vector", fontWeight = FontWeight.Bold, color = VectorColors.Vector800)
            Spacer(Modifier.height(12.dp))
            Text(message, textAlign = TextAlign.Center, color = VectorColors.Vector900)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
