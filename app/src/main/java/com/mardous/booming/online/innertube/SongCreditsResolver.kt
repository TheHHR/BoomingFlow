package com.mardous.booming.online.data.innertube

import androidx.compose.runtime.Immutable
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Immutable
data class SongCreditsData(
    val title: String,
    val performers: List<String>,
    val writers: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val album: String? = null,
    val releaseDate: String? = null,
    val metadataProvider: String? = null,
    val genres: List<String> = emptyList(),
    val isVerified: Boolean = false
)

object SongCreditsResolver {

    private val cache = ConcurrentHashMap<String, SongCreditsData>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    suspend fun resolveCredits(song: OnlineSong): SongCreditsData = withContext(Dispatchers.IO) {
        val cached = cache[song.videoId]
        if (cached != null) return@withContext cached

        val parsedPerformers = song.artist.split(Regex("[,&]| and | feat\\.? | ft\\.? ", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        val resolvedWriters = mutableListOf<String>()
        val resolvedProducers = mutableListOf<String>()
        var resolvedAlbum = song.albumName
        var resolvedReleaseDate: String? = null
        var resolvedMetadataProvider: String? = null
        val resolvedGenres = mutableListOf<String>()
        var isVerified = false

        // Strategy 1: InnerTube Official Credits Endpoint
        try {
            val nextResponse = InnerTubeClient.next(song.videoId)
            val creditsEndpoint = findCreditsBrowseEndpoint(nextResponse)
            if (creditsEndpoint != null) {
                val browseResponse = InnerTubeClient.browse(creditsEndpoint.first, creditsEndpoint.second)
                val parsed = parseInnerTubeCreditsResponse(browseResponse)
                if (parsed.performers.isNotEmpty()) {
                    parsed.performers.forEach { if (!parsedPerformers.contains(it)) parsedPerformers.add(it) }
                }
                resolvedWriters.addAll(parsed.writers)
                resolvedProducers.addAll(parsed.producers)
                if (parsed.metadataProvider != null) {
                    resolvedMetadataProvider = parsed.metadataProvider
                }
                if (resolvedWriters.isNotEmpty() || resolvedProducers.isNotEmpty() || resolvedMetadataProvider != null) {
                    isVerified = true
                }
            }
        } catch (_: Exception) {
            // Fall through to secondary strategies
        }

        // Strategy 2: YouTube Video Description Parser (Standard YouTube Music Upload Format)
        if (!isVerified || resolvedWriters.isEmpty()) {
            try {
                val playerResp = InnerTubeClient.player(song.videoId)
                val description = playerResp.getAsJsonObject("videoDetails")?.get("shortDescription")?.asString
                if (!description.isNullOrBlank()) {
                    val descCredits = parseDescriptionCredits(description)
                    if (descCredits.writers.isNotEmpty()) resolvedWriters.addAll(descCredits.writers)
                    if (descCredits.producers.isNotEmpty()) resolvedProducers.addAll(descCredits.producers)
                    if (resolvedMetadataProvider.isNullOrBlank() && descCredits.label != null) {
                        resolvedMetadataProvider = descCredits.label
                    }
                    if (resolvedReleaseDate.isNullOrBlank() && descCredits.releaseDate != null) {
                        resolvedReleaseDate = descCredits.releaseDate
                    }
                    if (resolvedAlbum.isNullOrBlank() && descCredits.album != null) {
                        resolvedAlbum = descCredits.album
                    }
                    if (descCredits.writers.isNotEmpty() || descCredits.producers.isNotEmpty() || descCredits.label != null) {
                        isVerified = true
                    }
                }
            } catch (_: Exception) {
                // Fall through to iTunes lookup
            }
        }

        // Strategy 3: Official Metadata from iTunes API (Record Label, Release Date, Album, Genre)
        try {
            val cleanTitle = song.title.replace(Regex("(?i)\\s*\\(.*?video.*?\\)"), "")
                .replace(Regex("(?i)\\s*\\[.*?video.*?\\]"), "")
                .trim()
            val query = "$cleanTitle ${song.artist}"
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=1"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "BoomingFlow/1.4.2 (Android)")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body.string()
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val item = results.getJSONObject(0)
                        if (resolvedAlbum.isNullOrBlank()) {
                            resolvedAlbum = item.optString("collectionName").takeIf { it.isNotBlank() }
                        }
                        if (resolvedReleaseDate.isNullOrBlank()) {
                            val rawDate = item.optString("releaseDate")
                            if (rawDate.length >= 4) {
                                resolvedReleaseDate = formatIsoDate(rawDate)
                            }
                        }
                        if (resolvedMetadataProvider.isNullOrBlank()) {
                            val copyright = item.optString("copyright").takeIf { it.isNotBlank() }
                            resolvedMetadataProvider = copyright
                        }
                        val genre = item.optString("primaryGenreName").takeIf { it.isNotBlank() }
                        if (genre != null && !resolvedGenres.contains(genre)) {
                            resolvedGenres.add(genre)
                        }
                        isVerified = true
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore API lookup errors
        }

        val result = SongCreditsData(
            title = song.title,
            performers = parsedPerformers.ifEmpty { listOf(song.artist) },
            writers = resolvedWriters.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            producers = resolvedProducers.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            album = resolvedAlbum,
            releaseDate = resolvedReleaseDate,
            metadataProvider = resolvedMetadataProvider?.trim()?.takeIf { it.isNotBlank() },
            genres = resolvedGenres.distinct(),
            isVerified = isVerified
        )

        cache[song.videoId] = result
        result
    }

    private fun findCreditsBrowseEndpoint(json: JsonObject): Pair<String, String?>? {
        return searchBrowseEndpoint(json)
    }

    private fun searchBrowseEndpoint(element: JsonElement): Pair<String, String?>? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val navItem = obj.getAsJsonObject("menuNavigationItemRenderer")
            if (navItem != null) {
                val iconType = navItem.getAsJsonObject("icon")?.get("iconType")?.asString.orEmpty()
                val textRuns = navItem.getAsJsonObject("text")?.getAsJsonArray("runs")
                val text = textRuns?.joinToString("") { it.asJsonObject.get("text")?.asString.orEmpty() }.orEmpty()

                if (iconType.equals("GROUPS", ignoreCase = true) || text.contains("credit", ignoreCase = true)) {
                    val browseEndpoint = navItem.getAsJsonObject("navigationEndpoint")?.getAsJsonObject("browseEndpoint")
                    val browseId = browseEndpoint?.get("browseId")?.asString
                    if (!browseId.isNullOrBlank()) {
                        val params = browseEndpoint.get("params")?.asString
                        return Pair(browseId, params)
                    }
                }
            }

            for ((_, child) in obj.entrySet()) {
                val found = searchBrowseEndpoint(child)
                if (found != null) return found
            }
        } else if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                val found = searchBrowseEndpoint(child)
                if (found != null) return found
            }
        }
        return null
    }

    private data class ParsedInnerTubeCredits(
        val performers: List<String> = emptyList(),
        val writers: List<String> = emptyList(),
        val producers: List<String> = emptyList(),
        val metadataProvider: String? = null
    )

    private fun parseInnerTubeCreditsResponse(json: JsonObject): ParsedInnerTubeCredits {
        val performers = mutableListOf<String>()
        val writers = mutableListOf<String>()
        val producers = mutableListOf<String>()
        var provider: String? = null

        fun extractTextRuns(runs: JsonArray?): List<String> {
            if (runs == null) return emptyList()
            return runs.mapNotNull { it.asJsonObject.get("text")?.asString?.trim() }
                .filter { it.isNotBlank() && it != "," && it != "•" }
        }

        fun scanObject(element: JsonElement) {
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                val title = obj.getAsJsonObject("title")?.getAsJsonArray("runs")?.joinToString("") {
                    it.asJsonObject.get("text")?.asString.orEmpty()
                }?.trim().orEmpty()

                val subtitleRuns = obj.getAsJsonObject("subtitle")?.getAsJsonArray("runs")
                val contents = extractTextRuns(subtitleRuns)

                if (title.contains("performed by", ignoreCase = true) || title.contains("artist", ignoreCase = true)) {
                    performers.addAll(contents)
                } else if (title.contains("written by", ignoreCase = true) || title.contains("composer", ignoreCase = true) || title.contains("author", ignoreCase = true)) {
                    writers.addAll(contents)
                } else if (title.contains("produced by", ignoreCase = true) || title.contains("producer", ignoreCase = true)) {
                    producers.addAll(contents)
                } else if (title.contains("source", ignoreCase = true) || title.contains("metadata", ignoreCase = true) || title.contains("provided by", ignoreCase = true)) {
                    if (provider == null && contents.isNotEmpty()) {
                        provider = contents.joinToString(", ")
                    }
                }

                for ((_, child) in obj.entrySet()) {
                    scanObject(child)
                }
            } else if (element.isJsonArray) {
                for (child in element.asJsonArray) {
                    scanObject(child)
                }
            }
        }

        scanObject(json)
        return ParsedInnerTubeCredits(performers, writers, producers, provider)
    }

    private data class ParsedDescCredits(
        val writers: List<String> = emptyList(),
        val producers: List<String> = emptyList(),
        val album: String? = null,
        val label: String? = null,
        val releaseDate: String? = null
    )

    private fun parseDescriptionCredits(description: String): ParsedDescCredits {
        val writers = mutableListOf<String>()
        val producers = mutableListOf<String>()
        var album: String? = null
        var label: String? = null
        var releaseDate: String? = null

        val lines = description.lines().map { it.trim() }.filter { it.isNotBlank() }

        for (line in lines) {
            when {
                line.startsWith("Producer:", ignoreCase = true) || line.startsWith("Co-producer:", ignoreCase = true) -> {
                    val raw = line.substringAfter(":").trim()
                    producers.addAll(raw.split(Regex("[,;/]")).map { it.trim() }.filter { it.isNotBlank() })
                }
                line.startsWith("Composer:", ignoreCase = true) ||
                line.startsWith("Lyricist:", ignoreCase = true) ||
                line.startsWith("Composer  Lyricist:", ignoreCase = true) ||
                line.startsWith("Songwriter:", ignoreCase = true) -> {
                    val raw = line.substringAfter(":").trim()
                    writers.addAll(raw.split(Regex("[,;/]")).map { it.trim() }.filter { it.isNotBlank() })
                }
                line.startsWith("Provided to YouTube by", ignoreCase = true) -> {
                    label = line.substringAfter("Provided to YouTube by").trim()
                }
                line.startsWith("Released on:", ignoreCase = true) -> {
                    releaseDate = line.substringAfter(":").trim()
                }
                line.startsWith("℗") -> {
                    if (label == null) {
                        label = line
                    }
                }
            }
        }

        return ParsedDescCredits(writers, producers, album, label, releaseDate)
    }

    private fun formatIsoDate(isoString: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val date = parser.parse(isoString)
            if (date != null) {
                val formatter = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                formatter.format(date)
            } else {
                isoString.take(4)
            }
        } catch (_: Exception) {
            isoString.take(4)
        }
    }
}
