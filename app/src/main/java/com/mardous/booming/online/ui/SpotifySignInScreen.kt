@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mardous.booming.online.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mardous.booming.R
import com.mardous.booming.online.data.spotify.SpotifyAuth
import com.mardous.booming.online.ui.OnlineToast
import com.mardous.booming.online.viewmodel.SpotifyAuthState
import com.mardous.booming.online.viewmodel.StreamingAuthViewModel

/**
 * Full-screen Spotify sign-in screen designed with Material 3 Expressive.
 * Loads the Spotify login page in a secured WebView and captures sp_dc + sp_key cookies.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifySignInScreen(
    viewModel: StreamingAuthViewModel,
    onSignInSuccess: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember { mutableStateOf(false) }
    var hasSubmittedCookies by remember { mutableStateOf(false) }
    var webProgress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var localErrorMsg by remember { mutableStateOf<String?>(null) }
    var showManualTokenSheet by remember { mutableStateOf(false) }

    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val vmErrorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val spotifyGreen = Color(0xFF1DB954)

    val animatedProgress by animateFloatAsState(
        targetValue = (webProgress / 100f).coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "SpotifyWebProgressAnimation"
    )

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    LaunchedEffect(authState, hasSubmittedCookies) {
        if (!hasSubmittedCookies) return@LaunchedEffect
        when (authState) {
            SpotifyAuthState.CONNECTED -> {
                OnlineToast.show("Spotify connected successfully!")
                onSignInSuccess()
            }
            SpotifyAuthState.ERROR -> {
                isLoading = false
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = spotifyGreen,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(4.dp)) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_spotify_logo),
                                    contentDescription = "Spotify Logo",
                                    tint = Color.Black,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Column {
                            Text("Spotify", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("Secure Direct Sign In", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showManualTokenSheet = true }
                    ) {
                        Icon(Icons.Default.Key, contentDescription = "Manual Token Login", tint = spotifyGreen)
                    }
                    IconButton(
                        onClick = {
                            localErrorMsg = null
                            viewModel.dismissError()
                            webViewRef?.reload()
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload Page")
                    }
                    IconButton(
                        onClick = {
                            localErrorMsg = null
                            hasSubmittedCookies = false
                            isLoading = false
                            viewModel.logout()
                            CookieManager.getInstance().removeAllCookies(null)
                            webViewRef?.loadUrl(SpotifyAuth.LOGIN_URL)
                            OnlineToast.show("Cookies cleared. Refreshing Spotify login...")
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Session")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Animated Web Load Progress Bar
            if (webProgress in 1..99 && !isLoading) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )
            } else if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Surface Container Frame for Integrated Modern WebView
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(android.graphics.Color.parseColor("#121212"))

                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.setSupportMultipleWindows(true)
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                settings.allowContentAccess = true
                                settings.allowFileAccess = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.mediaPlaybackRequiresUserGesture = false

                                // Enable third-party cookies (Crucial for Spotify open.spotify.com vs accounts.spotify.com)
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                // Use standard clean Chrome Mobile User-Agent
                                settings.userAgentString =
                                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        webProgress = newProgress
                                    }

                                    override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                                        request?.grant(request.resources)
                                    }

                                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                        android.util.Log.d("SpotifyWebView", "${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                                        return true
                                    }

                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: android.os.Message?
                                    ): Boolean {
                                        val mainWebView = this@apply
                                        val popupWebView = WebView(ctx).apply {
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    targetView: WebView?,
                                                    request: android.webkit.WebResourceRequest?
                                                ): Boolean {
                                                    val popupUrl = request?.url?.toString() ?: return false
                                                    mainWebView.loadUrl(popupUrl)
                                                    return true
                                                }
                                            }
                                        }
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                                        transport?.webView = popupWebView
                                        resultMsg?.sendToTarget()
                                        return true
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?
                                    ): Boolean {
                                        val target = request?.url?.toString() ?: return false
                                        return !(target.startsWith("http://") || target.startsWith("https://")) // Let WebView load all redirects directly
                                    }

                                    private fun checkAndSubmitCookies(url: String?) {
                                        if (isLoading || hasSubmittedCookies) return
                                        cookieManager.flush()

                                        val currentUrl = url.orEmpty()
                                        val c1 = cookieManager.getCookie("https://open.spotify.com") ?: ""
                                        val c2 = cookieManager.getCookie("https://accounts.spotify.com") ?: ""
                                        val c3 = cookieManager.getCookie("https://spotify.com") ?: ""
                                        val c4 = if (currentUrl.isNotBlank()) cookieManager.getCookie(currentUrl) ?: "" else ""
                                        val allCookies = "$c1; $c2; $c3; $c4"

                                        val spDc = allCookies
                                            .split(";")
                                            .map { it.trim() }
                                            .firstOrNull { it.startsWith("sp_dc=") }
                                            ?.substringAfter("sp_dc=")
                                            ?.trim()
                                            .orEmpty()

                                        val spKey = allCookies
                                            .split(";")
                                            .map { it.trim() }
                                            .firstOrNull { it.startsWith("sp_key=") }
                                            ?.substringAfter("sp_key=")
                                            ?.trim()
                                            .orEmpty()

                                        if (spDc.isNotBlank()) {
                                            isLoading = true
                                            hasSubmittedCookies = true
                                            viewModel.connectWithCookies(spDc, spKey)
                                        }
                                    }

                                    private fun injectAuthInterceptor(view: WebView?) {
                                        val script = """
                                            (function() {
                                                if (window.__sf_hook) return;
                                                window.__sf_hook = true;

                                                function sendToken(t) {
                                                    if (t && typeof t === 'string' && t.length > 20 && window.SpotifyNativeBridge) {
                                                        window.SpotifyNativeBridge.onTokenReceived(t, Date.now() + 3600000, '', '');
                                                    }
                                                }

                                                // 1. Hook Fetch API calls for Authorization header
                                                const origFetch = window.fetch;
                                                window.fetch = function(...args) {
                                                    try {
                                                        const opts = args[1] || (typeof args[0] === 'object' ? args[0] : {});
                                                        const headers = opts ? opts.headers : null;
                                                        if (headers) {
                                                            let auth = null;
                                                            if (typeof Headers !== 'undefined' && headers instanceof Headers) {
                                                                auth = headers.get('authorization') || headers.get('Authorization');
                                                            } else if (typeof headers === 'object') {
                                                                auth = headers['authorization'] || headers['Authorization'];
                                                            }
                                                            if (auth && typeof auth === 'string' && auth.startsWith('Bearer ')) {
                                                                sendToken(auth.substring(7));
                                                            }
                                                        }
                                                    } catch(e) {}
                                                    return origFetch.apply(this, args);
                                                };

                                                // 2. Hook XMLHttpRequest for Authorization header
                                                const origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
                                                XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                                                    try {
                                                        if (header && header.toLowerCase() === 'authorization' && value && value.startsWith('Bearer ')) {
                                                            sendToken(value.substring(7));
                                                        }
                                                    } catch(e) {}
                                                    return origSetHeader.apply(this, arguments);
                                                };

                                                // 3. Check Web Player session object & storage
                                                try {
                                                    if (window.__spotify && window.__spotify.session && window.__spotify.session.accessToken) {
                                                        sendToken(window.__spotify.session.accessToken);
                                                    }
                                                    for (let i = 0; i < sessionStorage.length; i++) {
                                                        const val = sessionStorage.getItem(sessionStorage.key(i)) || '';
                                                        const m = val.match(/"accessToken":"([^"]+)"/) || val.match(/Bearer\s+([A-Za-z0-9_-]{50,})/);
                                                        if (m && m[1]) sendToken(m[1]);
                                                    }
                                                } catch(e) {}
                                            })();
                                        """.trimIndent()
                                        view?.evaluateJavascript(script, null)
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        injectAuthInterceptor(view)
                                        checkAndSubmitCookies(url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        injectAuthInterceptor(view)
                                        checkAndSubmitCookies(url)
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        super.doUpdateVisitedHistory(view, url, isReload)
                                        injectAuthInterceptor(view)
                                        checkAndSubmitCookies(url)
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: android.webkit.SslErrorHandler?,
                                        error: android.net.http.SslError?
                                    ) {
                                        handler?.proceed()
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                        error: android.webkit.WebResourceError?
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        if (request?.isForMainFrame == true) {
                                            android.util.Log.e("SpotifySignIn", "MainFrame error: ${error?.errorCode} ${error?.description}")
                                            localErrorMsg = "Load error: ${error?.description}"
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                        errorResponse: android.webkit.WebResourceResponse?
                                    ) {
                                        super.onReceivedHttpError(view, request, errorResponse)
                                        val code = errorResponse?.statusCode ?: 0
                                        if (request?.isForMainFrame == true && code >= 400) {
                                            android.util.Log.e("SpotifySignIn", "HTTP error $code on ${request.url}")
                                            localErrorMsg = "HTTP $code from server"
                                        }
                                    }
                                }
                                val bridge = object {
                                    @android.webkit.JavascriptInterface
                                    fun onTokenReceived(accessToken: String, expiresAt: Long, spDc: String, spKey: String) {
                                        if (accessToken.isNotBlank() && !hasSubmittedCookies) {
                                            hasSubmittedCookies = true
                                            isLoading = true
                                            viewModel.connectWithDirectToken(accessToken, expiresAt, spDc, spKey)
                                        }
                                    }
                                }
                                addJavascriptInterface(bridge, "SpotifyNativeBridge")

                                loadUrl(SpotifyAuth.LOGIN_URL)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Loading Overlay (Expressive M3 Dialog Box)
                androidx.compose.animation.AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            tonalElevation = 8.dp,
                            modifier = Modifier
                                .padding(32.dp)
                                .fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(color = spotifyGreen)
                                Text(
                                    "Connecting to Spotify...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Authenticating session token and syncing your Spotify library & playlists...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Error Card Overlay
                val activeError = vmErrorMessage ?: localErrorMsg
                activeError?.let { rawMsg ->
                    val userFriendlyMsg = when {
                        rawMsg.contains("totpVerExpired", ignoreCase = true) || rawMsg.contains("totp", ignoreCase = true) ->
                            "Spotify session key expired. Retrying connection..."
                        rawMsg.contains("anonymous", ignoreCase = true) ->
                            "Session expired. Please sign in again."
                        rawMsg.length > 220 ->
                            rawMsg.take(220) + "..."
                        else -> rawMsg
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "Spotify Connection Error",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    userFriendlyMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            localErrorMsg = null
                                            viewModel.dismissError()
                                        }
                                    ) {
                                        Text("Dismiss")
                                    }
                                    Button(
                                        onClick = {
                                            localErrorMsg = null
                                            viewModel.dismissError()
                                            hasSubmittedCookies = false
                                            isLoading = false
                                            webViewRef?.reload()
                                        }
                                    ) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Manual Token Quick Access Banner
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { showManualTokenSheet = true },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = spotifyGreen
                        )
                        Text(
                            "Having trouble signing in? Enter sp_dc token manually",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        "Manual Entry",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = spotifyGreen
                    )
                }
            }

            // Security & Privacy Trust Banner
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Privacy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Secure Direct Auth: Credentials are sent directly to Spotify. SpatialFlow never sees or stores your password.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showManualTokenSheet) {
        SpotifyManualTokenBottomSheet(
            onDismissRequest = { showManualTokenSheet = false },
            viewModel = viewModel,
            onLoginSuccess = {
                showManualTokenSheet = false
                onSignInSuccess()
            }
        )
    }
}
