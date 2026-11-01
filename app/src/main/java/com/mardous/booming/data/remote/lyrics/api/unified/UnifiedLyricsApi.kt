package com.mardous.booming.data.remote.lyrics.api.unified

import android.content.Context
import android.util.Log
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.remote.lyrics.api.LyricsApi
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.remote.lyrics.spatial.security.BetterLyricsTokenManager
import com.mardous.booming.util.Constants.SIMPLE_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.userAgent
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * BetterLyrics unified SSE API (Portato QQ QRC word-by-word + Musixmatch
 * richsync/synced + golyrics TTML + KuGou LRC), ported from SpatialFlow's
 * BetterLyricsUnifiedApi.
 *
 * Each logical provider (Portato, Musixmatch) is its own [LyricsApi] so
 * they get separate rows in the provider sheet, but they share one
 * network bundle: concurrent requests for the same song await the same
 * in-flight SSE stream via [activeRequests].
 *
 * Endpoint: POST https://lyrics.api.dacubeking.com/v2/lyrics
 * (form: videoId/song/artist/duration/album/alwaysFetchMetadata/token,
 *  stream: text/event-stream, 403 triggers a token refresh + one retry).
 */
class UnifiedLyricsBundleClient(
    private val context: Context,
    private val client: HttpClient
) {
    data class Bundle(
        var portato: RawLyrics.Remote? = null,
        var musixmatchWord: RawLyrics.Remote? = null,
        var musixmatchSynced: RawLyrics.Remote? = null,
        var golyricsTtml: RawLyrics.Remote? = null,
        var kugouLrc: RawLyrics.Remote? = null
    )

    private val activeRequests = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Bundle>>()
    private val cachedBundles = ConcurrentHashMap<String, Bundle>()
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun getBundle(song: Song, title: String, artist: String): Bundle {
        val cacheKey = buildCacheKey(song, title, artist)
        cachedBundles[cacheKey]?.let { return it }

        val deferred = activeRequests.computeIfAbsent(cacheKey) {
            scope.async {
                try {
                    fetchStream(song, title, artist)
                } finally {
                    activeRequests.remove(cacheKey)
                }
            }
        }
        return deferred.await()
    }

    private suspend fun fetchStream(
        song: Song,
        title: String,
        artist: String,
        isRetry: Boolean = false
    ): Bundle {
        val cacheKey = buildCacheKey(song, title, artist)
        val bundle = Bundle()

        val jwt = BetterLyricsTokenManager.getOrRefreshToken(context, forceRefresh = isRetry)
        if (jwt.isNullOrEmpty()) {
            Log.w(TAG, "No valid JWT token available for unified search")
            return bundle
        }

        try {
            val channel = client.post(UNIFIED_API_URL) {
                userAgent(SIMPLE_USER_AGENT)
                contentType(ContentType.Application.FormUrlEncoded)
                header(HttpHeaders.Accept, "text/event-stream")
                setBody(
                    "videoId=${encode(onlineVideoId(song) ?: "")}" +
                            "&song=${encode(title)}" +
                            "&artist=${encode(artist)}" +
                            "&duration=${(song.duration / 1000).toInt().coerceAtLeast(0)}" +
                            "&alwaysFetchMetadata=false" +
                            (song.albumName.takeIf { it.isNotBlank() }?.let { "&album=${encode(it)}" } ?: "") +
                            "&token=${encode(jwt)}"
                )
                timeout {
                    connectTimeoutMillis = 15000
                    socketTimeoutMillis = 20000
                    requestTimeoutMillis = 20000
                }
            }.bodyAsChannel()

            var currentEvent = ""
            val dataBuffer = StringBuilder()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                when {
                    line.startsWith("event:") -> currentEvent = line.substring(6).trim()
                    line.startsWith("data:") -> dataBuffer.append(line.substring(5).trim())
                    line.isEmpty() -> {
                        val data = dataBuffer.toString()
                        if (data.isNotEmpty() && data != "[DONE]") {
                            processEvent(currentEvent, data, bundle)
                        }
                        currentEvent = ""
                        dataBuffer.setLength(0)
                    }
                }
            }
            val remaining = dataBuffer.toString()
            if (remaining.isNotEmpty() && remaining != "[DONE]") {
                processEvent(currentEvent, remaining, bundle)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Unified API SSE stream for '$title': ${e.message}")
        }

        cachedBundles[cacheKey] = bundle
        return bundle
    }

    private fun processEvent(event: String, data: String, bundle: Bundle) {
        if (event != "provider") return
        try {
            val json = Json.parseToJsonElement(data).jsonObject
            val provider = json["provider"]?.jsonPrimitive?.contentOrNull ?: return
            val results = json["results"]?.jsonObject ?: return

            when (provider) {
                "qq" -> {
                    var raw = results["lyrics"]?.jsonPrimitive?.contentOrNull
                    if (!raw.isNullOrEmpty()) {
                        raw = runCatching {
                            Json.parseToJsonElement(raw).jsonObject["lyrics"]
                                ?.jsonPrimitive?.contentOrNull ?: raw
                        }.getOrNull() ?: raw
                        bundle.portato = RawLyrics.Remote(
                            synced = RawLyrics.Remote.Content(
                                LyricsProvider.Portato.displayName, raw
                            )
                        )
                    }
                }
                "musixmatch" -> {
                    val wordByWord = results["wordByWord"]?.jsonPrimitive?.contentOrNull
                    if (!wordByWord.isNullOrEmpty()) {
                        bundle.musixmatchWord = RawLyrics.Remote(
                            synced = RawLyrics.Remote.Content(
                                LyricsProvider.Musixmatch.displayName, wordByWord
                            )
                        )
                    }
                    val synced = results["synced"]?.jsonPrimitive?.contentOrNull
                    if (!synced.isNullOrEmpty()) {
                        bundle.musixmatchSynced = RawLyrics.Remote(
                            synced = RawLyrics.Remote.Content(
                                LyricsProvider.Musixmatch.displayName, synced
                            )
                        )
                    }
                }
                "golyrics" -> {
                    var ttml = results["lyrics"]?.jsonPrimitive?.contentOrNull
                    if (!ttml.isNullOrEmpty()) {
                        ttml = runCatching {
                            val parsed = Json.parseToJsonElement(ttml).jsonObject
                            if (parsed.containsKey("ttml")) {
                                parsed["ttml"]?.jsonPrimitive?.contentOrNull ?: ttml
                            } else ttml
                        }.getOrNull() ?: ttml
                        bundle.golyricsTtml = RawLyrics.Remote(
                            synced = RawLyrics.Remote.Content(
                                LyricsProvider.UnifiedTTML.displayName, ttml
                            )
                        )
                    }
                }
                "kugou" -> {
                    var raw = results["lyrics"]?.jsonPrimitive?.contentOrNull
                    if (!raw.isNullOrEmpty()) {
                        raw = runCatching {
                            Json.parseToJsonElement(raw).jsonObject["lyrics"]
                                ?.jsonPrimitive?.contentOrNull ?: raw
                        }.getOrNull() ?: raw
                        bundle.kugouLrc = RawLyrics.Remote(
                            synced = RawLyrics.Remote.Content(
                                LyricsProvider.UnifiedKugou.displayName, raw
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse provider stream JSON", e)
        }
    }

    private fun buildCacheKey(song: Song, title: String, artist: String): String {
        return onlineVideoId(song)?.takeIf { it.isNotBlank() }
            ?: "${artist.lowercase()}_${title.lowercase()}"
    }

    private fun onlineVideoId(song: Song): String? {
        val uri = song.uri
        uri?.host?.takeIf { it.isNotBlank() }?.let { return it }
        return song.data.removePrefix("yt:")
            .takeIf { song.data.startsWith("yt:") && it.isNotBlank() }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "UnifiedLyrics"
        private const val UNIFIED_API_URL = "https://lyrics.api.dacubeking.com/v2/lyrics"
    }
}

/**
 * Portato (QQ Music QRC word-by-word karaoke) row, fed by the shared
 * unified bundle.
 */
class PortatoApi(
    private val bundleClient: UnifiedLyricsBundleClient
) : LyricsApi {
    override val provider = LyricsProvider.Portato
    override suspend fun downloadLyrics(song: Song, title: String, artist: String) =
        bundleClient.getBundle(song, title, artist).portato
}

/**
 * Musixmatch (richsync word-by-word, synced fallback) row, fed by the
 * shared unified bundle.
 */
class MusixmatchApi(
    private val bundleClient: UnifiedLyricsBundleClient
) : LyricsApi {
    override val provider = LyricsProvider.Musixmatch
    override suspend fun downloadLyrics(song: Song, title: String, artist: String): RawLyrics.Remote? {
        val bundle = bundleClient.getBundle(song, title, artist)
        return bundle.musixmatchWord ?: bundle.musixmatchSynced
    }
}

/**
 * Unified golyrics TTML row, fed by the shared unified bundle.
 */
class UnifiedTtmlApi(
    private val bundleClient: UnifiedLyricsBundleClient
) : LyricsApi {
    override val provider = LyricsProvider.UnifiedTTML
    override suspend fun downloadLyrics(song: Song, title: String, artist: String) =
        bundleClient.getBundle(song, title, artist).golyricsTtml
}

/**
 * Unified Legato (KuGou LRC) row, fed by the shared unified bundle.
 */
class UnifiedKugouApi(
    private val bundleClient: UnifiedLyricsBundleClient
) : LyricsApi {
    override val provider = LyricsProvider.UnifiedKugou
    override suspend fun downloadLyrics(song: Song, title: String, artist: String) =
        bundleClient.getBundle(song, title, artist).kugouLrc
}
