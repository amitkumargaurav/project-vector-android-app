package com.projectvector.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.projectvector.app.notifications.GoalNotificationScheduler
import com.projectvector.app.core.security.TokenStore
import com.projectvector.app.ui.theme.VectorTheme
import com.projectvector.app.ui.theme.textUnitResource
import com.projectvector.app.webview.BackPressController
import com.projectvector.app.webview.BackPressMode
import com.projectvector.app.webview.WebViewConfig
import com.projectvector.app.webview.WebStateStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
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
    @Inject lateinit var goalNotificationScheduler: GoalNotificationScheduler
    @Inject lateinit var webViewConfig: WebViewConfig
    @Inject lateinit var webStateStore: WebStateStore
    @Inject lateinit var backPressController: BackPressController

    private val viewModel: MainViewModel by viewModels()
    private var webView: WebView? = null
    private var restoredWebViewState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        systemSplash.setOnExitAnimationListener { splashView -> splashView.remove() }
        enableEdgeToEdge()
        restoredWebViewState = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE) ?: webStateStore.restoreWebViewState()
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE,
        )
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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

    override fun onStop() {
        saveCurrentWebViewState()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveCurrentWebViewState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        webView?.let(callbackSender::detach)
        webView = null
        connectivityObserver.stop()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == GoalNotificationScheduler.ACTION_GOAL_REMINDER_TAPPED) {
            intent.getStringExtra(GoalNotificationScheduler.EXTRA_GOAL_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let(goalNotificationScheduler::markNotificationTapped)
        }
        val payload = deepLinkParser.parse(intent?.data) ?: intent?.extras?.toNotificationPayload()
        if (payload != null) callbackSender.onNotificationClicked(payload)
    }

    @Composable
    private fun VectorAppShell() {
        val uiState by viewModel.uiState.collectAsState()

        SideEffect {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            val useFullscreen = uiState != MainUiState.Home
            if (useFullscreen) {
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
            val useDarkSystemBarIcons = uiState == MainUiState.Home
            insetsController.isAppearanceLightStatusBars = useDarkSystemBarIcons
            insetsController.isAppearanceLightNavigationBars = useDarkSystemBarIcons
        }

        when (val state = uiState) {
            MainUiState.Splash -> SplashScreenContent()
            is MainUiState.Login -> LoginScreen(
                loading = state.loading,
                error = state.error,
                animateFromSplash = state.animateFromSplash,
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).also { created ->
                        webView = created
                        configureWebView(created, onLoading = { loading = it }, onError = { error = it }, onCanGoBack = { canGoBack = it })
                        callbackSender.attach(created)
                        if (!restoreWebView(created)) {
                            created.loadUrl(webStateStore.getLastTrustedUrl()?.takeIf(webViewConfig::isTrusted) ?: webViewConfig.startUrl)
                        }
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
                title = { Text(stringResource(R.string.notification_pre_permission_title)) },
                text = { Text(stringResource(R.string.notification_pre_permission_message)) },
                confirmButton = {
                    TextButton(onClick = { notificationOnboardingManager.onPrePermissionAccepted(viewModel.viewModelScope) }) {
                        Text(stringResource(R.string.action_continue))
                    }
                },
                dismissButton = {
                    TextButton(onClick = notificationOnboardingManager::onPrePermissionDismissed) {
                        Text(stringResource(R.string.action_not_now))
                    }
                },
            )
            NotificationOnboardingUiState.PermissionDenied -> AlertDialog(
                onDismissRequest = notificationOnboardingManager::dismissSettingsExplanation,
                title = { Text(stringResource(R.string.notification_permission_denied_title)) },
                text = { Text(stringResource(R.string.notification_permission_denied_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        notificationOnboardingManager.dismissSettingsExplanation()
                        openNotificationSettings()
                    }) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                },
                dismissButton = {
                    TextButton(onClick = notificationOnboardingManager::dismissSettingsExplanation) {
                        Text(stringResource(R.string.action_later))
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
                if (webViewConfig.isTrusted(url)) webStateStore.saveLastTrustedUrl(url)
                onLoading(true)
                onCanGoBack(view.canGoBack())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (webViewConfig.isTrusted(url)) webStateStore.saveLastTrustedUrl(url)
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
                if (request.isForMainFrame) onError(error.description?.toString() ?: getString(R.string.webview_load_failed))
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (BuildConfig.DEBUG && webViewConfig.isTrusted(request.url.toString())) {
                    Timber.tag(NETWORK_LOG_TAG).d("WebView %s %s", request.method, request.url)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun restoreWebView(webView: WebView): Boolean {
        val state = restoredWebViewState ?: return false
        restoredWebViewState = null
        return runCatching {
            webView.restoreState(state) != null
        }.onFailure {
            Timber.w(it, "Unable to restore WebView state")
        }.getOrDefault(false)
    }

    private fun saveCurrentWebViewState(outState: Bundle? = null) {
        val currentWebView = webView ?: return
        currentWebView.url?.takeIf(webViewConfig::isTrusted)?.let(webStateStore::saveLastTrustedUrl)
        currentWebView.evaluateJavascript(
            "window.VectorMobileBridge && window.VectorMobileBridge.saveCurrentState && window.VectorMobileBridge.saveCurrentState();",
            null,
        )
        val webBundle = Bundle()
        val hasState = runCatching { currentWebView.saveState(webBundle) != null }
            .onFailure { Timber.w(it, "Unable to save WebView state") }
            .getOrDefault(false)
        if (!hasState) return
        outState?.putBundle(KEY_WEBVIEW_STATE, webBundle)
        webStateStore.saveWebViewState(webBundle)
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
        const val KEY_WEBVIEW_STATE = "webview_state"
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val tokenStore: TokenStore,
    private val googleAuthManager: GoogleAuthManager,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Splash)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        val destination = if (tokenStore.hasSession()) {
            MainUiState.Home
        } else {
            MainUiState.Login(animateFromSplash = true)
        }
        viewModelScope.launch {
            delay(SPLASH_DURATION_MS)
            if (_uiState.value == MainUiState.Splash) _uiState.value = destination
        }
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
                            onFailure = { _uiState.value = MainUiState.Login(error = it.message ?: context.getString(R.string.login_failed)) },
                        )
                    },
                    onFailure = { _uiState.value = MainUiState.Login(error = it.message ?: context.getString(R.string.login_google_failed)) },
                )
        }
    }

}

sealed interface MainUiState {
    data object Splash : MainUiState
    data class Login(
        val loading: Boolean = false,
        val error: String? = null,
        val animateFromSplash: Boolean = false,
    ) : MainUiState
    data object Home : MainUiState
}

@Composable
private fun SplashScreenContent() {
    var progressTarget by remember { mutableFloatStateOf(0f) }
    var revealTarget by remember { mutableFloatStateOf(0f) }
    val progress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(durationMillis = SPLASH_DURATION_MS.toInt(), easing = LinearEasing),
        label = "Splash progress",
    )
    val revealProgress by animateFloatAsState(
        targetValue = revealTarget,
        animationSpec = tween(durationMillis = SPLASH_REVEAL_DURATION_MS, easing = FastOutSlowInEasing),
        label = "Splash reveal",
    )
    LaunchedEffect(Unit) {
        progressTarget = 1f
        delay(SPLASH_REVEAL_DELAY_MS)
        revealTarget = 1f
    }

    BrandedBackground(backgroundAlpha = revealProgress) {
        val splashLogoSize = dimensionResource(R.dimen.vector_splash_icon_size)
        val splashLogoTop = (maxHeight - splashLogoSize) / 2
        val splashCornerRadius = dimensionResource(R.dimen.splash_icon_corner_radius)
        val wordmarkSpacing = dimensionResource(R.dimen.splash_wordmark_spacing)
        val wordmarkTextSize = textUnitResource(R.dimen.splash_wordmark_text_size)
        val taglineSpacing = dimensionResource(R.dimen.splash_tagline_spacing)
        val taglineTextSize = textUnitResource(R.dimen.splash_tagline_text_size)

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = splashLogoTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VectorAppMark(size = splashLogoSize, cornerRadius = splashCornerRadius)
            Spacer(Modifier.height(wordmarkSpacing))
            VectorWordmark(
                fontSize = wordmarkTextSize,
                modifier = Modifier.alpha(revealProgress),
            )
            Spacer(Modifier.height(taglineSpacing))
            Text(
                text = stringResource(R.string.splash_tagline),
                modifier = Modifier.alpha(revealProgress),
                color = colorResource(R.color.vector_tagline),
                fontSize = taglineTextSize,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }

        SplashProgressBar(
            progress = progress,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = dimensionResource(R.dimen.splash_progress_bottom_padding))
                .alpha(revealProgress),
        )
    }
}

@Composable
private fun LoginScreen(
    loading: Boolean,
    error: String?,
    animateFromSplash: Boolean,
    onGoogleLogin: () -> Unit,
) {
    val transitionProgress = remember(animateFromSplash) {
        Animatable(if (animateFromSplash) 0f else 1f)
    }
    LaunchedEffect(transitionProgress) {
        if (animateFromSplash) {
            transitionProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = LOGIN_TRANSITION_DURATION_MS,
                    easing = LinearEasing,
                ),
            )
        }
    }

    val progress = transitionProgress.value
    val splashLogoSize = dimensionResource(R.dimen.vector_splash_icon_size)
    val loginLogoSize = dimensionResource(R.dimen.login_logo_size)
    val logoSize = splashLogoSize + ((loginLogoSize - splashLogoSize) * progress)
    val splashCornerRadius = dimensionResource(R.dimen.splash_icon_corner_radius)
    val loginCornerRadius = dimensionResource(R.dimen.login_logo_corner_radius)
    val logoCornerRadius = splashCornerRadius + ((loginCornerRadius - splashCornerRadius) * progress)
    val splashWordmarkSpacing = dimensionResource(R.dimen.splash_wordmark_spacing)
    val loginWordmarkSpacing = dimensionResource(R.dimen.login_wordmark_spacing)
    val wordmarkSpacing = splashWordmarkSpacing + ((loginWordmarkSpacing - splashWordmarkSpacing) * progress)
    val splashWordmarkSize = textUnitResource(R.dimen.splash_wordmark_text_size)
    val loginWordmarkSize = textUnitResource(R.dimen.login_wordmark_text_size)
    val wordmarkSize = (splashWordmarkSize.value + ((loginWordmarkSize.value - splashWordmarkSize.value) * progress)).sp
    val splashAlpha = 1f - progress
    val loginContentAlpha = ((progress - 0.20f) / 0.80f).coerceIn(0f, 1f)

    BrandedBackground {
        val splashLogoTop = (maxHeight - splashLogoSize) / 2
        val loginLogoTop = dimensionResource(R.dimen.login_logo_top_padding)
        val logoTopPadding = splashLogoTop + ((loginLogoTop - splashLogoTop) * progress)

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = logoTopPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VectorAppMark(size = logoSize, cornerRadius = logoCornerRadius)
            Spacer(Modifier.height(wordmarkSpacing))
            VectorWordmark(fontSize = wordmarkSize)
            if (splashAlpha > 0f) {
                Spacer(Modifier.height(dimensionResource(R.dimen.splash_tagline_spacing)))
                Text(
                    text = stringResource(R.string.splash_tagline),
                    modifier = Modifier.alpha(splashAlpha),
                    color = colorResource(R.color.vector_tagline),
                    fontSize = textUnitResource(R.dimen.splash_tagline_text_size),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .alpha(loginContentAlpha)
                .padding(
                    horizontal = dimensionResource(R.dimen.login_content_horizontal_padding),
                    vertical = dimensionResource(R.dimen.login_content_vertical_padding),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.login_welcome),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = textUnitResource(R.dimen.login_welcome_text_size),
                lineHeight = textUnitResource(R.dimen.login_welcome_line_height),
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.SansSerif,
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.login_welcome_subtitle_spacing)))
            Text(
                text = stringResource(R.string.login_subtitle),
                color = colorResource(R.color.vector_body_blue),
                fontSize = textUnitResource(R.dimen.login_subtitle_text_size),
                lineHeight = textUnitResource(R.dimen.login_subtitle_line_height),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(dimensionResource(R.dimen.login_button_top_spacing)))
            Button(
                onClick = onGoogleLogin,
                enabled = !loading && transitionProgress.value == 1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dimensionResource(R.dimen.login_button_height)),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(dimensionResource(R.dimen.pill_corner_radius)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = dimensionResource(R.dimen.button_elevation),
                    pressedElevation = dimensionResource(R.dimen.button_pressed_elevation),
                    disabledElevation = dimensionResource(R.dimen.button_disabled_elevation),
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(dimensionResource(R.dimen.login_progress_size)),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = dimensionResource(R.dimen.login_progress_stroke_width),
                        )
                    } else {
                        GoogleGLogo(dimensionResource(R.dimen.google_logo_size))
                    }
                    Spacer(Modifier.width(dimensionResource(R.dimen.login_button_icon_spacing)))
                    Text(
                        text = stringResource(if (loading) R.string.login_signing_in else R.string.login_continue_with_google),
                        fontSize = textUnitResource(R.dimen.login_button_text_size),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(dimensionResource(R.dimen.login_help_top_spacing)))
            Text(
                text = stringResource(R.string.login_google_help),
                color = colorResource(R.color.vector_body_blue),
                fontSize = textUnitResource(R.dimen.login_help_text_size),
                textAlign = TextAlign.Center,
            )
            error?.let {
                Spacer(Modifier.height(dimensionResource(R.dimen.login_error_top_spacing)))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = textUnitResource(R.dimen.login_error_text_size),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (splashAlpha > 0f) {
            SplashProgressBar(
                progress = 1f,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = dimensionResource(R.dimen.splash_progress_bottom_padding))
                    .alpha(splashAlpha),
            )
        }
    }
}

@Composable
private fun SplashProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(dimensionResource(R.dimen.splash_progress_width))
            .height(dimensionResource(R.dimen.splash_progress_height))
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(dimensionResource(R.dimen.splash_progress_corner_radius)))
            .background(colorResource(R.color.splash_progress_track)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(dimensionResource(R.dimen.splash_progress_corner_radius)))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

private const val SPLASH_DURATION_MS = 2_300L
private const val SPLASH_REVEAL_DELAY_MS = 120L
private const val SPLASH_REVEAL_DURATION_MS = 1_200
private const val LOGIN_TRANSITION_DURATION_MS = 500

@Composable
private fun BrandedBackground(
    backgroundAlpha: Float = 1f,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary),
    ) {
        Image(
            painter = painterResource(R.drawable.winding_path_to_the_summit),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(backgroundAlpha),
            contentScale = ContentScale.Crop,
        )
        content()
    }
}

@Composable
private fun VectorAppMark(size: androidx.compose.ui.unit.Dp, cornerRadius: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_splash_logo),
            contentDescription = stringResource(R.string.vector_logo_content_description),
            modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimary),
        )
    }
}

@Composable
private fun VectorWordmark(
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.vector_wordmark),
        modifier = modifier,
        color = colorResource(R.color.vector_wordmark),
        fontSize = fontSize,
        lineHeight = fontSize,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = FontFamily.SansSerif,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun GoogleGLogo(size: androidx.compose.ui.unit.Dp) {
    Image(
        painter = painterResource(R.drawable.ic_google_g),
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.background, colors.primaryContainer))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(dimensionResource(R.dimen.error_content_padding)).fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.project_vector), fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            Spacer(Modifier.height(dimensionResource(R.dimen.error_title_spacing)))
            Text(message, textAlign = TextAlign.Center, color = colors.onBackground)
            Spacer(Modifier.height(dimensionResource(R.dimen.error_action_spacing)))
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
