package com.mardous.booming.online.data.innertube

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Optimized Local YouTube stream extraction using NewPipe Extractor.
 * Implements multi-level caching, request deduplication, batching, and preference caching.
 */
object NewPipeStreamExtractor {

    private const val TAG = "NewPipeExtractor"
    private var isInitialized = false
    private var appContext: Context? = null

    // === Multi-Level Caching ===
    // Tier 1: Fastest. Just the URL.
    // googlevideo URLs carry a built-in expiry, so cached entries are stamped and treated as
    // misses once stale. Without this, an expired URL is handed to ExoPlayer, fails with 403/404,
    // and costs a full re-resolution round-trip — slower than never having cached it at all.
    private data class CachedUrl(val url: String, val cachedAtMs: Long)
    private val streamUrlCache = LruCache<String, CachedUrl>(500)
    private const val STREAM_URL_TTL_MS = 5 * 60 * 60 * 1000L // 5h, under YouTube's ~6h expiry
    typealias CachedStreamInfo = StreamInfo
    // Tier 2: Slowest to fetch, but fast to re-parse.
    private val streamInfoCache = LruCache<String, CachedStreamInfo>(200)
    // Tier 3: Parsed UI data.
    private val playerResultCache = LruCache<String, PlayerResult>(200)

    /** Reads a cached stream URL, treating anything past its TTL as absent. */
    private fun getFreshCachedUrl(videoId: String): String? {
        val entry = streamUrlCache.get(videoId) ?: return null
        if (System.currentTimeMillis() - entry.cachedAtMs >= STREAM_URL_TTL_MS) {
            streamUrlCache.remove(videoId)
            return null
        }
        return entry.url
    }

    private val cacheLock = Any()

    // === Request Deduplication ===
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<String?>>()
    private val inFlightPlayerResults = ConcurrentHashMap<String, Deferred<PlayerResult?>>()

    // Dedicated dispatcher with bounded concurrency so parallel prefetches never starve each other
    // or exhaust system threads. Reuses Dispatchers.IO worker pool instead of allocating static OS threads.
    private val extractorDispatcher = Dispatchers.IO.limitedParallelism(6)
    private val extractorScope = CoroutineScope(SupervisorJob() + extractorDispatcher)

    // === Preference Caching ===
    @Volatile
    private var cachedPrefTriple: Triple<String, Boolean, Boolean> = Triple("High", false, true)
    @Volatile
    private var lastPrefReadTimestamp: Long = 0
    private const val PREF_CACHE_TTL_MS = 60000L // 1 minute

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureInitialized()
    }

    private fun ensureInitialized() {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                NewPipe.init(NewPipeDownloader.getInstance())
                isInitialized = true
                Log.d(TAG, "NewPipe Extractor initialized successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize NewPipe Extractor", e)
            }
        }
    }

    fun getAppContext(): Context? = appContext

    fun getPreferencesSnapshot(): Triple<String, Boolean, Boolean> {
        val now = System.currentTimeMillis()
        if (now - lastPrefReadTimestamp < PREF_CACHE_TTL_MS) {
            return cachedPrefTriple
        }
        
        val prefs = try {
            appContext?.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
        } catch (_: Exception) { null }
        
        val quality = prefs?.getString("audio_quality", "High") ?: "High"
        val dataSaver = prefs?.getBoolean("data_saver", false) ?: false
        val forceOpus = prefs?.getBoolean("force_opus", true) ?: true
        
        val snapshot = Triple(quality, dataSaver, forceOpus)
        cachedPrefTriple = snapshot
        lastPrefReadTimestamp = now
        return snapshot
    }

    fun clearCache() {
        streamUrlCache.evictAll()
        streamInfoCache.evictAll()
        playerResultCache.evictAll()
        lastPrefReadTimestamp = 0
    }

    fun clearAllCaches() {
        clearCache()
        YouTubeMusic.clearCache()
    }

    fun clearVideoCache(videoId: String) {
        streamUrlCache.remove(videoId)
        streamInfoCache.remove(videoId)
        playerResultCache.remove(videoId)
    }

    /**
     * Internal generic fetcher that populates the streamInfoCache.
     */
    internal suspend fun fetchStreamInfo(videoId: String): StreamInfo? {
        ensureInitialized()
        val cachedInfo = synchronized(cacheLock) { streamInfoCache.get(videoId) }
        if (cachedInfo != null) return cachedInfo

        return try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val infoStart = System.currentTimeMillis()
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            Log.d(TAG, "StreamInfo.getInfo took ${System.currentTimeMillis() - infoStart}ms for $videoId")
            synchronized(cacheLock) { streamInfoCache.put(videoId, info) }
            info
        } catch (e: Throwable) {
            Log.e(TAG, "Stream extraction failed for $videoId: ${e.message}")
            null
        }
    }

    fun isOpusStream(stream: AudioStream): Boolean {
        val itag = stream.itag
        if (itag == 251 || itag == 250 || itag == 249) return true

        val fmt = stream.format
        if (fmt == org.schabi.newpipe.extractor.MediaFormat.WEBMA_OPUS ||
            fmt == org.schabi.newpipe.extractor.MediaFormat.OPUS ||
            fmt == org.schabi.newpipe.extractor.MediaFormat.WEBMA) {
            return true
        }

        val codec = stream.codec
        if (codec != null && codec.contains("opus", ignoreCase = true)) return true

        val mime = fmt?.mimeType
        return mime != null && (mime.contains("opus", ignoreCase = true) || mime.contains("webm", ignoreCase = true))
    }

    fun getNormalizedBitrateKbps(stream: AudioStream): Int {
        val b1 = stream.bitrate
        val b2 = stream.averageBitrate
        val kbps1 = when {
            b1 >= 10000 -> b1 / 1000
            b1 in 16..1000 -> b1
            else -> 0
        }
        val kbps2 = when {
            b2 >= 10000 -> b2 / 1000
            b2 in 16..1000 -> b2
            else -> 0
        }
        val maxKbps = maxOf(kbps1, kbps2)
        if (maxKbps > 0) return maxKbps
        return when (stream.itag) {
            251 -> 160
            141 -> 256
            140 -> 128
            250 -> 70
            249 -> 50
            139 -> 48
            else -> 128
        }
    }

    private fun getStreamScore(stream: AudioStream, preferOpus: Boolean): Long {
        val itag = stream.itag
        val isOpus = isOpusStream(stream)
        val kbps = getNormalizedBitrateKbps(stream)

        val rank = if (preferOpus) {
            when {
                itag == 251 || (isOpus && kbps >= 140) -> 5
                itag == 141 || (!isOpus && kbps >= 200) -> 4
                itag == 140 || (!isOpus && kbps >= 120) -> 3
                isOpus -> 2
                else -> 1
            }
        } else {
            val isAac = itag == 141 || itag == 140 || itag == 139 ||
                        stream.format == org.schabi.newpipe.extractor.MediaFormat.M4A ||
                        (stream.codec != null && stream.codec.contains("mp4a", ignoreCase = true))
            when {
                itag == 141 || (!isOpus && kbps >= 200) -> 5
                isAac -> 4
                itag == 251 || (isOpus && kbps >= 140) -> 3
                isOpus -> 2
                else -> 1
            }
        }
        // Pack (rank: 32 bits, kbps: 32 bits) into a 64-bit Long for zero-allocation single-pass max comparison
        return (rank.toLong() shl 32) or (kbps.toLong() and 0xFFFFFFFFL)
    }

    private fun selectBestStream(
        audioStreams: List<AudioStream>,
        forceHighQuality: Boolean = false,
        overridePreferOpus: Boolean? = null
    ): AudioStream? {
        if (audioStreams.isEmpty()) return null

        val (audioQuality, dataSaver, forceOpus) = getPreferencesSnapshot()
        val isDataSaver = dataSaver || audioQuality.equals("Data Saver", ignoreCase = true) || audioQuality.equals("Low", ignoreCase = true)
        val preferOpus = overridePreferOpus ?: (!isDataSaver && (forceHighQuality || forceOpus || !audioQuality.equals("AAC Only", ignoreCase = true)))

        return if (isDataSaver) {
            audioStreams.minByOrNull { getNormalizedBitrateKbps(it) }
        } else {
            audioStreams.maxByOrNull { getStreamScore(it, preferOpus) }
        }
    }

    fun getFormatTagForStream(stream: AudioStream): String {
        val itag = stream.itag
        val isOpus = isOpusStream(stream)
        val codec = when {
            isOpus -> "OPUS"
            itag == 141 || itag == 140 || itag == 139 ||
            stream.format == org.schabi.newpipe.extractor.MediaFormat.M4A ||
            (stream.codec != null && stream.codec.contains("mp4a", ignoreCase = true)) -> "AAC"
            stream.format == org.schabi.newpipe.extractor.MediaFormat.MP3 ||
            (stream.codec != null && stream.codec.contains("mp3", ignoreCase = true)) -> "MP3"
            stream.format == org.schabi.newpipe.extractor.MediaFormat.FLAC ||
            (stream.codec != null && stream.codec.contains("flac", ignoreCase = true)) -> "FLAC"
            else -> {
                val mime = stream.format?.mimeType ?: ""
                if (mime.contains("opus", ignoreCase = true) || mime.contains("webm", ignoreCase = true)) "OPUS"
                else if (mime.contains("mp4", ignoreCase = true) || mime.contains("aac", ignoreCase = true)) "AAC"
                else "AUDIO"
            }
        }
        val kbpsVal = getNormalizedBitrateKbps(stream)
        return if (kbpsVal > 0) "$codec • $kbpsVal kbps" else codec
    }

    /**
     * Extract the best audio stream URL and its resolved format tag for a YouTube video.
     */
    suspend fun getStreamWithFormat(
        videoId: String?,
        forceHighQuality: Boolean = false,
        preferOpus: Boolean? = null
    ): Pair<String, String>? = coroutineScope {
        if (videoId.isNullOrEmpty()) return@coroutineScope null

        if (!forceHighQuality) {
            val cachedUrl = synchronized(cacheLock) { getFreshCachedUrl(videoId) }
            val cachedTag = YouTubeMusic.getResolvedFormatTag(videoId)
            if (cachedUrl != null) {
                val isOpusCached = cachedTag?.contains("OPUS", ignoreCase = true) == true
                if (preferOpus == null || (preferOpus && isOpusCached) || (!preferOpus && !isOpusCached)) {
                    Log.d(TAG, "Cache HIT (URL) for $videoId ($cachedTag)")
                    return@coroutineScope cachedUrl to (cachedTag ?: "")
                }
            }
        }

        val deferred = inFlightRequests.computeIfAbsent(videoId) {
            extractorScope.async {
                try {
                    val start = System.currentTimeMillis()
                    val info = fetchStreamInfo(videoId)
                    if (info == null) {
                        return@async null
                    }

                    var bestUrl: String? = null
                    var formatTag = ""
                    val audioStreams = info.audioStreams
                    if (audioStreams.isEmpty()) {
                        Log.w(TAG, "No audio-only streams found for $videoId, trying muxed video/audio streams")
                        val anyMuxed = info.videoStreams.firstOrNull()
                        bestUrl = anyMuxed?.content
                        val bitrate = anyMuxed?.bitrate ?: 0
                        val kbps = if (bitrate > 0) "${bitrate / 1000} kbps" else ""
                        formatTag = if (kbps.isNotEmpty()) "AAC • $kbps" else "AAC"
                    } else {
                        val best = selectBestStream(audioStreams, forceHighQuality = forceHighQuality, overridePreferOpus = preferOpus)
                        bestUrl = best?.content
                        if (best != null) {
                            formatTag = getFormatTagForStream(best)
                        }
                    }

                    val durationMs = System.currentTimeMillis() - start
                    if (bestUrl != null) {
                        if (audioStreams.isNotEmpty() || YouTubeMusic.getResolvedFormatTag(videoId).isNullOrEmpty()) {
                            YouTubeMusic.setResolvedFormatTag(videoId, formatTag)
                        }
                        if (!forceHighQuality) {
                            synchronized(cacheLock) {
                                streamUrlCache.put(videoId, CachedUrl(bestUrl, System.currentTimeMillis()))
                            }
                        }
                    }
                    Log.d(TAG, "Stream extraction took ${durationMs}ms for $videoId ($formatTag)")
                    bestUrl
                } catch (e: Throwable) {
                    Log.e(TAG, "Extraction failed: ${e.message}", e)
                    null
                } finally {
                    inFlightRequests.remove(videoId)
                }
            }
        }

        val url = deferred.await() ?: return@coroutineScope null
        val tag = YouTubeMusic.getResolvedFormatTag(videoId) ?: ""
        return@coroutineScope url to tag
    }

    /**
     * Extract the best audio stream URL for a YouTube video.
     */
    suspend fun getStreamUrl(videoId: String?, forceHighQuality: Boolean = false): String? {
        return getStreamWithFormat(videoId, forceHighQuality)?.first
    }

    /**
     * Extract full stream info including metadata lazily.
     */
    suspend fun getPlayerResult(videoId: String?): PlayerResult? = coroutineScope {
        if (videoId.isNullOrEmpty()) return@coroutineScope null
        
        val cachedResult = synchronized(cacheLock) { playerResultCache.get(videoId) }
        if (cachedResult != null) {
            Log.d(TAG, "Cache HIT (PlayerResult) for $videoId")
            return@coroutineScope cachedResult
        }

        val deferred = inFlightPlayerResults.computeIfAbsent(videoId) {
            extractorScope.async {
                try {
                    val info = fetchStreamInfo(videoId)
                    if (info == null) return@async null
                    
                    val audioStreams = info.audioStreams
                    if (audioStreams.isEmpty()) return@async null

                    val bestStream = selectBestStream(audioStreams) ?: return@async null
                    val formatTag = getFormatTagForStream(bestStream)
                    YouTubeMusic.setResolvedFormatTag(videoId, formatTag)
                    
                    val creationStart = System.currentTimeMillis()
                    val streams = listOf(
                        StreamData(
                            url = bestStream.content,
                            mimeType = if (isOpusStream(bestStream)) "audio/webm" else (bestStream.format?.mimeType ?: "audio/webm"),
                            bitrate = maxOf(bestStream.bitrate, bestStream.averageBitrate),
                            contentLength = null,
                            audioQuality = when {
                                maxOf(bestStream.bitrate, bestStream.averageBitrate) >= 256 -> "AUDIO_QUALITY_HIGH"
                                maxOf(bestStream.bitrate, bestStream.averageBitrate) >= 128 -> "AUDIO_QUALITY_MEDIUM"
                                else -> "AUDIO_QUALITY_LOW"
                            }
                        )
                    )

                    val thumbStart = System.currentTimeMillis()
                    val thumbnailUrl = info.thumbnails.lastOrNull()?.url?.let {
                        InnerTubeParser.getHighResThumbnailUrl(it)
                    }
                    Log.d(TAG, "Thumbnail extraction took ${System.currentTimeMillis() - thumbStart}ms for $videoId")

                    val likesFormatted = try {
                        val likeCount = info.likeCount
                        if (likeCount > 0) formatLikesCount(likeCount) else null
                    } catch (_: Exception) {
                        null
                    }

                    val result = PlayerResult(
                        videoId = videoId,
                        title = info.name ?: "Unknown",
                        artist = info.uploaderName ?: "Unknown Artist",
                        thumbnailUrl = thumbnailUrl,
                        durationMs = info.duration * 1000,
                        streams = streams,
                        likesCount = likesFormatted
                    )
                    Log.d(TAG, "PlayerResult creation took ${System.currentTimeMillis() - creationStart}ms for $videoId")
                    synchronized(cacheLock) {
                        playerResultCache.put(videoId, result)
                        // Also seed the URL cache while we're at it!
                        streamUrlCache.put(videoId, CachedUrl(bestStream.content, System.currentTimeMillis()))
                    }
                    
                    result
                } finally {
                    inFlightPlayerResults.remove(videoId)
                }
            }
        }

        return@coroutineScope deferred.await()
    }

    /**
     * Dedicated extraction for high-quality offline downloads.
     */
    suspend fun getHighQualityPlayerResult(videoId: String?): PlayerResult? = coroutineScope {
        if (videoId.isNullOrEmpty()) return@coroutineScope null
        val info = fetchStreamInfo(videoId) ?: return@coroutineScope null
        val audioStreams = info.audioStreams
        if (audioStreams.isEmpty()) return@coroutineScope null

        val bestStream = selectBestStream(audioStreams, forceHighQuality = true) ?: return@coroutineScope null
        val streams = listOf(
            StreamData(
                url = bestStream.content,
                mimeType = if (isOpusStream(bestStream)) "audio/webm" else (bestStream.format?.mimeType ?: "audio/webm"),
                bitrate = maxOf(bestStream.bitrate, bestStream.averageBitrate),
                contentLength = null,
                audioQuality = "AUDIO_QUALITY_HIGH"
            )
        )

        val thumbnailUrl = info.thumbnails.lastOrNull()?.url?.let {
            InnerTubeParser.getHighResThumbnailUrl(it)
        }

        PlayerResult(
            videoId = videoId,
            title = info.name ?: "Unknown",
            artist = info.uploaderName ?: "Unknown Artist",
            thumbnailUrl = thumbnailUrl,
            durationMs = info.duration * 1000,
            streams = streams,
            likesCount = null
        )
    }

    /**
     * Batch extraction with controlled concurrency.
     */
    suspend fun extractBatch(videoIds: List<String>, batchSize: Int = 6): Map<String, String> = coroutineScope {
        if (videoIds.isEmpty()) return@coroutineScope emptyMap()
        val results = ConcurrentHashMap<String, String>(videoIds.size)

        videoIds.chunked(batchSize).forEach { batch ->
            val deferreds = batch.map { id ->
                async {
                    val url = getStreamUrl(id)
                    if (url != null) results[id] = url
                }
            }
            deferreds.awaitAll()
        }

        return@coroutineScope results
    }

    enum class PrefetchPriority { LOW, MEDIUM }

    /**
     * Prefetch a batch of items in the background.
     */
    fun prefetchBatch(videoIds: List<String>, priority: PrefetchPriority = PrefetchPriority.LOW) {
        val size = when (priority) {
            PrefetchPriority.LOW    -> 3
            PrefetchPriority.MEDIUM -> 6
        }
        extractorScope.launch {
            extractBatch(videoIds, batchSize = size)
        }
    }

    /**
     * Warm both the URL cache AND the full PlayerResult cache for a list of video IDs
     * in parallel. A single [getPlayerResult] call seeds both caches, so this is more
     * efficient than calling [prefetchBatch] (URL-only) for every item.
     *
     * Call this whenever you know the next 2-3 songs (e.g. at queue load time or
     * after a track transition).
     */
    fun warmCache(videoIds: List<String>) {
        val uncached = videoIds.filter { id ->
            playerResultCache.get(id) == null && streamUrlCache.get(id) == null
        }
        if (uncached.isEmpty()) return
        extractorScope.launch {
            uncached.take(6).map { id ->
                async {
                    try { getPlayerResult(id) } catch (_: Throwable) {}
                }
            }.awaitAll()
        }
    }

    private fun formatLikesCount(count: Long): String {
        if (count <= 0) return "0"
        if (count < 1000) return count.toString()
        if (count < 1000000) {
            val whole = count / 1000
            val rem = (count % 1000) / 100
            return if (whole >= 100 || rem == 0L) "${whole}K" else "${whole}.${rem}K"
        }
        val whole = count / 1000000
        val rem = (count % 1000000) / 100000
        return if (whole >= 100 || rem == 0L) "${whole}M" else "${whole}.${rem}M"
    }
}
