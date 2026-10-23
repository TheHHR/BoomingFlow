@file:OptIn(DelicateCoroutinesApi::class)
@file:Suppress("DeferredResultUnused")

package com.mardous.booming.online.data.innertube

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * High-level YouTube Music API facade.
 * Provides clean, type-safe methods for all YouTube Music operations.
 * This is the main entry point for online music features.
 */
object YouTubeMusic {

    private const val TAG = "YouTubeMusic"

    // Intelligent Streaming Performance Caches
    private val streamUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val activeStreamRequests = ConcurrentHashMap<String, Deferred<Result<String>>>()
    private val resolvedFormatTags = ConcurrentHashMap<String, String>()
    private val resolvedProviders = ConcurrentHashMap<String, String>()
    private const val CACHE_TTL = 6 * 60 * 60 * 1000L // Standard 6 Hour TTL

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun clearCache() {
        streamUrlCache.clear()
        resolvedFormatTags.clear()
        resolvedProviders.clear()
    }

    fun clearVideoCache(videoId: String) {
        streamUrlCache.remove(videoId)
        streamUrlCache.entries.removeIf { it.key.startsWith("$videoId:") }
        resolvedFormatTags.remove(videoId)
        resolvedProviders.remove(videoId)
    }

    fun getResolvedFormatTag(videoId: String): String? = resolvedFormatTags[videoId]

    fun setResolvedFormatTag(videoId: String, tag: String) {
        resolvedFormatTags[videoId] = tag
    }

    fun getResolvedProvider(videoId: String): String? = resolvedProviders[videoId]

    fun setResolvedProvider(videoId: String, provider: String) {
        resolvedProviders[videoId] = provider
    }

    private fun sanitizeStreamUrl(rawUrl: String): String {
        if (!rawUrl.contains("range=") && !rawUrl.contains("rn=")) return rawUrl
        // Use non-destructive regex replacement to remove range/rn query parameters.
        // Never use android.net.Uri.parse().buildUpon().clearQuery() because it decodes
        // and re-encodes query params, corrupting cryptographic signatures (sig, lsig, sparams).
        return rawUrl
            .replace(Regex("&range=[^&]*"), "")
            .replace(Regex("\\?range=[^&]*&"), "?")
            .replace(Regex("\\?range=[^&]*$"), "")
            .replace(Regex("&rn=[^&]*"), "")
            .replace(Regex("\\?rn=[^&]*&"), "?")
            .replace(Regex("\\?rn=[^&]*$"), "")
    }

    private fun cleanExpiredCache() {
        val now = System.currentTimeMillis()
        streamUrlCache.entries.removeIf { it.value.second < now }
    }

    /**
     * Proactively resolve and cache the streaming URL in the background.
     * Eliminates click-to-play lag entirely for predicted next tracks.
     */
    @JvmStatic
    fun prefetch(videoId: String?) {
        if (videoId.isNullOrEmpty()) return
        // Fire-and-forget resolution call leveraging our deduplicated cache chain
        appScope.launch {
            try {
                getStreamUrl(videoId)
                Log.d(TAG, "Successfully prefetched stream cache for: $videoId")
            } catch (_: Exception) {
                // Prefetch failures are non-critical, silent ignore
            }
        }
    }

    // ========== Search ==========

    /**
     * Search YouTube Music for songs, albums, artists, playlists.
     * Returns mixed results (all types) when no filter is specified.
     */
    suspend fun search(query: String, filter: SearchFilter? = null): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(7000L.milliseconds) {
                val response = InnerTubeClient.search(query, filter?.value)
                withContext(Dispatchers.Default) {
                    InnerTubeParser.parseSearchResponse(response)
                }
            }
        }
    }

    /**
     * Load more search results using continuation token.
     */
    suspend fun searchContinuation(continuation: String): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(7000L.milliseconds) {
                val response = InnerTubeClient.search("", continuation = continuation)
                withContext(Dispatchers.Default) {
                    InnerTubeParser.parseSearchResponse(response)
                }
            }
        }
    }

    /**
     * Get search suggestions as user types.
     */
    suspend fun searchSuggestions(query: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(2500L.milliseconds) {
                val response = InnerTubeClient.searchSuggestions(query)
                val suggestions = mutableListOf<String>()

                val contents = response.path("contents")?.asJsonArray
                contents?.forEach { section ->
                    val renderer = section.asJsonObject
                        .getAsJsonObject("searchSuggestionsSectionRenderer")
                    val sectionContents = renderer?.getAsJsonArray("contents")
                    sectionContents?.forEach { item ->
                        val suggestion = item.asJsonObject
                            .getAsJsonObject("searchSuggestionRenderer")
                            ?.path("suggestion.runs")?.asJsonArray
                            ?.joinToString("") { it.asJsonObject.get("text")?.asString ?: "" }
                        suggestion?.let { suggestions.add(it) }
                    }
                }
                suggestions
            }
        }
    }

    // ========== Player & Streaming ==========

    suspend fun player(videoId: String?): Result<PlayerResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (videoId.isNullOrEmpty()) throw IllegalArgumentException("Invalid videoId")

            cleanExpiredCache()

            coroutineScope {
                val newPipeDeferred = async(Dispatchers.IO) {
                    try {
                        NewPipeStreamExtractor.getPlayerResult(videoId)
                    } catch (e: Exception) {
                        Log.e("YouTubeTelemetry", "NewPipe stream extraction failed for $videoId: ${e.message}", e)
                        null
                    }
                }

                val trackingDeferred = async(Dispatchers.IO) {
                    var pbUrl: String? = null
                    var wtUrl: String? = null
                    if (!InnerTubeClient.cookie.isNullOrBlank()) {
                        try {
                            val playerJson = InnerTubeClient.playerWebRemix(videoId)
                            Log.d("YouTubeTelemetry", "playerWebRemix keys: ${playerJson.keySet()}")
                            if (playerJson.has("playabilityStatus")) {
                                Log.d("YouTubeTelemetry", "playabilityStatus: ${playerJson.getAsJsonObject("playabilityStatus")}")
                            }
                            val tracking = playerJson.getAsJsonObject("playbackTracking")
                            pbUrl = tracking?.getAsJsonObject("videostatsPlaybackUrl")?.get("baseUrl")?.asString
                            wtUrl = tracking?.getAsJsonObject("videostatsWatchtimeUrl")?.get("baseUrl")?.asString
                            Log.d("YouTubeTelemetry", "Fetched watch history URLs from WebRemix player: playbackUrl=$pbUrl, watchtimeUrl=$wtUrl")
                        } catch (e: Exception) {
                            Log.e("YouTubeTelemetry", "Failed to fetch watch history URLs from WebRemix player: ${e.message}", e)
                        }
                    }
                    Pair(pbUrl, wtUrl)
                }

                val newPipeResult = newPipeDeferred.await()
                val (playbackUrl, watchtimeUrl) = trackingDeferred.await()

                newPipeResult?.copy(
                    playbackUrl = playbackUrl,
                    watchtimeUrl = watchtimeUrl
                ) ?: PlayerResult(
                    videoId = videoId,
                    title = "Unknown",
                    artist = "Unknown Artist",
                    thumbnailUrl = null,
                    durationMs = 0,
                    streams = emptyList(),
                    likesCount = null,
                    playbackUrl = playbackUrl,
                    watchtimeUrl = watchtimeUrl
                )
            }
        }
    }

    /**
     * Atomically acquires the best audio stream URL for a given video ID.
     * Implements absolute deduped parallelism prevention and automatic caching.
     */
    suspend fun getStreamUrl(
        videoId: String?,
        forceHighQuality: Boolean = false,
        title: String? = null,
        artist: String? = null,
        isrc: String? = null,
        overridePreferOpus: Boolean? = null,
        isRetry: Boolean = false,
        durationMs: Long? = null
    ): Result<String> = coroutineScope {
        if (videoId.isNullOrEmpty()) return@coroutineScope Result.failure(IllegalArgumentException("Video ID null"))

        cleanExpiredCache()
        
        val (audioQuality, dataSaver, forceOpus) = NewPipeStreamExtractor.getPreferencesSnapshot()
        val isDataSaver = dataSaver || audioQuality.equals("Data Saver", ignoreCase = true) || audioQuality.equals("Low", ignoreCase = true)
        val isAacOnly = audioQuality.equals("AAC Only", ignoreCase = true)
        val preferOpus = overridePreferOpus ?: (!isDataSaver && !isAacOnly)
        val cacheKey = "$videoId:$audioQuality:${if (preferOpus) "opus" else "aac"}${if (isRetry) ":retry" else ""}"

        // Check Level 1 Cache: Instant memory map retrieval (bypassed on retry)
        if (!forceHighQuality && !isRetry) {
            val cached = streamUrlCache[cacheKey] ?: (if (overridePreferOpus == null) streamUrlCache[videoId] else null)
            if (cached != null && cached.second > System.currentTimeMillis()) {
                val tag = resolvedFormatTags[videoId] ?: "Cached"
                com.mardous.booming.online.util.ConsoleLogger.stream("Stream Cache HIT for $videoId -> $tag")
                return@coroutineScope Result.success(cached.first)
            }
        }

        // Level 2: Check for in-flight request duplication prevention
        // Atomically compute or retrieve an active coroutine future for this exact stream.
        val activeTask = activeStreamRequests.computeIfAbsent(cacheKey) { _ ->
            appScope.async {
                try {
                    var bestUrl: String? = null
                    var formatTag: String? = null

                    // Resolve song title & artist if missing
                    var resolvedTitle = title?.takeIf { it.isNotBlank() }
                    var resolvedArtist = artist?.takeIf { it.isNotBlank() }
                    var resolvedDurationSeconds: Long? = durationMs?.takeIf { it > 0 }?.let { it / 1000L }
                    if (resolvedTitle == null || resolvedDurationSeconds == null) {
                        try {
                            val streamInfo = NewPipeStreamExtractor.fetchStreamInfo(videoId)
                            if (streamInfo != null) {
                                if (resolvedTitle == null) resolvedTitle = streamInfo.name
                                if (resolvedArtist == null) resolvedArtist = streamInfo.uploaderName
                                if (resolvedDurationSeconds == null && streamInfo.duration > 0) {
                                    resolvedDurationSeconds = streamInfo.duration
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Failed to resolve streamInfo for metadata: ${e.message}")
                        }
                    }

                    com.mardous.booming.online.util.ConsoleLogger.stream("Resolving stream for $videoId (title=\"${resolvedTitle ?: "unknown"}\", artist=\"${resolvedArtist ?: "unknown"}\", duration=${resolvedDurationSeconds ?: 0}s, isRetry=$isRetry)...")

                    // 1. Attempt Lossless / Hi-Fi stream resolution via MLS, Qobuz, or JioSaavn (bypassed on retry)

                    // 2. Fallback to NewPipe YouTube stream (Opus / AAC)
                    if (bestUrl.isNullOrEmpty()) {
                        com.mardous.booming.online.util.ConsoleLogger.stream("Resolving via YouTube NewPipe for $videoId (preferOpus=$preferOpus, forceHQ=$forceHighQuality)...")
                        Log.d(TAG, "Resolving stream for $videoId via NewPipe (preferOpus=$preferOpus, forceHQ=$forceHighQuality)...")
                        try {
                            val result = NewPipeStreamExtractor.getStreamWithFormat(
                                videoId,
                                forceHighQuality = forceHighQuality,
                                preferOpus = preferOpus
                            )
                            if (result != null && !result.first.isNullOrEmpty()) {
                                bestUrl = result.first
                                formatTag = result.second
                                resolvedProviders[videoId] = "YouTube Music (NewPipe Opus/AAC)"
                                Log.d(TAG, "Stream obtained via NewPipe ($formatTag) for $videoId")
                                com.mardous.booming.online.util.ConsoleLogger.stream("Stream obtained via NewPipe ($formatTag) for $videoId")
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "NewPipe stream extraction failed for $videoId: ${e.message}")
                        }
                    }

                    // 3. Tier 3: Direct Native InnerTube Multi-Client Fallback (ANDROID_VR / VISIONOS / IOS / WEB_REMIX)
                    if (bestUrl.isNullOrEmpty()) {
                        try {
                            com.mardous.booming.online.util.ConsoleLogger.stream("Resolving via Native InnerTube Tier 3 for $videoId...")
                            Log.d(TAG, "Resolving via Native InnerTube Tier 3 for $videoId...")
                            val playerJson = InnerTubeClient.player(videoId)
                            val playerResult = InnerTubeParser.parsePlayerResponse(playerJson, parseStreams = true)
                            val fallbackStream = playerResult?.streams?.firstOrNull()?.url ?: playerResult?.playbackUrl
                            if (!fallbackStream.isNullOrEmpty()) {
                                bestUrl = fallbackStream
                                formatTag = "Native InnerTube • OPUS/AAC"
                                resolvedProviders[videoId] = "YouTube Music (Google InnerTube • CDN Edge)"
                                Log.i(TAG, "Stream obtained via Native InnerTube Tier 3 for $videoId")
                                com.mardous.booming.online.util.ConsoleLogger.stream("Stream obtained via Native InnerTube Tier 3 for $videoId")
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Tier 3 InnerTube fallback failed for $videoId: ${e.message}")
                        }
                    }

                    if (!bestUrl.isNullOrEmpty()) {
                        val finalUrl = sanitizeStreamUrl(bestUrl)
                        if (!formatTag.isNullOrEmpty()) {
                            resolvedFormatTags[videoId] = formatTag
                        }
                        if (!forceHighQuality) {
                            streamUrlCache[cacheKey] = finalUrl to (System.currentTimeMillis() + CACHE_TTL)
                            if (preferOpus) {
                                streamUrlCache[videoId] = finalUrl to (System.currentTimeMillis() + CACHE_TTL)
                            }
                        }
                        com.mardous.booming.online.util.ConsoleLogger.stream("Playback Ready: $videoId -> $formatTag")
                        Result.success(finalUrl)
                    } else {
                        Result.failure(IllegalStateException("Stream extraction total exhaustion"))
                    }
                } catch (e: Throwable) {
                    Result.failure(if (e is Exception) e else RuntimeException(e))
                } finally {
                    // Release request lock immediately upon resolution boundary
                    activeStreamRequests.remove(cacheKey)
                }
            }
        }

        return@coroutineScope activeTask.await()
    }

    /**
     * Extracts YouTube's native 'loudnessDb' parameter for a given video ID.
     * This metric indicates how much the track deviates from YouTube's target of -14 LUFS.
     */
    suspend fun getLoudnessDb(videoId: String): Result<Float> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d("YouTubeMusic", "Fetching loudnessDb for videoId: $videoId via playerWebRemix")
            // Using playerWebRemix (Client 67) instead of player (Android/iOS) to bypass HTTP 400 blocks
            val playerJson = InnerTubeClient.playerWebRemix(videoId)
            val playerConfig = playerJson.getAsJsonObject("playerConfig")
            val audioConfig = playerConfig?.getAsJsonObject("audioConfig")
            val loudnessDb = audioConfig?.get("loudnessDb")?.asFloat
            
            if (loudnessDb == null) {
                Log.w("YouTubeMusic", "Loudness DB not found in playerWebRemix response for $videoId")
                throw Exception("Loudness DB not found in player response")
            } else {
                Log.d("YouTubeMusic", "Successfully fetched loudnessDb: $loudnessDb for $videoId")
                loudnessDb
            }
        }.onFailure { e ->
            Log.e("YouTubeMusic", "Failed to fetch loudnessDb for $videoId: ${e.message}", e)
        }
    }

    // ========== Home Feed ==========

    /**
     * Get the YouTube Music home page with personalized recommendations.
     */
    suspend fun home(): Result<HomePage> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(15000L.milliseconds) {
                val response = InnerTubeClient.browse(browseId = "FEmusic_home")
                withContext(Dispatchers.Default) {
                    InnerTubeParser.parseHomePage(response)
                }
            }
        }
    }

    /**
     * Load more home page sections.
     */
    suspend fun homeContinuation(continuation: String): Result<HomePage> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(15000L.milliseconds) {
                val response = InnerTubeClient.browse(continuation = continuation)
                withContext(Dispatchers.Default) {
                    InnerTubeParser.parseHomePage(response)
                }
            }
        }
    }

    /**
     * Get the Explore/Charts page.
     */
    suspend fun explore(): Result<HomePage> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(15000L.milliseconds) {
                val response = InnerTubeClient.browse(browseId = "FEmusic_explore")
                withContext(Dispatchers.Default) {
                    InnerTubeParser.parseHomePage(response)
                }
            }
        }
    }

    suspend fun moodsAndGenres(): Result<List<HomeSection>> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(15000L.milliseconds) {
                val response = InnerTubeClient.browse(browseId = "FEmusic_moods_and_genres")
                withContext(Dispatchers.Default) {
                    InnerTubeParser.parseMoodsAndGenresPage(response)
                }
            }
        }
    }

    suspend fun moodCategory(browseId: String, params: String?): Result<HomePage> = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeout(15000L.milliseconds) {
                val response = InnerTubeClient.browse(browseId = browseId, params = params)
                withContext(Dispatchers.Default) {
                    InnerTubeParser.parseHomePage(response)
                }
            }
        }
    }

    // ========== Albums ==========

    /**
     * Get an album's details and songs.
     */
    suspend fun album(browseId: String): Result<AlbumPage> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse(browseId = browseId)
            withContext(Dispatchers.Default) {
                InnerTubeParser.parseAlbumPage(response, browseId)
                    ?: throw Exception("Failed to parse album $browseId")
            }
        }
    }

    // ========== Artists ==========

    /**
     * Get an artist's page with songs, albums, etc.
     */
    suspend fun artist(browseId: String): Result<ArtistPage> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse(browseId = browseId)

            // Parse artist header
            val header = response.path("header.musicImmersiveHeaderRenderer")?.asJsonObject
                ?: response.path("header.musicVisualHeaderRenderer")?.asJsonObject
                ?: response.path("header.musicHeaderRenderer")?.asJsonObject

            val title = header?.path("title.runs.0.text")?.asString ?: "Unknown Artist"
            val thumbnail = header?.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                ?: header?.path("foregroundThumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                ?: header?.path("avatar.musicThumbnailRenderer.thumbnail.thumbnails")
                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                ?: response.path("header.musicHeaderRenderer.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                ?: response.path("header.musicHeaderRenderer.avatar.musicThumbnailRenderer.thumbnail.thumbnails")
                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
            val finalThumbnail = InnerTubeParser.getHighResThumbnailUrl(thumbnail)
            val description = header?.path("description.runs.0.text")?.asString

            val subCount = header?.path("subtitle.runs")?.asJsonArray
                ?.joinToString("") { run ->
                    run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                }?.takeIf { it.isNotBlank() }
                ?: header?.path("subscriptionButton.subscribeButtonRenderer.subscriberCountWithConfirm.runs.0.text")?.asString
                ?: header?.path("subscriptionButton.subscribeButtonRenderer.subscriberCountText.runs.0.text")?.asString

            val additionalInfo = header?.path("additionalInfo.runs")?.asJsonArray
                ?.joinToString("") { run ->
                    run.takeIf { run.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
                }?.takeIf { it.isNotBlank() }

            var combinedSubCount = subCount ?: ""
            if (!additionalInfo.isNullOrEmpty()) {
                if (combinedSubCount.isNotEmpty() && !combinedSubCount.contains(additionalInfo)) {
                    combinedSubCount = "$combinedSubCount • $additionalInfo"
                } else if (combinedSubCount.isEmpty()) {
                    combinedSubCount = additionalInfo
                }
            }

            // Regex extraction from description as a solid fallback
            if (description != null && !combinedSubCount.lowercase().contains("monthly")) {
                val regex = Regex("([0-9a-zA-Z.,]+)\\s*(monthly listeners|monthly audience|listeners|audience)", RegexOption.IGNORE_CASE)
                val match = regex.find(description)
                if (match != null) {
                    val count = match.groupValues[1]
                    val label = match.groupValues[2]
                    val suffix = if (label.lowercase().contains("listener")) "Monthly Listeners" else "Monthly Audience"
                    combinedSubCount = if (combinedSubCount.isNotEmpty()) {
                        "$combinedSubCount • $count $suffix"
                    } else {
                        "$count $suffix"
                    }
                }
            }

            // Clean any 'Spotify' prefix and duplicate words
            combinedSubCount = combinedSubCount
                .replace("Spotify", "", ignoreCase = true)
                .replace("Monthly Monthly Listeners", "Monthly Listeners", ignoreCase = true)
                .replace("Monthly Monthly", "Monthly", ignoreCase = true)
                .trim()

            // Extract subscription status
            val isSubscribed = header?.path("subscriptionButton.subscribeButtonRenderer.subscribed")?.asBoolean ?: false

            // Parse content sections (reuse home section parser)
            val homePage = InnerTubeParser.parseHomePage(response)

            ArtistPage(
                artist = OnlineArtist(
                    browseId = browseId,
                    title = title,
                    thumbnailUrl = finalThumbnail,
                    subscriberCount = combinedSubCount.takeIf { it.isNotBlank() },
                    isSubscribed = isSubscribed
                ),
                sections = homePage.sections,
                description = description
            )
        }
    }

    // ========== Playlists ==========

    /**
     * Parses a single OnlineSong from a YouTube Music musicResponsiveListItemRenderer.
     */
    fun parseMusicResponsiveListItem(renderer: JsonObject): OnlineSong? {
        val flexColumns = renderer.getAsJsonArray("flexColumns") ?: return null
        val songTitle = flexColumns.getOrNull(0)?.asJsonObject
            ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs.0.text")?.asString ?: return null
        val videoId = renderer.path("overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer.playNavigationEndpoint.watchEndpoint.videoId")?.asString
            ?: renderer.path("navigationEndpoint.watchEndpoint.videoId")?.asString
            ?: renderer.path("playlistItemData.videoId")?.asString
            ?: flexColumns.getOrNull(0)?.asJsonObject
                ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs.0.navigationEndpoint.watchEndpoint.videoId")?.asString
            ?: return null
        val subtitleRuns = flexColumns.getOrNull(1)?.asJsonObject
            ?.path("musicResponsiveListItemFlexColumnRenderer.text.runs")?.asJsonArray
        val artistText = subtitleRuns?.joinToString("") { run ->
            run.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString ?: ""
        } ?: ""
        val fixedColumns = renderer.getAsJsonArray("fixedColumns")
        val duration = fixedColumns?.getOrNull(0)?.asJsonObject
            ?.path("musicResponsiveListItemFixedColumnRenderer.text.runs.0.text")?.asString
        val songThumbnail = InnerTubeParser.getHighResThumbnailUrl(
            renderer.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
        )
        val artistId = subtitleRuns?.getOrNull(0)?.asJsonObject
            ?.path("navigationEndpoint.browseEndpoint.browseId")?.asString

        return OnlineSong(
            videoId = videoId,
            title = songTitle,
            artist = artistText.trim(),
            duration = duration,
            durationMs = parseDuration(duration),
            thumbnailUrl = songThumbnail,
            artistId = artistId
        )
    }

    /**
     * Helper to extract continuation token across all YouTube Music InnerTube formats:
     * - continuations array on container (nextContinuationData, continuationCommand, reloadContinuationData, etc.)
     * - continuationEndpoint directly on container
     * - continuationItemRenderer inside contents/items array
     * - onResponseReceivedActions (appendContinuationItemsAction / reloadContinuationItemsCommand)
     * - continuationContents (musicPlaylistShelfContinuation, musicShelfContinuation, sectionListContinuation)
     */
    fun extractContinuationToken(
        container: JsonObject? = null,
        contents: JsonArray? = null,
        response: JsonObject? = null
    ): String? {
        if (contents != null && contents.size() > 0) {
            for (i in (contents.size() - 1) downTo 0) {
                val itemObj = contents.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val contItem = itemObj.getAsJsonObject("continuationItemRenderer") ?: continue
                val token = contItem.path("continuationEndpoint.continuationCommand.token")?.asString
                    ?: contItem.path("continuationEndpoint.nextContinuationData.continuation")?.asString
                    ?: contItem.path("continuationEndpoint.browseContinuationEndpoint.continuation")?.asString
                    ?: contItem.path("continuationEndpoint.reloadContinuationData.continuation")?.asString
                    ?: contItem.path("continuationEndpoint.nextRadioContinuationData.continuation")?.asString
                if (!token.isNullOrBlank()) return token
            }
        }

        if (container != null) {
            val continuations = container.get("continuations")?.takeIf { it.isJsonArray }?.asJsonArray
            if (continuations != null && continuations.size() > 0) {
                for (i in 0 until continuations.size()) {
                    val contObj = continuations.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val token = contObj.path("nextContinuationData.continuation")?.asString
                        ?: contObj.path("continuationEndpoint.continuationCommand.token")?.asString
                        ?: contObj.path("reloadContinuationData.continuation")?.asString
                        ?: contObj.path("nextRadioContinuationData.continuation")?.asString
                        ?: contObj.path("continuationEndpoint.browseContinuationEndpoint.continuation")?.asString
                        ?: contObj.path("continuationEndpoint.nextContinuationData.continuation")?.asString
                    if (!token.isNullOrBlank()) return token
                }
            }
            val directToken = container.path("continuationEndpoint.continuationCommand.token")?.asString
                ?: container.path("continuationEndpoint.nextContinuationData.continuation")?.asString
                ?: container.path("continuationEndpoint.browseContinuationEndpoint.continuation")?.asString
            if (!directToken.isNullOrBlank()) return directToken
        }

        if (response != null) {
            val actions = response.getAsJsonArray("onResponseReceivedActions")
            if (actions != null && actions.size() > 0) {
                for (i in 0 until actions.size()) {
                    val action = actions.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val items = action.path("appendContinuationItemsAction.continuationItems")?.asJsonArray
                        ?: action.path("reloadContinuationItemsCommand.continuationItems")?.asJsonArray
                    if (items != null) {
                        val token = extractContinuationToken(contents = items)
                        if (!token.isNullOrBlank()) return token
                    }
                }
            }

            val contContents = response.getAsJsonObject("continuationContents")
            if (contContents != null) {
                for ((_, shelfElement) in contContents.entrySet()) {
                    val shelf = shelfElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val token = extractContinuationToken(container = shelf, contents = shelf.getAsJsonArray("contents"))
                    if (!token.isNullOrBlank()) return token
                }
            }
        }

        return null
    }

    /**
     * Get a playlist's details and songs.
     */
    suspend fun playlist(playlistId: String, forceRefresh: Boolean = false): Result<PlaylistPage> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBrowseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            val response = InnerTubeClient.browse(browseId = cleanBrowseId)

            val header = response.path(
                "contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicResponsiveHeaderRenderer"
            )?.asJsonObject
                ?: response.path(
                    "contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicEditablePlaylistDetailHeaderRenderer.header.musicResponsiveHeaderRenderer"
                )?.asJsonObject
                ?: response.path(
                    "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicResponsiveHeaderRenderer"
                )?.asJsonObject
                ?: response.path(
                    "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicEditablePlaylistDetailHeaderRenderer.header.musicResponsiveHeaderRenderer"
                )?.asJsonObject
                ?: response.path("header.musicResponsiveHeaderRenderer")?.asJsonObject
                ?: response.path("header.musicEditablePlaylistDetailHeaderRenderer.header.musicResponsiveHeaderRenderer")?.asJsonObject
                ?: response.path("header.musicDetailHeaderRenderer")?.asJsonObject

            val title = header?.path("title.runs.0.text")?.asString ?: "Playlist"
            val authorRun = header?.path("straplineTextOne.runs.0")?.asJsonObject
            val author = authorRun?.let {
                OnlineArtistRef(
                    name = it.get("text")?.asString ?: "",
                    id = it.path("navigationEndpoint.browseEndpoint.browseId")?.asString
                )
            }
            val thumbnail = InnerTubeParser.getHighResThumbnailUrl(
                header?.path("thumbnail.musicThumbnailRenderer.thumbnail.thumbnails")
                    ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
            )
            val songCount = header?.path("secondSubtitle.runs.0.text")?.asString

            // Parse songs: check primary known paths first
            var playlistShelf = response.path(
                "contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents.0.musicPlaylistShelfRenderer"
            )?.asJsonObject
                ?: response.path(
                    "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.0.musicPlaylistShelfRenderer"
                )?.asJsonObject
                ?: response.path(
                    "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents.1.musicPlaylistShelfRenderer"
                )?.asJsonObject

            // Fallback: search sectionListContents for musicPlaylistShelfRenderer
            if (playlistShelf == null) {
                val sectionListContents = response.path(
                    "contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer.contents"
                )?.asJsonArray
                    ?: response.path(
                        "contents.singleColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents"
                    )?.asJsonArray
                    ?: response.path(
                        "contents.twoColumnBrowseResultsRenderer.tabs.0.tabRenderer.content.sectionListRenderer.contents"
                    )?.asJsonArray
                    ?: response.path("contents.sectionListRenderer.contents")?.asJsonArray

                if (sectionListContents != null) {
                    for (i in 0 until sectionListContents.size()) {
                        val obj = sectionListContents.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                        val shelf = obj.getAsJsonObject("musicPlaylistShelfRenderer")
                        if (shelf != null) {
                            playlistShelf = shelf
                            break
                        }
                    }
                    if (playlistShelf == null) {
                        for (i in 0 until sectionListContents.size()) {
                            val obj = sectionListContents.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                            val shelf = obj.getAsJsonObject("musicShelfRenderer")
                            if (shelf != null) {
                                playlistShelf = shelf
                                break
                            }
                        }
                    }
                }
            }

            val songContents = playlistShelf?.getAsJsonArray("contents")
            val songs = mutableListOf<OnlineSong>()
            songContents?.forEach { item ->
                val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val renderer = obj.getAsJsonObject("musicResponsiveListItemRenderer") ?: return@forEach
                parseMusicResponsiveListItem(renderer)?.let { songs.add(it) }
            }

            val currentContinuation = extractContinuationToken(container = playlistShelf, contents = songContents, response = response)

            PlaylistPage(
                playlist = OnlinePlaylist(
                    playlistId = playlistId,
                    title = title,
                    author = author,
                    songCount = songCount ?: "${songs.size} songs",
                    thumbnailUrl = thumbnail
                ),
                songs = songs,
                continuation = currentContinuation
            )
        }
    }

    /**
     * Load more songs from a playlist continuation token.
     * Handles onResponseReceivedActions (standard),
     * musicPlaylistShelfContinuation, musicShelfContinuation, and sectionListContinuation.
     */
    suspend fun playlistContinuation(continuation: String): Result<Pair<List<OnlineSong>, String?>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse(continuation = continuation)
            val songs = mutableListOf<OnlineSong>()
            val rawItems = mutableListOf<JsonObject>()

            // 1. Check onResponseReceivedActions (Standard modern YouTube Music format)
            val actions = response.getAsJsonArray("onResponseReceivedActions")
            if (actions != null && actions.size() > 0) {
                for (i in 0 until actions.size()) {
                    val action = actions.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val continuationItems = action.path("appendContinuationItemsAction.continuationItems")?.asJsonArray
                        ?: action.path("reloadContinuationItemsCommand.continuationItems")?.asJsonArray
                    if (continuationItems != null) {
                        for (j in 0 until continuationItems.size()) {
                            val item = continuationItems.get(j).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                            rawItems.add(item)
                        }
                    }
                }
            }

            // 2. Check continuationContents (Legacy / alternate format)
            val continuationContents = response.getAsJsonObject("continuationContents")
            val continuationShelf = continuationContents?.getAsJsonObject("musicPlaylistShelfContinuation")
                ?: continuationContents?.getAsJsonObject("musicShelfContinuation")

            val shelfContents = continuationShelf?.getAsJsonArray("contents")
                ?: continuationShelf?.getAsJsonArray("items")

            if (shelfContents != null) {
                for (i in 0 until shelfContents.size()) {
                    val item = shelfContents.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    rawItems.add(item)
                }
            }

            // 3. Check sectionListContinuation
            val sectionListCont = continuationContents?.getAsJsonObject("sectionListContinuation")
            val sectionContents = sectionListCont?.getAsJsonArray("contents")
            if (sectionContents != null) {
                for (i in 0 until sectionContents.size()) {
                    val sec = sectionContents.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val shelf = sec.getAsJsonObject("musicPlaylistShelfRenderer")
                        ?: sec.getAsJsonObject("musicShelfRenderer")
                    val items = shelf?.getAsJsonArray("contents")
                    if (items != null) {
                        for (j in 0 until items.size()) {
                            val item = items.get(j).takeIf { it.isJsonObject }?.asJsonObject ?: continue
                            rawItems.add(item)
                        }
                    }
                }
            }

            var nextContinuation: String? = null

            for (item in rawItems) {
                val contItem = item.getAsJsonObject("continuationItemRenderer")
                if (contItem != null) {
                    val token = contItem.path("continuationEndpoint.continuationCommand.token")?.asString
                        ?: contItem.path("continuationEndpoint.nextContinuationData.continuation")?.asString
                        ?: contItem.path("continuationEndpoint.browseContinuationEndpoint.continuation")?.asString
                        ?: contItem.path("continuationEndpoint.reloadContinuationData.continuation")?.asString
                    if (!token.isNullOrBlank()) {
                        nextContinuation = token
                    }
                    continue
                }

                val renderer = item.getAsJsonObject("musicResponsiveListItemRenderer") ?: continue
                parseMusicResponsiveListItem(renderer)?.let { songs.add(it) }
            }

            if (nextContinuation.isNullOrBlank()) {
                nextContinuation = extractContinuationToken(container = continuationShelf, response = response)
            }

            Pair(songs, nextContinuation)
        }
    }

    /**
     * Extension to fetch all remaining pages of a PlaylistPage.
     */
    suspend fun PlaylistPage.completed(): PlaylistPage {
        var page = this
        var cont = page.continuation
        val allSongs = page.songs.toMutableList()
        var count = 0
        while (!cont.isNullOrBlank() && count < 250) {
            count++
            val res = playlistContinuation(cont).getOrNull() ?: break
            val (newSongs, nextCont) = res
            if (newSongs.isEmpty()) break
            allSongs.addAll(newSongs)
            if (nextCont == cont) break
            cont = nextCont
        }
        return page.copy(songs = allSongs, continuation = null)
    }

    /**
     * Remove a song from a YouTube Music playlist.
     */
    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.removeFromPlaylist(playlistId, videoId, setVideoId)
            response.has("status") || response.has("playlistEditResults") || response.has("responseContext")
        }
    }

    /**
     * Rename a YouTube Music playlist.
     */
    suspend fun renamePlaylist(playlistId: String, newName: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.renamePlaylist(playlistId, newName)
            response.has("status") || response.has("playlistEditResults") || response.has("responseContext")
        }
    }

    // ========== Lyrics ==========

    /**
     * Fetch lyrics for a song by first getting the lyrics browseId from the next endpoint,
     * then fetching the actual lyrics via browse.
     */
    suspend fun lyrics(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val nextResponse = InnerTubeClient.next(videoId)
            val tabs = nextResponse.path("contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs")?.asJsonArray

            // Lyrics tab is typically the second tab (index 1)
            var lyricsBrowseId: String? = null
            tabs?.forEach { tab ->
                val tabRenderer = tab.asJsonObject?.getAsJsonObject("tabRenderer")
                val endpoint = tabRenderer?.path("endpoint.browseEndpoint")?.asJsonObject
                val browseId = endpoint?.get("browseId")?.asString
                if (browseId != null && browseId.startsWith("MPLYt")) {
                    lyricsBrowseId = browseId
                }
            }

            if (lyricsBrowseId == null) throw IllegalStateException("No lyrics available for $videoId")

            val lyricsResponse = InnerTubeClient.browse(lyricsBrowseId)
            InnerTubeParser.parseLyrics(lyricsResponse)
        }
    }

    // ========== Start Radio ==========

    /**
     * Start a radio (mix) based on a song, returning the full auto-generated playlist.
     */
    suspend fun startRadio(videoId: String? = null, playlistId: String? = null): Result<List<OnlineSong>> = withContext(Dispatchers.IO) {
        runCatching {
            val pid = playlistId ?: videoId?.let { "RDAMVM$it" } ?: throw IllegalArgumentException("Must provide videoId or playlistId")
            val response = InnerTubeClient.next(videoId, playlistId = pid)
            val songs = mutableListOf<OnlineSong>()

            val playlistContents = response.path("contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs.0.tabRenderer.content.musicQueueRenderer.content.playlistPanelRenderer.contents")?.asJsonArray

            playlistContents?.forEach { content ->
                val renderer = content.asJsonObject.getAsJsonObject("playlistPanelVideoRenderer") ?: return@forEach
                val id = renderer.get("videoId")?.asString ?: return@forEach
                val titleText = renderer.path("title.runs.0.text")?.asString ?: return@forEach
                val shortByline = renderer.path("shortBylineText.runs")?.asJsonArray
                    ?.joinToString("") { it.asJsonObject.get("text")?.asString ?: "" } ?: ""
                val lengthText = renderer.path("lengthText.runs.0.text")?.asString
                val thumb = InnerTubeParser.getHighResThumbnailUrl(
                    renderer.path("thumbnail.thumbnails")
                        ?.asJsonArray?.lastOrNull()?.asJsonObject?.get("url")?.asString
                )

                songs.add(OnlineSong(
                    videoId = id,
                    title = titleText,
                    artist = shortByline.trim(),
                    duration = lengthText,
                    durationMs = parseDuration(lengthText),
                    thumbnailUrl = thumb
                ))
            }
            songs
        }
    }

    // ========== Playlist Management ==========

    /**
     * Create a new playlist on the user's YouTube Music account.
     */
    suspend fun createPlaylist(
        title: String,
        description: String = "",
        privacyStatus: String = "PRIVATE",
        videoIds: List<String> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.createPlaylist(title, description, privacyStatus, videoIds)
            response.get("playlistId")?.asString ?: throw IllegalStateException("Failed to create playlist")
        }
    }

    /**
     * Delete a playlist from the user's YouTube Music library.
     */
    suspend fun deletePlaylist(playlistId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.deletePlaylist(playlistId)
            response.has("command") || response.has("responseContext")
        }
    }

    /**
     * Add songs to an existing YouTube Music playlist.
     */
    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.addToPlaylist(playlistId, videoIds)
            response.has("status") || response.has("playlistEditResults") || response.has("responseContext")
        }
    }

    // ========== Subscriptions ==========

    suspend fun subscribeArtist(channelId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.subscribe(channelId)
            if (response.has("error")) {
                val errorMsg = response.getAsJsonObject("error")?.get("message")?.asString ?: "Subscription failed"
                throw Exception(errorMsg)
            }
            if (response.entrySet().isEmpty()) {
                throw Exception("Empty response from subscribe API")
            }
            true
        }
    }

    /**
     * Unsubscribe from an artist on YouTube Music.
     */
    suspend fun unsubscribeArtist(channelId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.unsubscribe(channelId)
            if (response.has("error")) {
                val errorMsg = response.getAsJsonObject("error")?.get("message")?.asString ?: "Unsubscription failed"
                throw Exception(errorMsg)
            }
            if (response.entrySet().isEmpty()) {
                throw Exception("Empty response from unsubscribe API")
            }
            true
        }
    }

    // ========== Related / Next ==========

    /**
     * Get related songs for a given video.
     */
    private fun findJsonObjectRecursively(element: JsonElement?, targetKey: String): JsonObject? {
        if (element == null || !element.isJsonObject) return null
        val obj = element.asJsonObject
        if (obj.has(targetKey) && obj.get(targetKey).isJsonObject) {
            return obj.getAsJsonObject(targetKey)
        }
        for (entry in obj.entrySet()) {
            val child = entry.value
            if (child.isJsonObject) {
                val found = findJsonObjectRecursively(child, targetKey)
                if (found != null) return found
            } else if (child.isJsonArray) {
                for (item in child.asJsonArray) {
                    val found = findJsonObjectRecursively(item, targetKey)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun findJsonArrayRecursively(element: JsonElement?, targetKey: String): JsonArray? {
        if (element == null || !element.isJsonObject) return null
        val obj = element.asJsonObject
        if (obj.has(targetKey) && obj.get(targetKey).isJsonArray) {
            return obj.getAsJsonArray(targetKey)
        }
        for (entry in obj.entrySet()) {
            val child = entry.value
            if (child.isJsonObject) {
                val found = findJsonArrayRecursively(child, targetKey)
                if (found != null) return found
            } else if (child.isJsonArray) {
                for (item in child.asJsonArray) {
                    val found = findJsonArrayRecursively(item, targetKey)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    // ========== Library & Account ==========

    /**
     * Browse a specific section using browseId and params.
     * Often returns a list of items similar to a playlist or artist page.
     * For simplicity, we can parse it as a PlaylistPage if it contains items.
     */
    suspend fun section(browseId: String, params: String?): Result<HomeSection> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse(browseId = browseId, params = params)
            // A section often has the same structure as a playlist (a list of items)
            InnerTubeParser.parseSectionPage(response) ?: throw Exception("Failed to parse section")
        }
    }

    /**
     * Fetch the current user's profile details (name, avatar)
     */
    suspend fun accountProfile(): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.accountMenu()
            InnerTubeParser.parseAccountProfile(response)
                ?: throw Exception("Failed to parse user profile")
        }
    }

    /**
     * Fetch user's library playlists
     */
    suspend fun libraryPlaylists(): Result<List<OnlinePlaylist>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse(browseId = "FEmusic_liked_playlists")
            val list = InnerTubeParser.parseLibraryPlaylists(response).toMutableList()
            var continuation = InnerTubeParser.extractLibraryContinuation(response)
            var iterations = 0
            while (continuation != null && iterations < 25) {
                iterations++
                try {
                    val nextResponse = InnerTubeClient.browse(continuation = continuation)
                    val (items, nextToken) = InnerTubeParser.extractContinuationItemsAndNextToken(nextResponse)
                    if (items != null && items.size() > 0) {
                        list.addAll(InnerTubeParser.parseLibraryPlaylistsList(items))
                    }
                    continuation = nextToken
                } catch (e: Exception) {
                    Log.e("YouTubeMusic", "Error fetching playlist continuation", e)
                    break
                }
            }
            list
        }
    }

    /**
     * Fetch user's library artists (followed/subscribed artists)
     */
    suspend fun libraryArtists(): Result<List<OnlineArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse(browseId = "FEmusic_library_corpus_artists")
            val list = InnerTubeParser.parseLibraryArtists(response).toMutableList()
            var continuation = InnerTubeParser.extractLibraryContinuation(response)
            var iterations = 0
            while (continuation != null && iterations < 25) {
                iterations++
                try {
                    val nextResponse = InnerTubeClient.browse(continuation = continuation)
                    val (items, nextToken) = InnerTubeParser.extractContinuationItemsAndNextToken(nextResponse)
                    if (items != null && items.size() > 0) {
                        list.addAll(InnerTubeParser.parseLibraryArtistsList(items))
                    }
                    continuation = nextToken
                } catch (e: Exception) {
                    Log.e("YouTubeMusic", "Error fetching artist continuation", e)
                    break
                }
            }
            list
        }
    }

    suspend fun libraryAlbums(): Result<List<OnlineAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse(browseId = "FEmusic_library_corpus_albums")
            val list = InnerTubeParser.parseLibraryAlbums(response).toMutableList()
            var continuation = InnerTubeParser.extractLibraryContinuation(response)
            var iterations = 0
            while (continuation != null && iterations < 25) {
                iterations++
                try {
                    val nextResponse = InnerTubeClient.browse(continuation = continuation)
                    val (items, nextToken) = InnerTubeParser.extractContinuationItemsAndNextToken(nextResponse)
                    if (items != null && items.size() > 0) {
                        list.addAll(InnerTubeParser.parseLibraryAlbumsList(items))
                    }
                    continuation = nextToken
                } catch (e: Exception) {
                    Log.e("YouTubeMusic", "Error fetching album continuation", e)
                    break
                }
            }
            list
        }
    }

    /**
     * Fetch user's library podcasts (subscribed podcasts)
     */
    suspend fun libraryPodcasts(): Result<List<OnlinePlaylist>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse(browseId = "FEmusic_library_corpus_podcasts")
            val list = InnerTubeParser.parseLibraryPlaylists(response).toMutableList()
            var continuation = InnerTubeParser.extractLibraryContinuation(response)
            var iterations = 0
            while (continuation != null && iterations < 25) {
                iterations++
                try {
                    val nextResponse = InnerTubeClient.browse(continuation = continuation)
                    val (items, nextToken) = InnerTubeParser.extractContinuationItemsAndNextToken(nextResponse)
                    if (items != null && items.size() > 0) {
                        list.addAll(InnerTubeParser.parseLibraryPlaylistsList(items))
                    }
                    continuation = nextToken
                } catch (e: Exception) {
                    Log.e("YouTubeMusic", "Error fetching podcast continuation", e)
                    break
                }
            }
            list
        }
    }

    /**
     * Resolves YouTube videoId for a non-YouTube or local track title and artist.
     */
    suspend fun resolveVideoIdForTrack(title: String, artist: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val query = "$title - $artist"
            val searchRes = search(query, SearchFilter.SONGS).getOrThrow()
            val song = searchRes.items.firstNotNullOfOrNull { item ->
                when (item) {
                    is SearchItem.Song -> item.song
                    is SearchItem.TopResult -> item.song
                    else -> null
                }
            }
            song?.videoId ?: throw NoSuchElementException("No matching YouTube track found for '$query'")
        }
    }

    /**
     * Prefetch a stream in background to speed up playback switches
     */
    fun prefetchStream(videoId: String?) {
        if (videoId.isNullOrEmpty()) return
        prefetchStreams(listOf(videoId))
    }

    /**
     * Warms the tap-path stream cache for several videos concurrently.
     */
    fun prefetchStreams(videoIds: List<String>) {
        val targets = videoIds.filter { it.isNotEmpty() }
        if (targets.isEmpty()) return
        appScope.launch {
            targets.forEach { id ->
                val cached = streamUrlCache[id]
                if (cached != null && cached.second > System.currentTimeMillis()) return@forEach
                launch { runCatching { getStreamUrl(id) } }
            }
        }
    }

    /**
     * High level convenience to kick off pre-caching for upcoming track
     */
    fun preCacheNextSong(song: com.mardous.booming.online.model.SongItem) {
        prefetchStream(song.videoId)
    }

    // ========== Auth / Account ==========

    /**
     * Fetch the authenticated user's YouTube Music listening history.
     */
    suspend fun history(): Result<List<OnlineSong>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.browse("FEmusic_history")
            InnerTubeParser.parseHistory(response)
        }
    }

    /**
     * Set authentication cookie for logged-in features.
     */
    fun setCookie(cookie: String?) {
        InnerTubeClient.cookie = cookie
    }

    // ========== Utility ==========

    private fun parseDuration(duration: String?): Long {
        if (duration == null) return 0
        return try {
            val parts = duration.split(":")
            when (parts.size) {
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
                3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }


    enum class LikeStatus {
        LIKE, DISLIKE, INDIFFERENT
    }

    /**
     * Update the like status of a song on YouTube Music.
     */
    suspend fun updateLikeStatus(videoId: String, status: LikeStatus): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val response = when (status) {
                LikeStatus.LIKE -> InnerTubeClient.like(videoId)
                LikeStatus.DISLIKE -> InnerTubeClient.dislike(videoId)
                LikeStatus.INDIFFERENT -> InnerTubeClient.removeLike(videoId)
            }
            response.has("actions") || response.has("responseContext")
        }
    }

    /**
     * Fetch the user's authentic last watched position for a track to sync cross-device resumes.
     */
    suspend fun getPlaybackPosition(videoId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val response = InnerTubeClient.playerWebRemix(videoId)
            val startSeconds = response.path("playerConfig.playbackStartConfig.startSeconds")?.asLong ?: 0L
            startSeconds * 1000L
        }
    }
}
