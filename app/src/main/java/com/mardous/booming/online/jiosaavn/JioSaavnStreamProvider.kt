/*
 * Copyright (c) 2026 TheHHR
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.online.jiosaavn

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Hi-Fi direct audio provider for JioSaavn: title/artist search plus
 * on-device DES-ECB CDN link decryption yields 320 kbps AAC streams -
 * above YouTube's ceiling (Opus 160 / AAC 256-inconsistent).
 * Ported from SpatialFlow's source pool.
 */
object JioSaavnStreamProvider {

    private const val TAG = "JioSaavnProvider"
    private const val BASE_URL = "https://www.jiosaavn.com/api.php"
    private const val DES_SECRET_KEY = "38346591"
    private const val MIN_MATCH_SCORE = 80
    private const val MAX_DURATION_TOLERANCE_MS = 8_000L
    private const val METADATA_CACHE_LIMIT = 200
    private val SUFFIX_REGEX = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)$")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** videoId -> queue metadata, recorded when online queue items are built. */
    private val metadataCache = ConcurrentHashMap<String, TrackMetadata>()

    data class TrackMetadata(
        val title: String,
        val artist: String,
        val durationSeconds: Long?
    )

    data class JioSaavnStream(
        val url: String,
        val formatTag: String,
        val trackId: String
    )

    /** Remembers the queue metadata of an online song for later Hi-Fi matching. */
    fun rememberMetadata(videoId: String, title: String, artist: String, durationMs: Long?) {
        if (videoId.isBlank() || title.isBlank()) return
        if (metadataCache.size >= METADATA_CACHE_LIMIT) {
            metadataCache.keys.firstOrNull()?.let { metadataCache.remove(it) }
        }
        metadataCache[videoId] = TrackMetadata(
            title = title,
            artist = artist,
            durationSeconds = durationMs?.takeIf { it > 0 }?.div(1000L)
        )
    }

    fun lookupMetadata(videoId: String): TrackMetadata? = metadataCache[videoId]

    /**
     * Resolves a direct 320 kbps AAC stream from JioSaavn by title, artist
     * and duration. Returns null when no candidate matches strictly enough.
     */
    suspend fun resolveStream(
        title: String,
        artist: String? = null,
        durationSeconds: Long? = null
    ): JioSaavnStream? = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val cleanArtist = artist?.trim().orEmpty()
        if (cleanTitle.isBlank()) return@withContext null

        try {
            val searchQuery = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle
            val candidates = searchSongs(searchQuery)
            if (candidates.isEmpty()) return@withContext null

            val wantedDurationMs = durationSeconds?.takeIf { it > 0 }?.times(1000L)
            val best = candidates
                .filter { !it.isProOnly && it.encryptedMediaUrl.isNotBlank() }
                .map { it to matchScore(cleanTitle, cleanArtist, it, wantedDurationMs) }
                .filter { it.second >= MIN_MATCH_SCORE }
                .maxByOrNull { it.second }
                ?.first ?: return@withContext null

            val decryptedUrl = decryptUrl(best.encryptedMediaUrl)
            if (decryptedUrl.isBlank()) return@withContext null

            val stream320 = formatStreamUrl(decryptedUrl, targetQuality = "320")
            if (!stream320.startsWith("http://") && !stream320.startsWith("https://")) {
                return@withContext null
            }

            JioSaavnStream(
                url = stream320,
                formatTag = "AAC • 320 kbps",
                trackId = best.id
            )
        } catch (e: Exception) {
            Log.w(TAG, "JioSaavn resolution failed: ${e.message}")
            null
        }
    }

    private fun searchSongs(query: String): List<SaavnTrackCandidate> {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        val url = "$BASE_URL?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=android&q=$encodedQuery&p=1&n=10"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("X-Forwarded-For", "49.36.0.1")
            .header("X-Real-IP", "49.36.0.1")
            .header("Cookie", "explicit_content=1")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string()
                if (body.isNullOrBlank()) return emptyList()

                val root = JSONObject(body)
                val results = root.optJSONArray("results") ?: return emptyList()

                val candidates = mutableListOf<SaavnTrackCandidate>()
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val title = item.optString("title")
                    val moreInfo = item.optJSONObject("more_info") ?: JSONObject()
                    val encryptedMediaUrl = moreInfo.optString("encrypted_media_url")
                    val durationSec = moreInfo.optString("duration").toLongOrNull()

                    val artistMap = moreInfo.optJSONObject("artistMap")
                    val primaryArtists = artistMap?.optJSONArray("primary_artists")
                    var artistName = ""
                    if (primaryArtists != null && primaryArtists.length() > 0) {
                        artistName = primaryArtists.optJSONObject(0)?.optString("name").orEmpty()
                    }
                    if (artistName.isBlank()) {
                        artistName = moreInfo.optString("music")
                    }

                    val rights = moreInfo.optJSONObject("rights")
                    val rightsCode = rights?.optString("code").orEmpty()
                    val rightsReason = rights?.optString("reason").orEmpty()
                    val isProOnly = rightsCode == "1" || rightsReason.contains("Pro Only", ignoreCase = true)

                    if (id.isNotBlank() && title.isNotBlank() && encryptedMediaUrl.isNotBlank()) {
                        candidates.add(
                            SaavnTrackCandidate(
                                id = id,
                                title = title,
                                artist = artistName,
                                durationSeconds = durationSec,
                                encryptedMediaUrl = encryptedMediaUrl,
                                isProOnly = isProOnly
                            )
                        )
                    }
                }
                candidates
            }
        } catch (e: Exception) {
            Log.w(TAG, "JioSaavn search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Decrypts the JioSaavn encrypted CDN URL (DES/ECB/PKCS5Padding, key
     * "38346591").
     */
    fun decryptUrl(encryptedUrl: String): String {
        if (encryptedUrl.isBlank()) return ""
        return try {
            val secretKey = SecretKeySpec(DES_SECRET_KEY.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val cleanEncrypted = encryptedUrl.replace("\\/", "/").trim()
            val decodedBytes = Base64.decode(cleanEncrypted, Base64.DEFAULT)
            String(cipher.doFinal(decodedBytes), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.e(TAG, "JioSaavn URL decryption failed: ${e.message}")
            ""
        }
    }

    /** Rewrites the decrypted base URL to the target bitrate suffix. */
    fun formatStreamUrl(decryptedUrl: String, targetQuality: String = "320"): String {
        if (decryptedUrl.isBlank()) return ""
        return if (decryptedUrl.contains(SUFFIX_REGEX)) {
            decryptedUrl.replace(SUFFIX_REGEX) { match ->
                "_${targetQuality}.${match.groupValues[2]}"
            }
        } else {
            decryptedUrl.replace("_96", "_$targetQuality")
        }
    }

    private data class SaavnTrackCandidate(
        val id: String,
        val title: String,
        val artist: String,
        val durationSeconds: Long?,
        val encryptedMediaUrl: String,
        val isProOnly: Boolean
    )

    // ---- Strict track matching (ported from SpatialFlow TrackMatching) ----

    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "feat", "ft", "featuring", "with",
        "to", "in", "for", "on", "by", "at", "from"
    )

    private val VERSION_TOKENS = setOf(
        "remix", "acoustic", "live", "instrumental", "demo", "unplugged"
    )

    private val UNWANTED_CANDIDATE_TOKENS = setOf(
        "karaoke", "tribute", "cover", "slowed", "reverb", "sped up",
        "speed up", "nightcore", "backing track", "instrumental version",
        "piano tribute", "guitar backing", "originally performed", "in the style of"
    )

    private fun matchScore(
        wantedTitle: String,
        wantedArtist: String,
        candidate: SaavnTrackCandidate,
        wantedDurationMs: Long?
    ): Int {
        if (hasUnwantedToken(wantedTitle, candidate.title)) return Int.MIN_VALUE
        val normCandidateArtist = normalize(candidate.artist)
        if (UNWANTED_CANDIDATE_TOKENS.any { normCandidateArtist.contains(it) }) {
            return Int.MIN_VALUE
        }
        if (hasVersionMismatch(wantedTitle, candidate.title)) return Int.MIN_VALUE

        val normWantedTitle = normalizeTitle(wantedTitle)
        val normCandidateTitle = normalizeTitle(candidate.title)
        if (normWantedTitle.isBlank() || normCandidateTitle.isBlank()) return Int.MIN_VALUE

        val normWantedArtist = normalize(wantedArtist)
        val artistScore = if (normWantedArtist.isNotBlank()) {
            if (normCandidateArtist.isBlank()) return Int.MIN_VALUE
            val overlap = if (normWantedArtist == normCandidateArtist) 1.0
            else tokenOverlap(significantTokens(normWantedArtist), significantTokens(normCandidateArtist))
            if (overlap < 0.65) return Int.MIN_VALUE
            when {
                overlap >= 0.95 -> 30
                overlap >= 0.80 -> 25
                else -> 20
            }
        } else {
            15
        }

        val wantedTokens = significantTokens(normWantedTitle)
        val candidateTokens = significantTokens(normCandidateTitle)
        val titleOverlap = if (normWantedTitle == normCandidateTitle) 1.0
        else tokenOverlap(wantedTokens, candidateTokens)
        if (wantedTokens.size <= 2 && titleOverlap < 0.80 && normWantedTitle != normCandidateTitle) {
            return Int.MIN_VALUE
        }
        if (titleOverlap < 0.70) return Int.MIN_VALUE

        val titleScore = when {
            titleOverlap >= 0.95 -> 50
            titleOverlap >= 0.80 -> 40
            else -> 30
        }

        var durationScore = 5
        val candidateDurationMs = candidate.durationSeconds?.takeIf { it > 0 }?.times(1000L)
        if (wantedDurationMs != null && candidateDurationMs != null) {
            val deltaMs = abs(wantedDurationMs - candidateDurationMs)
            if (deltaMs > MAX_DURATION_TOLERANCE_MS) return Int.MIN_VALUE
            durationScore = if (deltaMs <= 3_000L) 20 else 10
        }

        return titleScore + artistScore + durationScore
    }

    private fun significantTokens(value: String): Set<String> =
        value.split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun tokenOverlap(wanted: Set<String>, candidate: Set<String>): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    private fun hasUnwantedToken(wanted: String, candidate: String): Boolean {
        val normWanted = " ${normalize(wanted)} "
        val normCandidate = " ${normalize(candidate)} "
        return UNWANTED_CANDIDATE_TOKENS.any { token ->
            val paddedToken = " $token "
            normCandidate.contains(paddedToken) && !normWanted.contains(paddedToken)
        }
    }

    private fun hasVersionMismatch(wanted: String, candidate: String): Boolean {
        val normWanted = " ${normalize(wanted)} "
        val normCandidate = " ${normalize(candidate)} "
        return VERSION_TOKENS.any { token ->
            val paddedToken = " $token "
            normWanted.contains(paddedToken) != normCandidate.contains(paddedToken)
        }
    }

    private fun normalize(value: String?): String =
        value
            ?.lowercase(Locale.US)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            ?.replace(Regex("\\p{Mn}+"), "")
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    private fun normalizeTitle(value: String): String {
        val cleaned = value
            .replace(Regex("""\s*[[(][^\])]*\b(remaster|remastered|anniversary|deluxe|version|edition|bonus|explicit|clean|official|audio|video)\b[^\])]*[\])]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*-\s*[^-\n]*\b(remaster|remastered|anniversary|deluxe|version|edition|bonus|explicit|clean|official|audio|video)\b.*$""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*[[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE), " ")

        return normalize(cleaned)
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""\b(explicit|clean|remaster|remastered|anniversary|deluxe|edition|audio|official|video|mv|hd|4k)\b"""), " ")
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
