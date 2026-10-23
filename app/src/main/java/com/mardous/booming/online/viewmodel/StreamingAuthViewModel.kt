package com.mardous.booming.online.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mardous.booming.online.data.innertube.AccountManager
import com.mardous.booming.online.data.innertube.UserProfile
import com.mardous.booming.online.data.innertube.YouTubeMusic
import com.mardous.booming.online.data.spotify.SpotifyAuth
import com.mardous.booming.online.data.spotify.SpotifyClient
import com.mardous.booming.online.data.spotify.SpotifyPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SpotifyAuthState {
    NOT_CONNECTED,
    LOADING,
    CONNECTED,
    ERROR,
}

class StreamingAuthViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _authState = MutableStateFlow(SpotifyAuthState.NOT_CONNECTED)
    val authState = _authState.asStateFlow()

    private val _accountName = MutableStateFlow("")
    val accountName = _accountName.asStateFlow()

    private val _accountAvatarUrl = MutableStateFlow<String?>(null)
    val accountAvatarUrl = _accountAvatarUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private val _ytProfile = MutableStateFlow<UserProfile?>(null)
    val ytProfile = _ytProfile.asStateFlow()

    private val _ytLoggedIn = MutableStateFlow(AccountManager.isLoggedIn(context))
    val ytLoggedIn = _ytLoggedIn.asStateFlow()

    init {
        SpotifyClient.setTokenRefresher {
            refreshTokenSilently()
        }

        if (SpotifyPrefs.isConnected(context)) {
            restoreSession()
        }
    }

    suspend fun refreshTokenSilently(): Boolean {
        val spDc = SpotifyPrefs.getSpDc(context)
        val spKey = SpotifyPrefs.getSpKey(context)
        if (spDc.isBlank()) return false

        return SpotifyAuth.fetchAccessToken(spDc, spKey)
            .map { token ->
                SpotifyClient.setToken(token.accessToken)
                SpotifyPrefs.saveAccessToken(context, token.accessToken)
                SpotifyPrefs.saveTokenExpiresAt(context, token.accessTokenExpirationTimestampMs)
                true
            }
            .getOrDefault(false)
    }

    private fun restoreSession() {
        _authState.value = SpotifyAuthState.LOADING
        _accountName.value = SpotifyPrefs.getAccountName(context)
        _accountAvatarUrl.value = SpotifyPrefs.getAccountAvatarUrl(context)

        viewModelScope.launch(Dispatchers.IO) {
            if (!SpotifyPrefs.isTokenExpiringSoon(context)) {
                SpotifyClient.setToken(SpotifyPrefs.getAccessToken(context))
                verifySession()
            } else {
                val spDc = SpotifyPrefs.getSpDc(context)
                val spKey = SpotifyPrefs.getSpKey(context)
                if (spDc.isNotBlank()) {
                    authenticateAndVerify(spDc, spKey)
                } else {
                    _authState.value = SpotifyAuthState.NOT_CONNECTED
                }
            }
        }
    }

    fun connectWithDirectToken(
        accessToken: String,
        expiresAtMs: Long,
        spDc: String = "",
        spKey: String = "",
    ) {
        if (accessToken.isBlank()) return
        _authState.value = SpotifyAuthState.LOADING
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            if (spDc.isNotBlank()) SpotifyPrefs.saveSpDc(context, spDc)
            if (spKey.isNotBlank()) SpotifyPrefs.saveSpKey(context, spKey)
            SpotifyClient.setToken(accessToken)
            SpotifyPrefs.saveAccessToken(context, accessToken)
            val expiry = if (expiresAtMs > 0) expiresAtMs else System.currentTimeMillis() + 3600_000L
            SpotifyPrefs.saveTokenExpiresAt(context, expiry)
            verifySession()
        }
    }

    fun connectWithCookies(spDc: String, spKey: String) {
        _authState.value = SpotifyAuthState.LOADING
        _errorMessage.value = null
        viewModelScope.launch(Dispatchers.IO) {
            SpotifyPrefs.saveSpDc(context, spDc)
            SpotifyPrefs.saveSpKey(context, spKey)
            authenticateAndVerify(spDc, spKey)
        }
    }

    fun loginWithManualToken(
        tokenInput: String,
        manualSpKey: String = "",
        onResult: (Result<String>) -> Unit = {},
    ) {
        val (spDc, spKey) = SpotifyAuth.parseCookieInput(tokenInput, manualSpKey)
        if (spDc.isBlank()) {
            val error = IllegalArgumentException("sp_dc token could not be found. Please check your input.")
            _errorMessage.value = error.message
            onResult(Result.failure(error))
            return
        }

        _authState.value = SpotifyAuthState.LOADING
        _errorMessage.value = null

        viewModelScope.launch(Dispatchers.IO) {
            SpotifyAuth.fetchAccessToken(spDc, spKey)
                .onSuccess { token ->
                    SpotifyPrefs.saveSpDc(context, spDc)
                    SpotifyPrefs.saveSpKey(context, spKey)
                    SpotifyClient.setToken(token.accessToken)
                    SpotifyPrefs.saveAccessToken(context, token.accessToken)
                    SpotifyPrefs.saveTokenExpiresAt(context, token.accessTokenExpirationTimestampMs)

                    verifySession()
                    val name = _accountName.value.ifEmpty { "Spotify User" }
                    withContext(Dispatchers.Main) {
                        onResult(Result.success(name))
                    }
                }
                .onFailure { e ->
                    Log.e("StreamingAuthViewModel", "Manual token auth failed", e)
                    _authState.value = SpotifyAuthState.ERROR
                    val errorMsg = if (e.message?.contains("anonymous", ignoreCase = true) == true) {
                        "Invalid or expired sp_dc token. Please copy a fresh token from your browser."
                    } else {
                        e.message ?: "Authentication failed. Please verify your token."
                    }
                    _errorMessage.value = errorMsg
                    withContext(Dispatchers.Main) {
                        onResult(Result.failure(Exception(errorMsg, e)))
                    }
                }
        }
    }

    fun logout() {
        SpotifyPrefs.clearAll(context)
        SpotifyClient.clearToken()
        _accountName.value = ""
        _accountAvatarUrl.value = null
        _errorMessage.value = null
        _authState.value = SpotifyAuthState.NOT_CONNECTED
    }

    fun dismissError() {
        _errorMessage.value = null
        _authState.value = if (SpotifyPrefs.isConnected(context)) {
            SpotifyAuthState.CONNECTED
        } else {
            SpotifyAuthState.NOT_CONNECTED
        }
    }

    private suspend fun authenticateAndVerify(spDc: String, spKey: String) {
        SpotifyAuth.fetchAccessToken(spDc, spKey)
            .onSuccess { token ->
                SpotifyClient.setToken(token.accessToken)
                SpotifyPrefs.saveAccessToken(context, token.accessToken)
                SpotifyPrefs.saveTokenExpiresAt(context, token.accessTokenExpirationTimestampMs)
                verifySession()
            }
            .onFailure { e ->
                Log.e("StreamingAuthViewModel", "Auth failed", e)
                _authState.value = SpotifyAuthState.ERROR
                _errorMessage.value = e.message ?: "Authentication failed"
            }
    }

    private suspend fun verifySession() {
        _authState.value = SpotifyAuthState.LOADING
        try {
            SpotifyClient.me().onSuccess { user ->
                val name = user.displayName ?: user.id.ifBlank { "Spotify User" }
                SpotifyPrefs.saveAccountName(context, name)
                val avatar = user.images.firstOrNull()?.url
                if (avatar != null) SpotifyPrefs.saveAccountAvatarUrl(context, avatar)
                _accountName.value = name
                _accountAvatarUrl.value = avatar
                _authState.value = SpotifyAuthState.CONNECTED
            }.onFailure { e ->
                Log.e("StreamingAuthViewModel", "Session verification failed", e)
                if (isOfflineTolerableFailure(e)) {
                    // Network unreachable but we hold a cached token: stay
                    // connected so offline playlists/downloads keep working.
                    _authState.value = SpotifyAuthState.CONNECTED
                } else {
                    _authState.value = SpotifyAuthState.ERROR
                    _errorMessage.value = e.message ?: "Failed to verify Spotify session"
                }
            }
        } catch (e: Throwable) {
            Log.e("StreamingAuthViewModel", "Session verification failed", e)
            if (isOfflineTolerableFailure(e)) {
                _authState.value = SpotifyAuthState.CONNECTED
            } else {
                _authState.value = SpotifyAuthState.ERROR
                _errorMessage.value = e.message ?: "Failed to verify Spotify session"
            }
        }
    }

    private fun isOfflineTolerableFailure(e: Throwable): Boolean {
        if (!SpotifyPrefs.isConnected(context) || !SpotifyClient.isAuthenticated()) return false
        val message = e.message.orEmpty().lowercase()
        val offlineHints = listOf(
            "timeout", "timed out", "resolve host", "unresolved", "unreachable",
            "connection", "connect", "network", "no address associated", "software caused connection abort"
        )
        return offlineHints.any { message.contains(it) }
    }

    fun ytOnLoggedIn(cookies: String) {
        AccountManager.saveLoginState(context, cookies)
        YouTubeMusic.setCookie(cookies)
        _ytLoggedIn.value = true
    }

    fun ytLoginWithManualCookie(
        rawCookie: String,
        onResult: (Result<UserProfile>) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = AccountManager.testAndSaveCookie(context, rawCookie)
            result.onSuccess { profile ->
                YouTubeMusic.setCookie(AccountManager.getAuthCookie(context))
                _ytProfile.value = profile
                _ytLoggedIn.value = true
            }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun ytLogout() {
        AccountManager.logout(context)
        YouTubeMusic.setCookie(null)
        _ytProfile.value = null
        _ytLoggedIn.value = false
    }
}
