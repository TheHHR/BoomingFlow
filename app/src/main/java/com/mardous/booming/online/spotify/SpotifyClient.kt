package com.mardous.booming.online.data.spotify

import com.mardous.booming.online.data.network.KtorClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.TimeUnit

/**
 * Spotify API client using the internal GraphQL API (api-partner.spotify.com)
 * for most operations, falling back to the public REST API for endpoints
 * without a GraphQL equivalent.
 *
 * GQL persisted-query hashes sourced from SpotifyHashProvider.
 */
object SpotifyClient {

    @Volatile var accessToken: String? = null

    private const val GQL_URL = "https://api-partner.spotify.com/pathfinder/v2/query"

    private fun randomUserAgent(): String {
        val osOptions = arrayOf(
            "Windows NT 10.0; Win64; x64",
            "Macintosh; Intel Mac OS X 10_15_7",
            "X11; Linux x86_64",
        )
        val chromeMajor = 140 - (0..4).random()
        val chromePatch = (0..499).random()
        val os = osOptions.random()
        return "Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeMajor.0.$chromePatch.0 Safari/537.36"
    }

    private val json = KtorClientProvider.defaultJson

    private val restClient = KtorClientProvider.httpClient

    private val gqlClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            defaultRequest {
                header("User-Agent", randomUserAgent())
                header("app-platform", "WebPlayer")
                header("Origin", "https://open.spotify.com")
                header("Referer", "https://open.spotify.com/")
                header("Accept", "application/json")
            }
            expectSuccess = false
        }
    }

    class SpotifyException(val statusCode: Int, override val message: String) : Exception(message)

    // ── JSON navigation helpers ──────────────────────────────────────────────

    private fun JsonObject.obj(key: String): JsonObject? = try {
        this[key]?.takeIf { it !is JsonNull }?.jsonObject
    } catch (_: Exception) { null }

    private fun JsonObject.str(key: String): String? = try {
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) { null }

    private fun JsonObject.int(key: String): Int? = try {
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull
    } catch (_: Exception) { null }

    private fun JsonObject.long(key: String): Long? = try {
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull
    } catch (_: Exception) { null }

    private fun JsonObject.arr(key: String): JsonArray? = try {
        this[key]?.takeIf { it !is JsonNull }?.jsonArray
    } catch (_: Exception) { null }

    // ── Token management ─────────────────────────────────────────────────────

    private var tokenRefresher: (suspend () -> Boolean)? = null
    @Volatile private var anonymousToken: String? = null
    @Volatile private var anonymousTokenExpiryMs: Long = 0L

    fun setTokenRefresher(refresher: (suspend () -> Boolean)?) {
        tokenRefresher = refresher
    }

    fun setToken(token: String) {
        accessToken = token
    }

    fun clearToken() {
        accessToken = null
    }

    fun isAuthenticated(): Boolean = accessToken != null

    /**
     * Returns the active user token if authenticated, or automatically fetches/reuses an anonymous Spotify web token.
     */
    suspend fun getEffectiveToken(): String? {
        val userToken = accessToken
        if (!userToken.isNullOrBlank()) return userToken

        if (anonymousToken != null && System.currentTimeMillis() < anonymousTokenExpiryMs) {
            return anonymousToken
        }

        return fetchAnonymousToken()
    }

    suspend fun fetchAnonymousToken(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val result = SpotifyAuth.fetchAnonymousAccessToken().getOrNull()
            if (result != null && result.accessToken.isNotBlank()) {
                anonymousToken = result.accessToken
                anonymousTokenExpiryMs = result.accessTokenExpirationTimestampMs - 60_000L
                return@withContext result.accessToken
            }
        } catch (e: Exception) {
            android.util.Log.w("SpotifyClient", "Failed to fetch anonymous Spotify web token", e)
        }
        null
    }

    // ── GraphQL core ──────────────────────────────────────────────────────────

    private suspend fun graphqlPost(
        operationName: String,
        variables: JsonObject = buildJsonObject {},
    ): JsonObject {
        val primaryHash = SpotifyHashProvider.getHash(operationName)
        val hashCandidates = buildList {
            add(primaryHash)
            SpotifyHashProvider.getPreviousHash(operationName)?.let { prev ->
                if (prev != primaryHash) add(prev)
            }
        }

        for ((hashIdx, sha256Hash) in hashCandidates.withIndex()) {
            val body = buildJsonObject {
                put("variables", variables)
                put("operationName", operationName)
                putJsonObject("extensions") {
                    putJsonObject("persistedQuery") {
                        put("version", 1)
                        put("sha256Hash", sha256Hash)
                    }
                }
            }

            val result = executeGqlWithRetries(operationName, body)
            if (result.isPersistedQueryNotFound) {
                if (hashIdx < hashCandidates.lastIndex) continue
                throw SpotifyException(412, "PersistedQueryNotFound for $operationName")
            }
            return result.json!!
        }

        throw SpotifyException(412, "No valid hash found for $operationName")
    }

    private class GqlResult(val json: JsonObject?, val isPersistedQueryNotFound: Boolean)

    private suspend fun executeGqlWithRetries(
        operationName: String,
        body: JsonObject,
    ): GqlResult {
        val maxRetries = 3
        var refreshedTokenOnce = false

        for (attempt in 0 until maxRetries) {
            val token = accessToken ?: getEffectiveToken()
            if (token == null) {
                if (!refreshedTokenOnce && tokenRefresher?.invoke() == true) {
                    refreshedTokenOnce = true
                    continue
                }
                throw SpotifyException(401, "Not authenticated")
            }

            val response = gqlClient.post(GQL_URL) {
                header("Authorization", "Bearer $token")
                setBody(
                    TextContent(
                        body.toString(),
                        ContentType.Application.Json.withParameter("charset", "UTF-8"),
                    )
                )
            }

            when {
                response.status == HttpStatusCode.Unauthorized -> {
                    if (!refreshedTokenOnce && tokenRefresher?.invoke() == true) {
                        refreshedTokenOnce = true
                        continue
                    }
                    anonymousToken = null
                    throw SpotifyException(401, "Token expired or invalid")
                }

                response.status == HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: (2L * (attempt + 1))
                    if (attempt < maxRetries - 1) { delay(retryAfter * 1000); continue }
                    throw SpotifyException(429, "Rate limited")
                }

                response.status == HttpStatusCode.PreconditionFailed ->
                    return GqlResult(json = null, isPersistedQueryNotFound = true)

                response.status.value !in 200..299 ->
                    throw SpotifyException(response.status.value, "GraphQL error ${response.status.value}")
            }

            val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val errors = responseJson.arr("errors")
            if (errors != null && errors.isNotEmpty()) {
                val errorMsg = errors[0].jsonObject.str("message") ?: "Unknown GraphQL error"
                if (errorMsg.contains("PersistedQueryNotFound", ignoreCase = true)) {
                    return GqlResult(json = null, isPersistedQueryNotFound = true)
                }
                throw SpotifyException(400, "GraphQL: $errorMsg")
            }

            return GqlResult(json = responseJson, isPersistedQueryNotFound = false)
        }
        throw SpotifyException(429, "Rate limited after $maxRetries retries")
    }

    // ── REST core ─────────────────────────────────────────────────────────────

    private suspend inline fun <reified T> authenticatedGet(
        endpoint: String,
        crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val maxRetries = 3
        var refreshedTokenOnce = false

        for (attempt in 0 until maxRetries) {
            val token = accessToken ?: getEffectiveToken()
            if (token == null) {
                if (!refreshedTokenOnce && tokenRefresher?.invoke() == true) {
                    refreshedTokenOnce = true
                    continue
                }
                throw SpotifyException(401, "Not authenticated")
            }

            val response = restClient.get(endpoint) {
                header("Authorization", "Bearer $token")
                block()
            }
            when {
                response.status == HttpStatusCode.Unauthorized -> {
                    if (!refreshedTokenOnce && tokenRefresher?.invoke() == true) {
                        refreshedTokenOnce = true
                        continue
                    }
                    anonymousToken = null
                    throw SpotifyException(401, "Token expired")
                }
                response.status == HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: (2L * (attempt + 1))
                    if (attempt < maxRetries - 1) { delay(retryAfter * 1000); continue }
                    throw SpotifyException(429, "Rate limited")
                }
                response.status.value !in 200..299 ->
                    throw SpotifyException(response.status.value, "Spotify API error ${response.status.value}")
            }
            return response.body()
        }
        throw SpotifyException(429, "Rate limited after $maxRetries retries")
    }

    // ── GQL response parsers ──────────────────────────────────────────────────

    private fun parseGqlImage(source: JsonObject): SpotifyImage? {
        val url = source.str("url") ?: return null
        return SpotifyImage(url = url, height = source.int("height"), width = source.int("width"))
    }

    private fun parseGqlImages(sources: JsonArray?): List<SpotifyImage> =
        sources?.mapNotNull { parseGqlImage(it.jsonObject) } ?: emptyList()

    private fun parseGqlSimpleArtist(artistObj: JsonObject): SpotifySimpleArtist? {
        val uri = artistObj.str("uri") ?: return null
        return SpotifySimpleArtist(
            id = uri.substringAfterLast(":"),
            name = artistObj.obj("profile")?.str("name") ?: "",
            uri = uri,
        )
    }

    private fun parseGqlTrackDurationMs(trackData: JsonObject): Int {
        val durationObj = trackData.obj("duration") ?: trackData.obj("trackDuration")
        val milliseconds = listOfNotNull(
            durationObj?.long("totalMilliseconds"),
            durationObj?.long("total_milliseconds"),
            durationObj?.long("milliseconds"),
            durationObj?.long("durationMs"),
            trackData.long("durationMs"),
            trackData.long("duration_ms"),
            trackData.long("durationMilliseconds"),
            trackData.long("totalMilliseconds"),
        ).firstOrNull { it > 0 }
        if (milliseconds != null) return milliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        trackData.long("duration")?.let { seconds ->
            if (seconds > 0) return (seconds * 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        android.util.Log.d("SpotifyClient", "Missing track duration. Fields: ${trackData.keys}")
        return 0
    }

    private fun parseGqlTrack(
        trackData: JsonObject,
        albumOverride: SpotifySimpleAlbum? = null,
        uriOverride: String? = null,
    ): SpotifyTrack {
        val uri = uriOverride ?: trackData.str("uri") ?: trackData.str("_uri") ?: ""
        val trackId = uri.substringAfterLast(":")

        val artists = trackData.obj("artists")?.arr("items")?.mapNotNull { elem ->
            parseGqlSimpleArtist(elem.jsonObject)
        } ?: emptyList()

        val album = albumOverride ?: run {
            val albumData = trackData.obj("albumOfTrack")
            val albumUri = albumData?.str("uri") ?: ""
            val albumId = albumUri.substringAfterLast(":")
            SpotifySimpleAlbum(
                id = albumId,
                name = albumData?.str("name") ?: "",
                images = parseGqlImages(albumData?.obj("coverArt")?.arr("sources")),
                uri = albumUri.ifEmpty { null },
            )
        }

        return SpotifyTrack(
            id = trackId,
            name = trackData.str("name") ?: "",
            artists = artists,
            album = album,
            durationMs = parseGqlTrackDurationMs(trackData),
            explicit = trackData.obj("contentRating")?.str("label") == "EXPLICIT",
            uri = uri.ifEmpty { null },
        )
    }

    private fun parseGqlPlaylistImages(imagesObj: JsonObject?): List<SpotifyImage> =
        imagesObj?.arr("items")?.flatMap { imageGroup ->
            parseGqlImages(imageGroup.jsonObject.arr("sources"))
        } ?: emptyList()

    private fun parsePlaylistWrapper(wrapper: JsonObject): SpotifyPlaylist? {
        val data = wrapper.obj("data") ?: return null
        if (data.str("__typename") != "Playlist") return null
        val playlistUri = wrapper.str("_uri") ?: return null
        val playlistId = playlistUri.substringAfterLast(":")
        val ownerData = data.obj("ownerV2")?.obj("data")
        val ownerId = ownerData?.str("uri")?.substringAfterLast(":") ?: ownerData?.str("id") ?: ""
        return SpotifyPlaylist(
            id = playlistId,
            name = data.str("name") ?: "",
            description = data.str("description"),
            images = parseGqlPlaylistImages(data.obj("images")),
            owner = SpotifyPlaylistOwner(
                id = ownerId,
                displayName = ownerData?.str("name"),
                uri = ownerData?.str("uri"),
            ),
            tracks = SpotifyPlaylistTracksRef(total = parsePlaylistTrackCount(data)),
            uri = playlistUri,
        )
    }

    private fun parsePlaylistTrackCount(data: JsonObject): Int? =
        data.obj("content")?.int("totalCount")
            ?: data.obj("contents")?.int("totalCount")
            ?: data.obj("tracks")?.int("totalCount")
            ?: data.obj("tracksV2")?.int("totalCount")
            ?: data.int("totalCount")
            ?: data.int("trackCount")
            ?: data.int("numTracks")

    // ── Public API methods ────────────────────────────────────────────────────

    /** Fetches the current user's Spotify profile. */
    suspend fun me(): Result<SpotifyUser> = runCatching {
        try {
            val response = graphqlPost(operationName = "profileAttributes")
            val profile = response.obj("data")?.obj("me")?.obj("profile")
                ?: throw SpotifyException(500, "Invalid profileAttributes response")
            val uri = profile.str("uri") ?: ""
            SpotifyUser(
                id = uri.substringAfterLast(":"),
                displayName = profile.str("name"),
                email = null,
                images = parseGqlImages(profile.obj("avatar")?.arr("sources")),
            )
        } catch (e: Exception) {
            authenticatedGet("me")
        }
    }

    /** Fetches the user's library playlists (flattened, no folder nesting). */
    suspend fun myPlaylists(limit: Int = 50, offset: Int = 0): Result<SpotifyPaging<SpotifyPlaylist>> =
        runCatching {
            val vars = buildJsonObject {
                putJsonArray("filters") { add("Playlists") }
                put("order", null as String?)
                put("textFilter", "")
                putJsonArray("features") {
                    add("LIKED_SONGS"); add("YOUR_EPISODES_V2")
                    add("PRERELEASES"); add("EVENTS")
                }
                put("limit", limit)
                put("offset", offset)
                put("flatten", true)
                putJsonArray("expandedFolders") {}
                put("folderUri", null as String?)
                put("includeFoldersWhenFlattening", false)
            }

            val response = graphqlPost(operationName = "libraryV3", variables = vars)
            val libraryData = response.obj("data")?.obj("me")?.obj("libraryV3")
                ?: throw SpotifyException(500, "Invalid libraryV3 response")
            val totalCount = libraryData.int("totalCount") ?: 0
            val pagingInfo = libraryData.obj("pagingInfo")

            val playlists = libraryData.arr("items")?.mapNotNull { itemElem ->
                val wrapper = itemElem.jsonObject.obj("item") ?: return@mapNotNull null
                if (wrapper.str("__typename") != "PlaylistResponseWrapper") return@mapNotNull null
                parsePlaylistWrapper(wrapper)
            } ?: emptyList()

            SpotifyPaging(
                items = playlists,
                total = totalCount,
                limit = pagingInfo?.int("limit") ?: limit,
                offset = pagingInfo?.int("offset") ?: offset,
            )
        }

    /** Fetches playlist metadata. */
    suspend fun playlist(playlistId: String): Result<SpotifyPlaylist> = runCatching {
        val vars = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", 0)
            put("limit", 25)
            put("enableWatchFeedEntrypoint", true)
        }
        val response = graphqlPost(operationName = "fetchPlaylist", variables = vars)
        val playlist = response.obj("data")?.obj("playlistV2")
            ?: throw SpotifyException(500, "Invalid fetchPlaylist response")

        val ownerData = playlist.obj("ownerV2")?.obj("data")
        val ownerUri = ownerData?.str("uri") ?: ""
        val images = playlist.obj("images")?.arr("items")?.firstOrNull()?.let {
            parseGqlImages(it.jsonObject.arr("sources"))
        } ?: emptyList()

        SpotifyPlaylist(
            id = playlistId,
            name = playlist.str("name") ?: "",
            description = playlist.str("description"),
            images = images,
            owner = SpotifyPlaylistOwner(
                id = ownerUri.substringAfterLast(":"),
                displayName = ownerData?.str("name"),
                uri = ownerUri.ifEmpty { null },
            ),
            tracks = SpotifyPlaylistTracksRef(total = parsePlaylistTrackCount(playlist)),
            collaborative = (playlist.obj("members")?.arr("items")?.size ?: 0) > 1,
        )
    }

    /** Fetches paginated tracks from a playlist. */
    suspend fun playlistTracks(
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0,
    ): Result<SpotifyPaging<SpotifyPlaylistTrack>> = runCatching {
        val vars = buildJsonObject {
            put("uri", "spotify:playlist:$playlistId")
            put("offset", offset)
            put("limit", limit)
            put("enableWatchFeedEntrypoint", false)
        }
        val response = graphqlPost(operationName = "fetchPlaylist", variables = vars)
        val content = response.obj("data")?.obj("playlistV2")?.obj("content")
            ?: throw SpotifyException(500, "No content in fetchPlaylist response")

        val tracks = content.arr("items")?.mapNotNull { elem ->
            val itemWrapper = elem.jsonObject.obj("itemV2") ?: return@mapNotNull null
            val itemData = itemWrapper.obj("data") ?: return@mapNotNull null
            val wrapperUri = itemWrapper.str("_uri") ?: itemWrapper.str("uri")
            val uid = elem.jsonObject.str("uid") ?: itemWrapper.str("uid")
            SpotifyPlaylistTrack(
                track = parseGqlTrack(itemData, uriOverride = wrapperUri),
                uid = uid,
            )
        } ?: emptyList()

        SpotifyPaging(
            items = tracks,
            total = content.int("totalCount") ?: 0,
            limit = limit,
            offset = offset,
        )
    }

    /** Fetches the user's Liked Songs. */
    suspend fun likedSongs(limit: Int = 50, offset: Int = 0): Result<SpotifyPaging<SpotifySavedTrack>> =
        runCatching {
            val vars = buildJsonObject {
                put("offset", offset)
                put("limit", limit)
            }
            val response = graphqlPost(operationName = "fetchLibraryTracks", variables = vars)
            val tracksData = response.obj("data")?.obj("me")?.obj("library")?.obj("tracks")
                ?: throw SpotifyException(500, "Invalid fetchLibraryTracks response")

            val savedTracks = tracksData.arr("items")?.mapNotNull { elem ->
                val trackWrapper = elem.jsonObject.obj("track") ?: return@mapNotNull null
                val trackData = trackWrapper.obj("data") ?: return@mapNotNull null
                val wrapperUri = trackWrapper.str("_uri") ?: trackWrapper.str("uri")
                SpotifySavedTrack(track = parseGqlTrack(trackData, uriOverride = wrapperUri))
            } ?: emptyList()

            SpotifyPaging(
                items = savedTracks,
                total = tracksData.int("totalCount") ?: 0,
                limit = limit,
                offset = offset,
            )
        }

    /** Fetches the personalized Spotify web-player Home feed. */
    suspend fun home(
        sectionItemsLimit: Int = 10,
        timeZone: String = java.util.TimeZone.getDefault().id,
    ): Result<SpotifyHomeFeed> = runCatching {
        val variables = buildJsonObject {
            put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
            put("timeZone", timeZone)
            put("sp_t", "")
            put("facet", "")
            put("sectionItemsLimit", sectionItemsLimit)
            put("includeEpisodeContentRatingsV2", false)
        }
        val response = graphqlPost(operationName = "home", variables = variables)
        val homeData = response.obj("data")?.obj("home")
            ?: throw SpotifyException(500, "Invalid Spotify Home response")
        val sections = homeData.obj("sectionContainer")?.obj("sections")?.arr("items")
            ?.mapNotNull { parseHomeSection(it.jsonObject) }
            .orEmpty()
        SpotifyHomeFeed(
            greeting = homeData.obj("greeting")?.str("transformedLabel"),
            sections = sections,
        )
    }

    private fun parseHomeSection(sectionObj: JsonObject): SpotifyHomeSection? {
        val data = sectionObj.obj("data") ?: return null
        val sectionItems = sectionObj.obj("sectionItems") ?: return null
        val items = sectionItems.arr("items")
            ?.mapNotNull { parseHomeItem(it.jsonObject) }
            ?.filterIsInstance<SpotifyHomeItem.Playlist>()
            .orEmpty()
        if (items.isEmpty()) return null

        val title = data.obj("title")
        return SpotifyHomeSection(
            uri = sectionObj.str("uri").orEmpty(),
            title = title?.str("transformedLabel") ?: title?.str("translatedBaseText") ?: title?.str("text"),
            totalCount = items.size,
            items = items,
        )
    }

    private fun parseHomeItem(itemObj: JsonObject): SpotifyHomeItem? {
        val content = itemObj.obj("content") ?: return null
        val data = content.obj("data") ?: return null
        val type = content.str("__typename") ?: data.str("__typename") ?: ""
        val uri = data.str("uri") ?: data.str("_uri") ?: itemObj.str("uri") ?: ""

        return when {
            type.contains("Playlist", ignoreCase = true) || uri.startsWith("spotify:playlist:") ->
                parseHomePlaylist(data)
            else -> null
        }
    }

    private fun parseHomePlaylist(data: JsonObject): SpotifyHomeItem.Playlist? {
        val uri = data.str("uri") ?: data.str("_uri") ?: return null
        val imageSources = data.obj("images")?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
            ?: data.obj("coverArt")?.arr("sources")
            ?: data.obj("images")?.arr("sources")
            ?: data.arr("images")
        val imageUrl = imageSources?.firstOrNull()?.jsonObject?.str("url")
            ?: imageSources?.firstOrNull()?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }
        val rawDesc = data.str("description")
        val cleanDesc = rawDesc?.replace(Regex("<[^>]*>"), "")
            ?.replace("&amp;", "&")
            ?.replace("&quot;", "\"")
            ?.replace("&#39;", "'")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val name = data.str("name") ?: data.obj("name")?.str("text") ?: ""
        val ownerName = data.obj("ownerV2")?.obj("data")?.str("name")
            ?: data.obj("owner")?.str("displayName")
            ?: data.obj("owner")?.str("name")
        return SpotifyHomeItem.Playlist(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = name,
            description = cleanDesc,
            imageUrl = imageUrl,
            ownerName = ownerName,
        )
    }

    private fun parseHomeAlbum(data: JsonObject): SpotifyHomeItem.Album? {
        val uri = data.str("uri") ?: data.str("_uri") ?: return null
        val artists = data.obj("artists")?.arr("items")
            ?.mapNotNull { parseGqlSimpleArtist(it.jsonObject) }
            ?: data.arr("artists")?.mapNotNull { parseGqlSimpleArtist(it.jsonObject) }
            ?: emptyList()
        val imageSources = data.obj("coverArt")?.arr("sources")
            ?: data.obj("images")?.arr("items")?.firstOrNull()?.jsonObject?.arr("sources")
            ?: data.obj("images")?.arr("sources")
            ?: data.arr("images")
        val imageUrl = imageSources?.firstOrNull()?.jsonObject?.str("url")
            ?: imageSources?.firstOrNull()?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }
        val name = data.str("name") ?: data.obj("name")?.str("text") ?: ""
        return SpotifyHomeItem.Album(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = name,
            artists = artists,
            imageUrl = imageUrl,
        )
    }

    private fun parseHomeArtist(data: JsonObject): SpotifyHomeItem.Artist? {
        val uri = data.str("uri") ?: data.str("_uri") ?: return null
        val name = data.obj("profile")?.str("name") ?: data.str("name").orEmpty()
        val imageSources = data.obj("visuals")?.obj("avatarImage")?.arr("sources")
            ?: data.obj("avatar")?.arr("sources")
            ?: data.obj("images")?.arr("sources")
            ?: data.arr("images")
        val imageUrl = imageSources?.firstOrNull()?.jsonObject?.str("url")
            ?: imageSources?.firstOrNull()?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }
        return SpotifyHomeItem.Artist(
            uri = uri,
            id = uri.substringAfterLast(":"),
            name = name,
            imageUrl = imageUrl,
        )
    }

    /** Fetches a Spotify Artist's basic info by ID. */
    suspend fun artist(artistId: String): Result<SpotifyHomeItem.Artist> = runCatching {
        val vars = buildJsonObject {
            put("uri", "spotify:artist:$artistId")
            put("locale", "")
            put("includePrereleases", false)
        }
        val response = graphqlPost(operationName = "queryArtistOverview", variables = vars)
        val artistData = response.obj("data")?.obj("artistUnion")
            ?: throw SpotifyException(500, "Invalid queryArtistOverview response")
        val name = artistData.obj("profile")?.str("name").orEmpty()
        val imageUrl = artistData.obj("visuals")?.obj("avatarImage")?.arr("sources")
            ?.firstOrNull()?.jsonObject?.str("url")
        SpotifyHomeItem.Artist(
            uri = "spotify:artist:$artistId",
            id = artistId,
            name = name,
            imageUrl = imageUrl
        )
    }

    /** Fetches a Spotify Album's basic info by ID. */
    suspend fun album(albumId: String): Result<SpotifyHomeItem.Album> = runCatching {
        val vars = buildJsonObject {
            put("uri", "spotify:album:$albumId")
            put("locale", "")
        }
        val response = graphqlPost(operationName = "getAlbum", variables = vars)
        val albumData = response.obj("data")?.obj("albumUnion")
            ?: throw SpotifyException(500, "Invalid getAlbum response")
        val name = albumData.str("name").orEmpty()
        val artists = albumData.obj("artists")?.arr("items")
            ?.mapNotNull { parseGqlSimpleArtist(it.jsonObject) }
            .orEmpty()
        val imageUrl = albumData.obj("coverArt")?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")
        SpotifyHomeItem.Album(
            uri = "spotify:album:$albumId",
            id = albumId,
            name = name,
            artists = artists,
            imageUrl = imageUrl
        )
    }

    /** Searches for a track on Spotify and returns its ID (e.g. for Canvas resolution). */
    suspend fun searchTrackId(
        query: String,
        expectedTitle: String? = null,
        expectedArtist: String? = null
    ): String? = runCatching {
        val token = getEffectiveToken() ?: return@runCatching null

        // 1. Try GQL searchDesktop
        val gqlTrackId = runCatching {
            val vars = buildJsonObject {
                put("searchTerm", query)
                put("offset", 0)
                put("limit", 5)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeLocalConcertsField", false)
            }
            val response = graphqlPost(operationName = "searchDesktop", variables = vars)
            val tracks = response.obj("data")?.obj("searchV2")?.obj("tracksV2")?.arr("items") ?: return@runCatching null

            for (elem in tracks) {
                val itemData = elem.jsonObject.obj("item")?.obj("data") ?: continue
                val uri = itemData.str("uri") ?: continue
                val name = itemData.str("name").orEmpty()
                val artists = itemData.obj("artists")?.arr("items")?.mapNotNull {
                    it.jsonObject.obj("profile")?.str("name") ?: it.jsonObject.str("name")
                }.orEmpty()
                val artistStr = artists.joinToString(" ")

                if (!expectedTitle.isNullOrBlank() && !expectedArtist.isNullOrBlank()) {
                    val score = com.mardous.booming.online.data.sourcepool.TrackMatching.score(
                        wantedTitle = expectedTitle,
                        wantedArtists = listOf(expectedArtist),
                        candidateTitle = name,
                        candidateArtist = artistStr,
                        wantedDurationMs = null,
                        candidateDurationMs = null
                    )
                    if (score >= com.mardous.booming.online.data.sourcepool.TrackMatching.MIN_MATCH_SCORE) {
                        return@runCatching uri.removePrefix("spotify:track:")
                    }
                } else {
                    return@runCatching uri.removePrefix("spotify:track:")
                }
            }
            null
        }.getOrNull()

        if (!gqlTrackId.isNullOrBlank()) return@runCatching gqlTrackId

        // 2. Fallback: REST API search
        val restResponse = restClient.get("https://api.spotify.com/v1/search") {
            header("Authorization", "Bearer $token")
            parameter("q", query)
            parameter("type", "track")
            parameter("limit", "5")
        }
        if (restResponse.status == HttpStatusCode.OK) {
            val text = restResponse.bodyAsText()
            val parsed = json.parseToJsonElement(text).jsonObject
            val items = parsed.obj("tracks")?.arr("items")
            if (items != null) {
                for (elem in items) {
                    val trackObj = elem.jsonObject
                    val id = trackObj.str("id") ?: continue
                    val name = trackObj.str("name").orEmpty()
                    val artists = trackObj.arr("artists")?.mapNotNull { it.jsonObject.str("name") }.orEmpty()
                    val artistStr = artists.joinToString(" ")

                    if (!expectedTitle.isNullOrBlank() && !expectedArtist.isNullOrBlank()) {
                        val score = com.mardous.booming.online.data.sourcepool.TrackMatching.score(
                            wantedTitle = expectedTitle,
                            wantedArtists = listOf(expectedArtist),
                            candidateTitle = name,
                            candidateArtist = artistStr,
                            wantedDurationMs = null,
                            candidateDurationMs = null
                        )
                        if (score >= com.mardous.booming.online.data.sourcepool.TrackMatching.MIN_MATCH_SCORE) {
                            return@runCatching id
                        }
                    } else {
                        return@runCatching id
                    }
                }
            }
        }
        null
    }.getOrNull()

    /**
     * Searches Spotify (tracks + playlists) via the searchDesktop persisted query.
     */
    suspend fun search(query: String, limit: Int = 20): SpotifySearchResults {
        if (query.isBlank()) return SpotifySearchResults()

        val tracks = searchTracks(query, limit)
        val playlists = searchPlaylists(query, limit)
        return SpotifySearchResults(
            tracks = tracks,
            playlists = playlists,
            canLoadMore = tracks.size >= limit
        )
    }

    private fun searchEntityItems(response: JsonObject, vararg entityKeys: String): JsonArray? {
        val searchV2 = response.obj("data")?.obj("searchV2") ?: return null
        for (key in entityKeys) {
            searchV2.obj(key)?.arr("items")?.let { return it }
        }
        return null
    }

    suspend fun searchTracks(query: String, limit: Int = 20, offset: Int = 0): List<SpotifyTrack> =
        runCatching {
            val vars = buildJsonObject {
                put("searchTerm", query)
                put("offset", offset)
                put("limit", limit)
                put("numberOfTopResults", minOf(limit, 5))
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeLocalConcertsField", false)
            }
            val response = graphqlPost(operationName = "searchDesktop", variables = vars)
            val items = searchEntityItems(response, "tracksV2", "tracks")
                ?: return@runCatching emptyList()
            items.mapNotNull { elem ->
                val itemData = elem.jsonObject.obj("item")?.obj("data") ?: return@mapNotNull null
                if (itemData.str("uri")?.startsWith("spotify:track:") != true) return@mapNotNull null
                parseGqlTrack(itemData)
            }
        }.getOrDefault(emptyList())

    suspend fun searchPlaylists(query: String, limit: Int = 10, offset: Int = 0): List<SpotifyPlaylist> =
        runCatching {
            val vars = searchVars(query, limit, offset)
            val response = graphqlPost(operationName = "searchDesktop", variables = vars)
            val items = searchEntityItems(response, "playlists", "playlistsV2")
                ?: return@runCatching emptyList()
            items.mapNotNull { elem ->
                parseSearchPlaylist(elem.jsonObject)
            }
        }.getOrDefault(emptyList())

    suspend fun searchArtists(query: String, limit: Int = 10, offset: Int = 0): List<SpotifyArtist> =
        runCatching {
            val vars = searchVars(query, limit, offset)
            val response = graphqlPost(operationName = "searchDesktop", variables = vars)
            val items = searchEntityItems(response, "artists")
                ?: return@runCatching emptyList()
            items.mapNotNull { elem ->
                parseSearchArtist(elem.jsonObject)
            }
        }.getOrDefault(emptyList())

    suspend fun searchAlbums(query: String, limit: Int = 10, offset: Int = 0): List<SpotifyAlbum> =
        runCatching {
            val vars = searchVars(query, limit, offset)
            val response = graphqlPost(operationName = "searchDesktop", variables = vars)
            val items = searchEntityItems(response, "albumsV2", "albums")
                ?: return@runCatching emptyList()
            items.mapNotNull { elem ->
                parseSearchAlbum(elem.jsonObject)
            }
        }.getOrDefault(emptyList())

    private fun searchVars(query: String, limit: Int, offset: Int): JsonObject =
        buildJsonObject {
            put("searchTerm", query)
            put("offset", offset)
            put("limit", limit)
            // The API rejects the whole searchV2 response when this is < 1.
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includeArtistHasConcertsField", false)
            put("includePreReleases", false)
            put("includeLocalConcertsField", false)
        }

    /**
     * An artist's top tracks via the queryArtistOverview GQL operation,
     * with a REST fallback.
     */
    suspend fun artistTopTracks(artistId: String, limit: Int = 10): List<SpotifyTrack> {
        val gql = runCatching {
            val vars = buildJsonObject {
                put("artistUri", "spotify:artist:$artistId")
                put("locale", "en")
                put("includePrerelease", false)
            }
            val response = graphqlPost(operationName = "queryArtistOverview", variables = vars)
            val items = response.obj("data")?.obj("artistOverview")
                ?.obj("discography")?.obj("topTracks")?.arr("items")
                ?: return@runCatching emptyList()
            items.mapNotNull { elem ->
                val trackData = elem.jsonObject.obj("track")?.obj("data")
                    ?: elem.jsonObject.obj("data")
                    ?: return@mapNotNull null
                parseGqlTrack(trackData)
            }.take(limit)
        }.getOrDefault(emptyList())
        if (gql.isNotEmpty()) return gql

        return runCatching {
            val token = getEffectiveToken() ?: return@runCatching emptyList()
            val response = restClient.get("https://api.spotify.com/v1/artists/$artistId/top-tracks") {
                header("Authorization", "Bearer $token")
                parameter("market", "from_token")
            }
            if (response.status != HttpStatusCode.OK) return@runCatching emptyList()
            val parsed = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val items = parsed.arr("tracks") ?: return@runCatching emptyList()
            items.mapNotNull { parseRestTrack(it.jsonObject) }.take(limit)
        }.getOrDefault(emptyList())
    }

    /** An album's tracks via the getAlbum GQL operation. */
    suspend fun albumTracks(albumId: String, limit: Int = 50): List<SpotifyTrack> =
        runCatching {
            val vars = buildJsonObject {
                put("uri", "spotify:album:$albumId")
                put("offset", 0)
                put("limit", limit)
            }
            val response = graphqlPost(operationName = "getAlbum", variables = vars)
            val items = response.obj("data")?.obj("album")?.obj("tracks")?.arr("items")
                ?: return@runCatching emptyList()
            items.mapNotNull { elem ->
                val obj = elem.jsonObject
                val trackData = obj.obj("track")?.obj("data")
                    ?: obj.obj("data")
                    ?: obj
                parseGqlTrack(trackData)
            }
        }.getOrDefault(emptyList())

    private fun parseSearchPlaylist(source: JsonObject): SpotifyPlaylist? {
        val data = source.obj("data") ?: source
        val uri = data.str("uri") ?: return null
        if (!uri.startsWith("spotify:playlist:")) return null
        val id = uri.removePrefix("spotify:playlist:")
        val owner = data.obj("owner")?.let { ownerObj ->
            SpotifyPlaylistOwner(
                id = ownerObj.str("id") ?: ownerObj.str("uri")?.substringAfterLast(":") ?: "",
                displayName = ownerObj.obj("profile")?.str("name")
                    ?: ownerObj.str("displayName")
                    ?: ownerObj.str("name"),
                uri = ownerObj.str("uri"),
            )
        } ?: data.obj("ownerV2")?.obj("data")?.let { ownerObj ->
            SpotifyPlaylistOwner(
                id = ownerObj.str("uri")?.substringAfterLast(":") ?: "",
                displayName = ownerObj.str("name") ?: ownerObj.str("username"),
                uri = ownerObj.str("uri"),
            )
        }
        return SpotifyPlaylist(
            id = id,
            name = data.str("name") ?: "",
            images = parseGqlImages(
                data.obj("images")?.arr("items")
                    ?.flatMap { it.jsonObject.arr("sources").orEmpty() }
                    ?.let { JsonArray(it) }
                    ?: data.obj("coverArt")?.arr("sources")
                    ?: data.obj("images")?.arr("sources")
            ),
            owner = owner,
        )
    }

    private fun parseSearchArtist(source: JsonObject): SpotifyArtist? {
        val data = source.obj("data") ?: source
        val uri = data.str("uri") ?: return null
        if (!uri.startsWith("spotify:artist:")) return null
        val id = uri.removePrefix("spotify:artist:")
        val name = data.obj("profile")?.str("name")
            ?: data.str("name") ?: return null
        val image = data.obj("visuals")?.obj("avatarImage")?.arr("sources")?.firstOrNull()
            ?.jsonObject?.str("url")
            ?: data.obj("images")?.arr("items")?.firstOrNull()?.jsonObject
                ?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")
            ?: data.obj("images")?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")
        return SpotifyArtist(
            id = id,
            name = name,
            imageUrl = image
        )
    }

    private fun parseSearchAlbum(source: JsonObject): SpotifyAlbum? {
        val data = source.obj("data") ?: source
        val uri = data.str("uri") ?: return null
        if (!uri.startsWith("spotify:album:")) return null
        val id = uri.removePrefix("spotify:album:")
        val name = data.str("name") ?: return null
        val artistName = data.obj("artists")?.arr("items")?.firstOrNull()
            ?.jsonObject?.obj("profile")?.str("name")
            ?: data.obj("artists")?.arr("items")?.firstOrNull()
                ?.jsonObject?.str("name")
            ?: ""
        val year = data.obj("date")?.int("year")
            ?: data.str("date")?.take(4)?.toIntOrNull()
            ?: data.obj("releaseDate")?.str("year")?.toIntOrNull()
        val image = data.obj("coverArt")?.arr("sources")?.firstOrNull()
            ?.jsonObject?.str("url")
            ?: data.obj("images")?.arr("items")?.firstOrNull()?.jsonObject
                ?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")
            ?: data.obj("images")?.arr("sources")?.firstOrNull()?.jsonObject?.str("url")
        return SpotifyAlbum(
            id = id,
            name = name,
            artistName = artistName,
            year = year,
            imageUrl = image
        )
    }

    private fun parseRestTrack(trackObj: JsonObject): SpotifyTrack? {
        val id = trackObj.str("id") ?: return null
        val artists = trackObj.arr("artists")?.mapNotNull { elem ->
            val artistObj = elem.jsonObject
            val artistId = artistObj.str("id") ?: return@mapNotNull null
            SpotifySimpleArtist(
                id = artistId,
                name = artistObj.str("name") ?: "",
                uri = artistObj.str("uri"),
            )
        }.orEmpty()
        val albumObj = trackObj.obj("album")
        val images = albumObj?.arr("images")?.mapNotNull { elem ->
            val imageObj = elem.jsonObject
            val url = imageObj.str("url") ?: return@mapNotNull null
            SpotifyImage(
                url = url,
                height = imageObj.int("height"),
                width = imageObj.int("width"),
            )
        }.orEmpty()
        return SpotifyTrack(
            id = id,
            name = trackObj.str("name") ?: return null,
            artists = artists,
            album = albumObj?.let {
                SpotifySimpleAlbum(
                    id = it.str("id") ?: "",
                    name = it.str("name") ?: "",
                    images = images,
                )
            },
            durationMs = trackObj.long("duration_ms")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            explicit = trackObj.str("explicit") == "true",
        )
    }
}

/** Returns the highest resolution thumbnail URL from a SpotifyPlaylist. */
fun SpotifyPlaylist.thumbnailUrl(): String? =
    images.let { imgs ->
        imgs.maxByOrNull { it.width ?: 0 }?.url
            ?: imgs.firstOrNull()?.url
    }

/** Returns the highest resolution thumbnail URL from a SpotifyTrack's album art. */
fun SpotifyTrack.thumbnailUrl(): String? =
    album?.images?.let { imgs ->
        imgs.maxByOrNull { it.width ?: 0 }?.url
            ?: imgs.firstOrNull()?.url
    }

/** Builds a YouTube search query from a SpotifyTrack. */
fun SpotifyTrack.buildSearchQuery(): String {
    val artist = artists.firstOrNull()?.name.orEmpty()
    return if (artist.isEmpty()) name else "$artist $name"
}
