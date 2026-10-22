package com.mardous.booming.online.data.innertube

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Low-level HTTP client for YouTube Music's InnerTube API.
 * Handles request construction, authentication, and raw HTTP communication.
 */
object InnerTubeClient {

    private const val TAG = "InnerTubeClient"
    private const val BASE_URL = "https://music.youtube.com/youtubei/v1"
    private const val YOUTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1"
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    private val JSON_MEDIA_TYPE_OBJ = JSON_MEDIA_TYPE.toMediaType()

    private val gson = Gson()

    private fun buildClient(cacheDir: File? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(com.mardous.booming.online.data.network.FastFallbackDns)
            .connectionPool(ConnectionPool(20, 10, TimeUnit.MINUTES))
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
        
        // Enable 100MB dynamic disk cache for player manifests & repetitive metadata responses
        cacheDir?.let {
            try {
                val httpCache = File(it, "http_cache_innertube")
                builder.cache(Cache(httpCache, 100L * 1024 * 1024)) // 100 MB
            } catch (_: Exception) { /* non-fatal cache fail */ }
        }
        
        return builder.build()
    }

    var httpClient = buildClient()
        private set

    private var appContext: android.content.Context? = null

    /**
     * Binds runtime context to initialize core HTTP disk caching and shared state hooks.
     * Recommended execution target: Application.onCreate().
     */
    @JvmStatic
    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
        // Re-instantiate full persistence client with application storage context available
        httpClient = buildClient(context.applicationContext.cacheDir)
        Log.d(TAG, "InnerTube client initialized")
    }

    // ========== Client Configurations ==========

    @Volatile
    private var cachedWebRemixContext: JsonObject? = null
    @Volatile
    private var lastVisitorDataWebRemix: String? = null

    /**
     * WEB_REMIX client — used for search, browse, next (YouTube Music Web)
     */
    private fun webRemixContext(): JsonObject {
        val currentVisitorData = visitorData
        val cached = cachedWebRemixContext
        if (cached != null && lastVisitorDataWebRemix == currentVisitorData) {
            return cached.deepCopy()
        }
        val client = JsonObject().apply {
            addProperty("clientName", "WEB_REMIX")
            addProperty("clientVersion", "1.20241118.01.00")
            addProperty("hl", Locale.getDefault().language)
            addProperty("gl", Locale.getDefault().country.ifEmpty { "US" })
            currentVisitorData?.let { addProperty("visitorData", it) }
        }
        val res = JsonObject().apply { add("client", client) }
        cachedWebRemixContext = res
        lastVisitorDataWebRemix = currentVisitorData
        return res.deepCopy()
    }

    @Volatile
    private var cachedWebContext: JsonObject? = null
    @Volatile
    private var lastVisitorDataWeb: String? = null

    /**
     * WEB client — used for standard YouTube Web requests (e.g. nextYoutubeWeb)
     */
    private fun webContext(): JsonObject {
        val currentVisitorData = visitorData
        val cached = cachedWebContext
        if (cached != null && lastVisitorDataWeb == currentVisitorData) {
            return cached.deepCopy()
        }
        val client = JsonObject().apply {
            addProperty("clientName", "WEB")
            addProperty("clientVersion", "2.20241118.01.00")
            addProperty("hl", Locale.getDefault().language)
            addProperty("gl", Locale.getDefault().country.ifEmpty { "US" })
            currentVisitorData?.let { addProperty("visitorData", it) }
        }
        val res = JsonObject().apply { add("client", client) }
        cachedWebContext = res
        lastVisitorDataWeb = currentVisitorData
        return res.deepCopy()
    }

    @Volatile
    private var cachedAndroidContext: JsonObject? = null
    @Volatile
    private var lastVisitorDataAndroid: String? = null

    /**
     * ANDROID client — Core modern YouTube client for resilient logged-out streaming.
     */
    private fun androidContext(): JsonObject {
        val currentVisitorData = visitorData
        val cached = cachedAndroidContext
        if (cached != null && lastVisitorDataAndroid == currentVisitorData) {
            return cached.deepCopy()
        }
        val client = JsonObject().apply {
            addProperty("clientName", "ANDROID")
            addProperty("clientVersion", "20.10.38")
            addProperty("androidSdkVersion", 30)
            addProperty("osName", "Android")
            addProperty("osVersion", "11")
            addProperty("platform", "MOBILE")
            addProperty("hl", Locale.getDefault().language)
            addProperty("gl", Locale.getDefault().country.ifEmpty { "US" })
            currentVisitorData?.let { addProperty("visitorData", it) }
        }
        val res = JsonObject().apply { add("client", client) }
        cachedAndroidContext = res
        lastVisitorDataAndroid = currentVisitorData
        return res.deepCopy()
    }

    // ========== User Agents & Clients ==========

    const val USER_AGENT_WEB_REMIX = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    const val USER_AGENT_ANDROID = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
    const val USER_AGENT_IOS = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"

    // Standard public API keys: YouTube Music key & YouTube Main key (Player key)
    private const val INNER_TUBE_API_KEY = "AIzaSyAO_JVGg4tq4r2T5Co2t8G3oG1d1dQ"
    private const val YOUTUBE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

    // ========== Cookie / Auth ==========

    var cookie: String? = null
        set(value) {
            field = value
            visitorData = null // Auto-reset visitor data when cookie changes to prevent session conflicts
        }
    var visitorData: String? = null


    /**
     * Injects InnerTube headers directly into Request.Builder without allocating intermediate Map or Entry objects.
     */
    fun applyHeaders(builder: Request.Builder, clientType: ClientType, isYoutube: Boolean = false) {
        builder.header("Content-Type", JSON_MEDIA_TYPE)
        builder.header("Accept-Language", "${Locale.getDefault().language},en;q=0.9")
        builder.header("X-Goog-Api-Format-Version", "1")

        when (clientType) {
            ClientType.WEB_REMIX -> {
                builder.header("User-Agent", USER_AGENT_WEB_REMIX)
                builder.header("X-YouTube-Client-Name", "67")
                builder.header("X-YouTube-Client-Version", "1.20241118.01.00")
                builder.header("X-Origin", "https://music.youtube.com")
                builder.header("Origin", "https://music.youtube.com")
                builder.header("Referer", "https://music.youtube.com/")
            }
            ClientType.WEB -> {
                builder.header("User-Agent", USER_AGENT_WEB)
                builder.header("X-YouTube-Client-Name", "1")
                builder.header("X-YouTube-Client-Version", "2.20241118.01.00")
                builder.header("X-Origin", "https://www.youtube.com")
                builder.header("Origin", "https://www.youtube.com")
                builder.header("Referer", "https://www.youtube.com/")
            }
            ClientType.ANDROID_VR -> {
                builder.header("User-Agent", com.mardous.booming.online.util.StreamClientUtils.USER_AGENT_ANDROID_VR)
                builder.header("X-YouTube-Client-Name", "28")
                builder.header("X-YouTube-Client-Version", "1.65.10")
            }
            ClientType.IOS -> {
                builder.header("User-Agent", USER_AGENT_IOS)
                builder.header("X-YouTube-Client-Name", "5")
                builder.header("X-YouTube-Client-Version", "19.29.1")
            }
            ClientType.VISIONOS -> {
                builder.header("User-Agent", com.mardous.booming.online.util.StreamClientUtils.USER_AGENT_VISIONOS)
                builder.header("X-YouTube-Client-Name", "102")
                builder.header("X-YouTube-Client-Version", "1.2.1")
            }
            ClientType.TVHTML5 -> {
                builder.header("User-Agent", com.mardous.booming.online.util.StreamClientUtils.USER_AGENT_TVHTML5)
                builder.header("X-YouTube-Client-Name", "85")
                builder.header("X-YouTube-Client-Version", "7.20241118.11.00")
                builder.header("X-Origin", "https://www.youtube.com")
                builder.header("Origin", "https://www.youtube.com")
                builder.header("Referer", "https://www.youtube.com/tv")
            }
            ClientType.MWEB -> {
                builder.header("User-Agent", com.mardous.booming.online.util.StreamClientUtils.USER_AGENT_MWEB)
                builder.header("X-YouTube-Client-Name", "2")
                builder.header("X-YouTube-Client-Version", "2.20241118.01.00")
                builder.header("X-Origin", "https://m.youtube.com")
                builder.header("Origin", "https://m.youtube.com")
                builder.header("Referer", "https://m.youtube.com/")
            }
            ClientType.ANDROID -> {
                builder.header("User-Agent", USER_AGENT_ANDROID)
                builder.header("X-YouTube-Client-Name", "3")
                builder.header("X-YouTube-Client-Version", "20.10.38")
            }
        }

        if (clientType == ClientType.WEB_REMIX || clientType == ClientType.WEB) {
            cookie?.let { rawCookie ->
                builder.header("Cookie", rawCookie)
                val sapisid = getSapisid(rawCookie)
                if (sapisid != null) {
                    val origin = if (isYoutube) "https://www.youtube.com" else "https://music.youtube.com"
                    builder.header("Authorization", getSapisidAuthHeader(sapisid, origin))
                }
            }
        }

        visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }
    }

    internal fun buildHeaders(clientType: ClientType, isYoutube: Boolean = false): Map<String, String> {
        val dummy = Request.Builder().url("https://music.youtube.com")
        applyHeaders(dummy, clientType, isYoutube)
        val headers = dummy.build().headers
        val map = HashMap<String, String>(headers.size)
        for (i in 0 until headers.size) {
            map[headers.name(i)] = headers.value(i)
        }
        return map
    }

    @Volatile
    private var lastRawCookie: String? = null
    @Volatile
    private var cachedSapisid: String? = null

    private fun extractCookieValue(cookie: String, name: String): String? {
        var startIdx = 0
        while (startIdx < cookie.length) {
            val nameIdx = cookie.indexOf(name, startIdx)
            if (nameIdx == -1) return null
            val isValidStart = nameIdx == 0 || cookie[nameIdx - 1] == ' ' || cookie[nameIdx - 1] == ';'
            val equalsIdx = nameIdx + name.length
            if (isValidStart && equalsIdx < cookie.length && cookie[equalsIdx] == '=') {
                val valueStart = equalsIdx + 1
                val semiIdx = cookie.indexOf(';', valueStart)
                val valueEnd = if (semiIdx != -1) semiIdx else cookie.length
                return cookie.substring(valueStart, valueEnd).trim()
            }
            startIdx = nameIdx + 1
        }
        return null
    }

    private fun getSapisid(rawCookie: String): String? {
        if (rawCookie == lastRawCookie) return cachedSapisid
        val sapisid = extractCookieValue(rawCookie, "SAPISID")
            ?: extractCookieValue(rawCookie, "__Secure-3PAPISID")
        lastRawCookie = rawCookie
        cachedSapisid = sapisid
        return sapisid
    }

    @Volatile
    private var lastSapisidHashTimestamp: Long = 0
    @Volatile
    private var lastSapisidAuthHeader: Pair<String, String>? = null

    private fun getSapisidAuthHeader(sapisid: String, origin: String): String {
        val nowSec = System.currentTimeMillis() / 1000
        val cacheKey = "$origin:$sapisid"
        val cached = lastSapisidAuthHeader
        if (cached != null && cached.first == cacheKey && (nowSec - lastSapisidHashTimestamp) < 60) {
            return cached.second
        }
        val hashInput = "$nowSec $sapisid $origin"
        val hash = sha1(hashInput)
        val headerVal = "SAPISIDHASH ${nowSec}_$hash"
        lastSapisidHashTimestamp = nowSec
        lastSapisidAuthHeader = Pair(cacheKey, headerVal)
        return headerVal
    }



    // ========== API Endpoints ==========

    /**
     * Search YouTube Music
     */
    suspend fun search(
        query: String,
        filter: String? = null,
        continuation: String? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
            if (continuation != null) {
                addProperty("continuation", continuation)
            } else {
                addProperty("query", query)
                filter?.let { addProperty("params", it) }
            }
        }
        post("$BASE_URL/search", body, ClientType.WEB_REMIX)
    }

    /**
     * Get search suggestions
     */
    suspend fun searchSuggestions(query: String): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
            addProperty("input", query)
        }
        post("$BASE_URL/music/get_search_suggestions", body, ClientType.WEB_REMIX)
    }

    /**
     * Browse endpoint — home feed, artist pages, album pages, playlists
     */
    suspend fun browse(
        browseId: String? = null,
        params: String? = null,
        continuation: String? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val cleanBrowseId = browseId?.trim()
        val cleanContinuation = continuation?.trim()

        val finalContinuation = cleanContinuation.takeUnless {
            it.isNullOrEmpty() || it == "null" || it == "undefined"
        }
        val finalBrowseId = cleanBrowseId.takeUnless {
            it.isNullOrEmpty() || it == "null" || it == "undefined" || it == "VL" || it == "VLnull" || it == "VLundefined"
        }

        if (finalBrowseId == null && finalContinuation == null) {
            Log.e(TAG, "Aborting browse call: invalid or empty browseId ('$browseId') and continuation ('$continuation').")
            return@withContext JsonObject()
        }

        val body = JsonObject().apply {
            add("context", webRemixContext())
            if (finalContinuation != null) {
                addProperty("continuation", finalContinuation)
            } else {
                finalBrowseId?.let { addProperty("browseId", it) }
                params?.let { addProperty("params", it) }
            }
        }
        post("$BASE_URL/browse", body, ClientType.WEB_REMIX)
    }

    /**
     * Player endpoint — get streaming data for a video.
     * Tries: Invidious → Piped → ANDROID (youtube.com) → ANDROID_MUSIC
     */
    /**
     * Player endpoint specifically for fetching music configuration with WEB_REMIX
     * Used for retrieving authentic playback position (watch history resume).
     */
    suspend fun playerWebRemix(videoId: String): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
            addProperty("videoId", videoId)
            
            // Add playbackContext containing signatureTimestamp to prevent HTTP 400/403
            val sigTimestamp = try {
                org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
            } catch (_: Throwable) {
                null
            }
            sigTimestamp?.let { timestamp ->
                val contentContext = JsonObject().apply {
                    addProperty("signatureTimestamp", timestamp)
                }
                val playbackCtx = JsonObject().apply {
                    add("contentPlaybackContext", contentContext)
                }
                add("playbackContext", playbackCtx)
            }
        }
        post("$BASE_URL/player", body, ClientType.WEB_REMIX)
    }

    private val clientScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedSigTimestampPair: Pair<Int, Long>? = null

    fun getCachedSignatureTimestamp(videoId: String? = null): Int? {
        val current = cachedSigTimestampPair
        if (current != null && System.currentTimeMillis() < current.second) {
            return current.first
        }
        // Asynchronously fetch/refresh signature timestamp in background without blocking player()
        if (videoId != null) {
            clientScope.launch {
                try {
                    val fetched = org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
                    if (fetched > 0) {
                        cachedSigTimestampPair = Pair(fetched, System.currentTimeMillis() + 86_400_000L)
                    }
                } catch (_: Throwable) {}
            }
        }
        return current?.first
    }

    private const val FAILED_CLIENT_BACKOFF_MS = 10 * 60 * 1000L // 10 minutes backoff
    private val failedStreamClientsUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun markStreamClientFailed(videoId: String, clientName: String) {
        if (failedStreamClientsUntil.size > 100) {
            val now = System.currentTimeMillis()
            failedStreamClientsUntil.entries.removeIf { it.value <= now }
        }
        val key = "$videoId:${clientName.uppercase()}"
        failedStreamClientsUntil[key] = System.currentTimeMillis() + FAILED_CLIENT_BACKOFF_MS
        com.mardous.booming.online.util.ConsoleLogger.client("Client $clientName temporarily blacklisted for $videoId after failure")
    }

    fun isStreamClientBlocked(videoId: String, clientName: String): Boolean {
        val key = "$videoId:${clientName.uppercase()}"
        val until = failedStreamClientsUntil[key] ?: return false
        if (until <= System.currentTimeMillis()) {
            failedStreamClientsUntil.remove(key)
            return false
        }
        return true
    }

    /**
     * Player endpoint — get streaming data for a video.
     * Sequences: standard ANDROID core -> IOS core fallback -> WEB_REMIX fallback.
     */
    suspend fun player(videoId: String?, playlistId: String? = null): JsonObject = withContext(Dispatchers.IO) {
        if (videoId == null) return@withContext JsonObject()

        val sigTimestamp = getCachedSignatureTimestamp(videoId)
        val (prefQuality, prefDataSaver, prefForceOpus) = NewPipeStreamExtractor.getPreferencesSnapshot()
        val preferOpus = prefForceOpus || prefQuality.equals("High", ignoreCase = true) || prefQuality.equals("Highest", ignoreCase = true)

        var cipherFallbackResult: JsonObject? = null

        // Client execution helpers
        suspend fun tryAndroidVr(): JsonObject? {
            if (isStreamClientBlocked(videoId, "ANDROID_VR")) {
                com.mardous.booming.online.util.ConsoleLogger.client("Skipping ANDROID_VR for $videoId (temporarily blocked)")
                return null
            }
            val res = playerWithClient(
                videoId, playlistId,
                clientType = ClientType.ANDROID_VR,
                clientName = "ANDROID_VR", clientVersion = "1.65.10",
                userAgent = com.mardous.booming.online.util.StreamClientUtils.USER_AGENT_ANDROID_VR,
                baseUrl = YOUTUBE_BASE_URL,
                signatureTimestamp = sigTimestamp,
                extraContext = { ctx ->
                    ctx.addProperty("deviceMake", "Oculus")
                    ctx.addProperty("deviceModel", "Quest 3")
                    ctx.addProperty("osName", "Android")
                    ctx.addProperty("osVersion", "12L")
                    ctx.addProperty("platform", "MOBILE")
                    ctx.addProperty("androidSdkVersion", 32)
                }
            )
            if (hasDirectAudio(res)) {
                com.mardous.booming.online.util.ConsoleLogger.client("Direct OPUS/audio acquired via ANDROID_VR ($videoId)")
                return res
            }
            if (isPlayerResponseValid(res) && cipherFallbackResult == null) cipherFallbackResult = res
            return null
        }

        suspend fun tryVisionOs(): JsonObject? {
            if (isStreamClientBlocked(videoId, "VISIONOS")) {
                com.mardous.booming.online.util.ConsoleLogger.client("Skipping VISIONOS for $videoId (temporarily blocked)")
                return null
            }
            val res = playerWithClient(
                videoId, playlistId,
                clientType = ClientType.VISIONOS,
                clientName = "VISIONOS", clientVersion = "1.2.1",
                userAgent = com.mardous.booming.online.util.StreamClientUtils.USER_AGENT_VISIONOS,
                baseUrl = YOUTUBE_BASE_URL,
                signatureTimestamp = sigTimestamp,
                extraContext = { ctx ->
                    ctx.addProperty("deviceMake", "Apple")
                    ctx.addProperty("deviceModel", "Apple Vision Pro")
                    ctx.addProperty("osName", "visionOS")
                    ctx.addProperty("osVersion", "1.2.1")
                    ctx.addProperty("platform", "VISION")
                }
            )
            if (hasDirectAudio(res)) {
                com.mardous.booming.online.util.ConsoleLogger.client("Direct audio acquired via VISIONOS ($videoId)")
                return res
            }
            if (isPlayerResponseValid(res) && cipherFallbackResult == null) cipherFallbackResult = res
            return null
        }

        suspend fun tryIos(): JsonObject? {
            if (isStreamClientBlocked(videoId, "IOS")) {
                com.mardous.booming.online.util.ConsoleLogger.client("Skipping IOS for $videoId (temporarily blocked)")
                return null
            }
            val res = playerWithClient(
                videoId, playlistId,
                clientType = ClientType.IOS,
                clientName = "IOS", clientVersion = "19.29.1",
                userAgent = USER_AGENT_IOS,
                baseUrl = YOUTUBE_BASE_URL,
                signatureTimestamp = sigTimestamp,
                extraContext = { ctx ->
                    ctx.addProperty("deviceMake", "Apple")
                    ctx.addProperty("deviceModel", "iPhone16,2")
                    ctx.addProperty("osName", "iPhone")
                    ctx.addProperty("osVersion", "17.5.1.21F90")
                    ctx.addProperty("platform", "MOBILE")
                }
            )
            if (hasDirectAudio(res)) {
                com.mardous.booming.online.util.ConsoleLogger.client("Direct AAC acquired via IOS ($videoId)")
                return res
            }
            if (isPlayerResponseValid(res) && cipherFallbackResult == null) cipherFallbackResult = res
            return null
        }

        suspend fun tryWebRemix(): JsonObject? {
            if (isStreamClientBlocked(videoId, "WEB_REMIX")) {
                com.mardous.booming.online.util.ConsoleLogger.client("Skipping WEB_REMIX for $videoId (temporarily blocked)")
                return null
            }
            val res = playerWithClient(
                videoId, playlistId,
                clientType = ClientType.WEB_REMIX,
                clientName = "WEB_REMIX", clientVersion = "1.20241118.01.00",
                userAgent = USER_AGENT_WEB_REMIX,
                baseUrl = BASE_URL,
                signatureTimestamp = sigTimestamp
            )
            if (hasDirectAudio(res)) {
                com.mardous.booming.online.util.ConsoleLogger.client("Direct audio acquired via WEB_REMIX ($videoId)")
                return res
            }
            if (isPlayerResponseValid(res) && cipherFallbackResult == null) cipherFallbackResult = res
            return null
        }

        suspend fun tryTvHtml5(): JsonObject? {
            if (isStreamClientBlocked(videoId, "TVHTML5")) {
                com.mardous.booming.online.util.ConsoleLogger.client("Skipping TVHTML5 for $videoId (temporarily blocked)")
                return null
            }
            val res = playerWithClient(
                videoId, playlistId,
                clientType = ClientType.TVHTML5,
                clientName = "TVHTML5", clientVersion = "7.20241118.11.00",
                userAgent = com.mardous.booming.online.util.StreamClientUtils.USER_AGENT_TVHTML5,
                baseUrl = YOUTUBE_BASE_URL,
                signatureTimestamp = sigTimestamp
            )
            if (hasDirectAudio(res)) {
                com.mardous.booming.online.util.ConsoleLogger.client("Direct audio acquired via TVHTML5 ($videoId)")
                return res
            }
            if (isPlayerResponseValid(res) && cipherFallbackResult == null) cipherFallbackResult = res
            return null
        }

        suspend fun tryMWeb(): JsonObject? {
            if (isStreamClientBlocked(videoId, "MWEB")) {
                com.mardous.booming.online.util.ConsoleLogger.client("Skipping MWEB for $videoId (temporarily blocked)")
                return null
            }
            val res = playerWithClient(
                videoId, playlistId,
                clientType = ClientType.MWEB,
                clientName = "MWEB", clientVersion = "2.20241118.01.00",
                userAgent = com.mardous.booming.online.util.StreamClientUtils.USER_AGENT_MWEB,
                baseUrl = YOUTUBE_BASE_URL,
                signatureTimestamp = sigTimestamp
            )
            if (hasDirectAudio(res)) {
                com.mardous.booming.online.util.ConsoleLogger.client("Direct audio acquired via MWEB ($videoId)")
                return res
            }
            if (isPlayerResponseValid(res) && cipherFallbackResult == null) cipherFallbackResult = res
            return null
        }

        // Execution hierarchy for STREAM_FALLBACK_CLIENTS:
        // Priority 1: VISIONOS (direct un-ciphered Opus/AAC without bot-guard friction)
        // Priority 2: ANDROID_VR (direct Opus 160k with client ID 28)
        // Priority 3: IOS (direct pristine AAC 256k/128k)
        // Priority 4: TVHTML5 / MWEB
        val directResult = if (preferOpus) {
            tryVisionOs() ?: tryAndroidVr() ?: tryIos() ?: tryTvHtml5() ?: tryMWeb()
        } else {
            tryVisionOs() ?: tryIos() ?: tryAndroidVr() ?: tryTvHtml5() ?: tryMWeb()
        }

        if (directResult != null) {
            return@withContext directResult
        }

        if (cipherFallbackResult != null) {
            com.mardous.booming.online.util.ConsoleLogger.client("No direct audio found, falling back to ciphered player response ($videoId)")
            return@withContext cipherFallbackResult
        }

        com.mardous.booming.online.util.ConsoleLogger.client("All clients exhausted for $videoId")
        return@withContext JsonObject()
    }

    private suspend fun OkHttpClient.executeSuspended(request: Request): okhttp3.Response =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = newCall(request)
            continuation.invokeOnCancellation {
                try {
                    call.cancel()
                } catch (_: Exception) { /* mute */ }
            }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(e))
                    }
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(response))
                    } else {
                        response.close()
                    }
                }
            })
        }

    /**
     * Generic InnerTube player request with configurable client.
     */
    private suspend fun playerWithClient(
        videoId: String,
        playlistId: String?,
        clientType: ClientType,
        clientName: String,
        clientVersion: String,
        userAgent: String,
        baseUrl: String,
        signatureTimestamp: Int? = null,
        extraBody: (JsonObject) -> Unit = {},
        extraContext: (JsonObject) -> Unit = {}
    ): JsonObject = withContext(Dispatchers.IO) {
        val clientContext = JsonObject().apply {
            addProperty("clientName", clientName)
            addProperty("clientVersion", clientVersion)
            addProperty("hl", Locale.getDefault().language)
            addProperty("gl", Locale.getDefault().country.ifEmpty { "US" })
            visitorData?.let { addProperty("visitorData", it) }
            extraContext(this)
        }
        val context = JsonObject().apply { add("client", clientContext) }

        val body = JsonObject().apply {
            add("context", context)
            addProperty("videoId", videoId)
            playlistId?.let { addProperty("playlistId", it) }
            addProperty("contentCheckOk", true)
            addProperty("racyCheckOk", true)
            
            // Inject official signature timestamp if available to generate valid, unblocked cipher URLs
            if (signatureTimestamp != null) {
                val contentContext = JsonObject().apply {
                    addProperty("signatureTimestamp", signatureTimestamp)
                }
                val playbackCtx = JsonObject().apply {
                    add("contentPlaybackContext", contentContext)
                }
                add("playbackContext", playbackCtx)
            }
            extraBody(this)
        }

        val requestBody = gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE_OBJ)

        val isYoutube = baseUrl.contains("www.youtube.com")
        val apiKey = if (isYoutube) YOUTUBE_API_KEY else INNER_TUBE_API_KEY

        val request = Request.Builder()
            .url("$baseUrl/player?key=$apiKey&prettyPrint=false")
            .post(requestBody)
            .apply {
                applyHeaders(this, clientType, isYoutube)
                header("User-Agent", userAgent)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                cookie?.let { header("Cookie", it) }
            }
            .build()

        return@withContext try {
            httpClient.executeSuspended(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Player $clientName HTTP ${response.code}")
                    JsonObject()
                } else {
                    response.body.charStream().use { reader ->
                        JsonParser.parseReader(reader).asJsonObject
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Player $clientName failed: ${t.message}")
            JsonObject()
        }
    }


    // ========== Validation ==========

    fun isFormatPlayable(format: JsonObject): Boolean {
        val hasDirectUrl = format.has("url") && !format.get("url").asString.isNullOrEmpty()
        val hasCipher = format.has("signatureCipher") || format.has("cipher")
        return hasDirectUrl || hasCipher
    }

    private inline fun anyFormat(
        adaptive: com.google.gson.JsonArray?,
        formats: com.google.gson.JsonArray?,
        predicate: (JsonObject) -> Boolean
    ): Boolean {
        if (adaptive != null) {
            val size = adaptive.size()
            for (i in 0 until size) {
                val elem = adaptive.get(i)
                if (elem.isJsonObject && predicate(elem.asJsonObject)) return true
            }
        }
        if (formats != null) {
            val size = formats.size()
            for (i in 0 until size) {
                val elem = formats.get(i)
                if (elem.isJsonObject && predicate(elem.asJsonObject)) return true
            }
        }
        return false
    }

    fun hasDirectAudio(response: JsonObject?): Boolean {
        if (response == null || response.size() == 0) return false
        val playability = response.getAsJsonObject("playabilityStatus") ?: return false
        if (playability.get("status")?.asString != "OK") return false
        val streamingData = response.getAsJsonObject("streamingData") ?: return false
        val adaptive = streamingData.getAsJsonArray("adaptiveFormats")
        val formats = streamingData.getAsJsonArray("formats")
        return anyFormat(adaptive, formats) { format ->
            val mime = format.get("mimeType")?.asString ?: ""
            (mime.startsWith("audio/") || mime.contains("mp4a", ignoreCase = true)) &&
            format.has("url") && !format.get("url").asString.isNullOrEmpty()
        }
    }

    private fun isPlayerResponseValid(response: JsonObject): Boolean {
        if (response.size() == 0) return false
        val playability = response.getAsJsonObject("playabilityStatus") ?: return false
        if (playability.get("status")?.asString != "OK") return false
        val streamingData = response.getAsJsonObject("streamingData") ?: return false
        val adaptive = streamingData.getAsJsonArray("adaptiveFormats")
        val formats = streamingData.getAsJsonArray("formats")
        return anyFormat(adaptive, formats) { format ->
            val mime = format.get("mimeType")?.asString ?: ""
            (mime.startsWith("audio/") || mime.startsWith("video/")) && isFormatPlayable(format)
        }
    }

    private fun logPlayerFailure(clientName: String, response: JsonObject) {
        val status = response.getAsJsonObject("playabilityStatus")?.get("status")?.asString
        val reason = response.getAsJsonObject("playabilityStatus")?.get("reason")?.asString
        val hasStreamingData = response.has("streamingData")
        val streamKeys = response.getAsJsonObject("streamingData")?.keySet()?.joinToString() ?: "none"
        Log.w(TAG, "$clientName player failed — status=$status, reason=$reason, hasStreamingData=$hasStreamingData, streamKeys=[$streamKeys]")
    }

    /**
     * Next endpoint — get queue, related songs, lyrics endpoint
     */
    suspend fun next(
        videoId: String?,
        playlistId: String? = null,
        playlistSetVideoId: String? = null,
        index: Int? = null,
        params: String? = null,
        continuation: String? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
            if (continuation != null) {
                addProperty("continuation", continuation)
            } else {
                videoId?.let { addProperty("videoId", it) }
                playlistId?.let { addProperty("playlistId", it) }
                playlistSetVideoId?.let { addProperty("playlistSetVideoId", it) }
                index?.let { addProperty("index", it) }
                params?.let { addProperty("params", it) }
            }
        }
        post("$BASE_URL/next", body, ClientType.WEB_REMIX)
    }

    /**
     * Next endpoint with standard ANDROID client for robust metadata/engagement parsing.
     */
    suspend fun nextAndroid(videoId: String? = null, continuation: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", androidContext())
            if (continuation != null) {
                addProperty("continuation", continuation)
            } else {
                videoId?.let { addProperty("videoId", it) }
            }
        }
        post("$YOUTUBE_BASE_URL/next", body, ClientType.ANDROID)
    }

    /**
     * Next endpoint using standard YouTube Web host for un-obfuscated guest likes count extraction.
     */
    suspend fun nextYoutubeWeb(videoId: String? = null, continuation: String? = null): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webContext())
            if (continuation != null) {
                addProperty("continuation", continuation)
            } else {
                videoId?.let { addProperty("videoId", it) }
            }
        }
        post("$YOUTUBE_BASE_URL/next", body, ClientType.WEB)
    }


    /**
     * Account menu endpoint — used to fetch user's profile data
     */
    suspend fun accountMenu(): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
        }
        post("$BASE_URL/account/account_menu", body, ClientType.WEB_REMIX)
    }

    // ========== HTTP Layer ==========
 
    private suspend fun post(url: String, body: JsonObject, clientType: ClientType): JsonObject = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE_OBJ)

        val separator = if (url.contains("?")) "&" else "?"
        val requestBuilder = Request.Builder()
            .url("$url${separator}key=$INNER_TUBE_API_KEY&prettyPrint=false")
            .post(requestBody)

        val isYoutube = url.contains("www.youtube.com")
        applyHeaders(requestBuilder, clientType, isYoutube)

        val request = requestBuilder.build()

        return@withContext try {
            httpClient.executeSuspended(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $url [${clientType.name}] (skipping response body parsing)")
                    JsonObject()
                } else {
                    val parsed = response.body.charStream().use { reader ->
                        JsonParser.parseReader(reader).asJsonObject
                    }
                    // Extract visitor data from response if available
                    parsed.getAsJsonObject("responseContext")
                        ?.get("visitorData")?.asString?.let {
                            if (visitorData == null) {
                                visitorData = it
                                Log.d(TAG, "Got visitor data from response")
                            }
                        }
                    parsed
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Request failed [${clientType.name}]: ${t.message}", t)
            JsonObject()
        }
    }

    /**
     * Like a video/song to influence preferences/recap on YouTube Music
     */
    suspend fun like(targetId: String): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
            val target = JsonObject().apply {
                if (targetId.startsWith("VL") || targetId.startsWith("PL") || targetId.startsWith("RD")) {
                    addProperty("playlistId", targetId)
                } else {
                    addProperty("videoId", targetId)
                }
            }
            add("target", target)
        }
        post("$BASE_URL/like/like", body, ClientType.WEB_REMIX)
    }

    /**
     * Dislike a video/song on YouTube Music
     */
    suspend fun dislike(targetId: String): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
            val target = JsonObject().apply {
                if (targetId.startsWith("VL") || targetId.startsWith("PL") || targetId.startsWith("RD")) {
                    addProperty("playlistId", targetId)
                } else {
                    addProperty("videoId", targetId)
                }
            }
            add("target", target)
        }
        post("$BASE_URL/like/dislike", body, ClientType.WEB_REMIX)
    }

    /**
     * Remove a like to sync preference state
     */
    suspend fun removeLike(targetId: String): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
            val target = JsonObject().apply {
                if (targetId.startsWith("VL") || targetId.startsWith("PL") || targetId.startsWith("RD")) {
                    addProperty("playlistId", targetId)
                } else {
                    addProperty("videoId", targetId)
                }
            }
            add("target", target)
        }
        post("$BASE_URL/like/removelike", body, ClientType.WEB_REMIX)
    }

    // ========== Playlist Management ==========

    /**
     * Create a new playlist on the user's YouTube Music account.
     */
    suspend fun createPlaylist(
        title: String,
        description: String = "",
        privacyStatus: String = "PRIVATE", // PRIVATE, PUBLIC, UNLISTED
        videoIds: List<String> = emptyList()
    ): JsonObject = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            add("context", webRemixContext())
            addProperty("title", title)
            addProperty("description", description)
            addProperty("privacyStatus", privacyStatus)
            if (videoIds.isNotEmpty()) {
                val arr = com.google.gson.JsonArray()
                videoIds.forEach { arr.add(it) }
                add("videoIds", arr)
            }
        }
        post("$BASE_URL/playlist/create", body, ClientType.WEB_REMIX)
    }

    /**
     * Delete a playlist from the user's YouTube Music library.
     */
    suspend fun deletePlaylist(playlistId: String): JsonObject = withContext(Dispatchers.IO) {
        val cleanPlaylistId = playlistId.removePrefix("VL")
        val body = JsonObject().apply {
            add("context", webRemixContext())
            addProperty("playlistId", cleanPlaylistId)
        }
        post("$BASE_URL/playlist/delete", body, ClientType.WEB_REMIX)
    }

    /**
     * Add songs to an existing playlist on YouTube Music.
     */
    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): JsonObject = withContext(Dispatchers.IO) {
        val cleanPlaylistId = playlistId.removePrefix("VL")
        val actions = com.google.gson.JsonArray()
        videoIds.forEach { videoId ->
            val action = JsonObject().apply {
                addProperty("action", "ACTION_ADD_VIDEO")
                addProperty("addedVideoId", videoId)
            }
            actions.add(action)
        }
        val body = JsonObject().apply {
            add("context", webRemixContext())
            addProperty("playlistId", cleanPlaylistId)
            add("actions", actions)
        }
        post("$BASE_URL/browse/edit_playlist", body, ClientType.WEB_REMIX)
    }

    /**
     * Remove a song from a YouTube Music playlist.
     */
    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String): JsonObject = withContext(Dispatchers.IO) {
        val cleanPlaylistId = playlistId.removePrefix("VL")
        val actions = com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("action", "ACTION_REMOVE_VIDEO")
                addProperty("setVideoId", setVideoId)
                addProperty("removedVideoId", videoId)
            })
        }
        val body = JsonObject().apply {
            add("context", webRemixContext())
            addProperty("playlistId", cleanPlaylistId)
            add("actions", actions)
        }
        post("$BASE_URL/browse/edit_playlist", body, ClientType.WEB_REMIX)
    }

    /**
     * Rename a YouTube Music playlist.
     */
    suspend fun renamePlaylist(playlistId: String, newName: String): JsonObject = withContext(Dispatchers.IO) {
        val cleanPlaylistId = playlistId.removePrefix("VL")
        val actions = com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("action", "ACTION_SET_PLAYLIST_NAME")
                addProperty("playlistName", newName)
            })
        }
        val body = JsonObject().apply {
            add("context", webRemixContext())
            addProperty("playlistId", cleanPlaylistId)
            add("actions", actions)
        }
        post("$BASE_URL/browse/edit_playlist", body, ClientType.WEB_REMIX)
    }

    /**
     * Move a song within a YouTube Music playlist.
     */
    suspend fun moveVideoInPlaylist(playlistId: String, setVideoId: String, successorSetVideoId: String?): JsonObject = withContext(Dispatchers.IO) {
        val cleanPlaylistId = playlistId.removePrefix("VL")
        val actions = com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("action", "ACTION_MOVE_VIDEO_BEFORE")
                addProperty("setVideoId", setVideoId)
                successorSetVideoId?.let { addProperty("movedSetVideoIdSuccessor", it) }
            })
        }
        val body = JsonObject().apply {
            add("context", webRemixContext())
            addProperty("playlistId", cleanPlaylistId)
            add("actions", actions)
        }
        post("$BASE_URL/browse/edit_playlist", body, ClientType.WEB_REMIX)
    }

    // ========== Subscriptions ==========

    /**
     * Subscribe to a YouTube Music artist channel.
     */
    suspend fun subscribe(channelId: String): JsonObject = withContext(Dispatchers.IO) {
        val channelIds = com.google.gson.JsonArray().apply { add(channelId) }
        val body = JsonObject().apply {
            add("context", webRemixContext())
            add("channelIds", channelIds)
        }
        post("$BASE_URL/subscription/subscribe", body, ClientType.WEB_REMIX)
    }

    /**
     * Unsubscribe from a YouTube Music artist channel.
     */
    suspend fun unsubscribe(channelId: String): JsonObject = withContext(Dispatchers.IO) {
        val channelIds = com.google.gson.JsonArray().apply { add(channelId) }
        val body = JsonObject().apply {
            add("context", webRemixContext())
            add("channelIds", channelIds)
        }
        post("$BASE_URL/subscription/unsubscribe", body, ClientType.WEB_REMIX)
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    private fun sha1(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val result = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            result[i * 2] = HEX_CHARS[v ushr 4]
            result[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(result)
    }

    enum class ClientType {
        WEB_REMIX,
        WEB,
        MWEB,
        IOS,
        ANDROID,
        ANDROID_VR,
        VISIONOS,
        TVHTML5
    }
}
