package com.projectvector.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectvector.app.auth.AuthRepository
import com.projectvector.app.auth.GoogleAuthManager
import com.projectvector.app.bridge.AndroidBridge
import com.projectvector.app.bridge.BridgeDispatcher
import com.projectvector.app.bridge.NotificationRoutePayload
import com.projectvector.app.bridge.ReactCallbackSender
import com.projectvector.app.bridge.VectorBridgeInstaller
import com.projectvector.app.deeplink.DeepLinkParser
import com.projectvector.app.lifecycle.AppLifecycleObserver
import com.projectvector.app.lifecycle.ConnectivityObserver
import com.projectvector.app.notifications.NotificationOnboardingManager
import com.projectvector.app.notifications.NotificationOnboardingUiState
import com.projectvector.app.notifications.NotificationPermissionManager
import com.projectvector.app.core.security.TokenStore
import com.projectvector.app.ui.theme.VectorColors
import com.projectvector.app.ui.theme.VectorTheme
import com.projectvector.app.webview.BackPressController
import com.projectvector.app.webview.BackPressMode
import com.projectvector.app.webview.WebViewConfig
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var bridgeDispatcher: BridgeDispatcher
    @Inject lateinit var callbackSender: ReactCallbackSender
    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var lifecycleObserver: AppLifecycleObserver
    @Inject lateinit var deepLinkParser: DeepLinkParser
    @Inject lateinit var permissionManager: NotificationPermissionManager
    @Inject lateinit var notificationOnboardingManager: NotificationOnboardingManager
    @Inject lateinit var webViewConfig: WebViewConfig
    @Inject lateinit var backPressController: BackPressController

    private val viewModel: MainViewModel by viewModels()
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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
                VectorAppShell()
                NotificationOnboardingDialogs()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        notificationOnboardingManager.onAppForegrounded(viewModel.viewModelScope)
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
    private fun VectorAppShell() {
        val uiState by viewModel.uiState.collectAsState()

        when (val state = uiState) {
            MainUiState.Splash -> SplashScreenContent()
            is MainUiState.Login -> LoginScreen(
                loading = state.loading,
                error = state.error,
                onGoogleLogin = { viewModel.loginWithGoogle(this) },
            )
            MainUiState.Home -> {
                LaunchedEffect(Unit) { notificationOnboardingManager.onLoggedInAppOpened(viewModel.viewModelScope) }
                VectorWebShell()
            }
        }
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


    @Composable
    private fun NotificationOnboardingDialogs() {
        val state by notificationOnboardingManager.uiState.collectAsState()
        when (state) {
            NotificationOnboardingUiState.Idle -> Unit
            NotificationOnboardingUiState.PrePermission -> AlertDialog(
                onDismissRequest = notificationOnboardingManager::onPrePermissionDismissed,
                title = { Text("Turn on Vector reminders") },
                text = { Text("Notifications help Vector send reminders, plan reviews, and goal nudges at the right time.") },
                confirmButton = {
                    TextButton(onClick = { notificationOnboardingManager.onPrePermissionAccepted(viewModel.viewModelScope) }) {
                        Text("Continue")
                    }
                },
                dismissButton = {
                    TextButton(onClick = notificationOnboardingManager::onPrePermissionDismissed) {
                        Text("Not now")
                    }
                },
            )
            NotificationOnboardingUiState.PermissionDenied -> AlertDialog(
                onDismissRequest = notificationOnboardingManager::dismissSettingsExplanation,
                title = { Text("Notifications are off") },
                text = { Text("Vector works best with notifications for reminders, reviews, and goal nudges. You can turn them on in system settings.") },
                confirmButton = {
                    TextButton(onClick = {
                        notificationOnboardingManager.dismissSettingsExplanation()
                        openNotificationSettings()
                    }) {
                        Text("Open settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = notificationOnboardingManager::dismissSettingsExplanation) {
                        Text("Later")
                    }
                },
            )
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
                if (BuildConfig.DEBUG) {
                    Timber.tag(NETWORK_LOG_TAG).w(
                        "WebView error %s %s: %s",
                        request.method,
                        request.url,
                        error.description,
                    )
                }
                if (request.isForMainFrame) onError(error.description?.toString() ?: "Unable to load Project Vector")
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (BuildConfig.DEBUG && webViewConfig.isTrusted(request.url.toString())) {
                    Timber.tag(NETWORK_LOG_TAG).d("WebView %s %s", request.method, request.url)
                }
                return super.shouldInterceptRequest(view, request)
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

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun Bundle.toNotificationPayload(): NotificationRoutePayload? {
        val route = getString("route") ?: return null
        return NotificationRoutePayload(route = route, date = getString("date"), taskId = getString("taskId"), goalId = getString("goalId"))
    }

    private companion object {
        const val NETWORK_LOG_TAG = "Network"
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val googleAuthManager: GoogleAuthManager,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Splash)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        _uiState.value = if (tokenStore.hasSession()) MainUiState.Home else MainUiState.Login()
        viewModelScope.launch {
            authRepository.sessionInvalidated.collect {
                _uiState.value = MainUiState.Login(error = it.message)
            }
        }
    }

    fun loginWithGoogle(activityContext: android.content.Context) {
        if ((_uiState.value as? MainUiState.Login)?.loading == true) return
        _uiState.value = MainUiState.Login(loading = true)
        viewModelScope.launch {
            googleAuthManager.requestGoogleIdToken(activityContext)
                .fold(
                    onSuccess = { idToken ->
                        authRepository.exchangeGoogleIdToken(idToken).fold(
                            onSuccess = { _uiState.value = MainUiState.Home },
                            onFailure = { _uiState.value = MainUiState.Login(error = it.message ?: "Unable to sign in") },
                        )
                    },
                    onFailure = { _uiState.value = MainUiState.Login(error = it.message ?: "Unable to sign in with Google") },
                )
        }
    }
}

sealed interface MainUiState {
    data object Splash : MainUiState
    data class Login(val loading: Boolean = false, val error: String? = null) : MainUiState
    data object Home : MainUiState
}

@Composable
private fun SplashScreenContent() {
    BrandedAuthSurface {
        CircularProgressIndicator(color = VectorColors.Vector500)
    }
}

@Composable
private fun LoginScreen(loading: Boolean, error: String?, onGoogleLogin: () -> Unit) {
    BrandedAuthSurface {
        Text("Project Vector", fontWeight = FontWeight.Bold, fontSize = 30.sp, color = VectorColors.Vector800)
        Spacer(Modifier.height(8.dp))
        Text("Sign in to continue", color = VectorColors.Vector900, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onGoogleLogin,
            enabled = !loading,
            colors = ButtonDefaults.buttonColors(containerColor = VectorColors.Vector500),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (loading) {
                    CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (loading) "Signing in…" else "Continue with Google")
            }
        }
        error?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, color = VectorColors.Danger, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BrandedAuthSurface(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VectorColors.Vector25, VectorColors.Vector100))),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.padding(28.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)),
            color = androidx.compose.ui.graphics.Color.White,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

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
