package com.mardous.booming.data.remote.lyrics.spatial

import android.util.Log
import java.io.File

/**
 * Cleans song metadata before lyrics search, ported from SpatialFlow.
 * Strips video-style noise from titles (Official Video, 8D, Slowed +
 * Reverb, Bass Boosted...), extracts artist/title from filenames when
 * tags are missing, detects remix/slowed/live/cover variants and
 * splits compound artist names down to the primary artist.
 *
 * Complements the app's existing title cleanup with emoji stripping,
 * artist-first cleaning, filename extraction and language detection.
 */
class MetadataRepair {

    companion object {
        private const val TAG = "MetadataRepair"

        private val NOISE_PAREN = Regex(
            "\\s*[(\\[]\\s*(?:official\\s*(?:music\\s*)?video|" +
                    "official\\s*audio|lyrics?\\s*video|lyric|" +
                    "audio|hd|hq|4k|1080p|720p|full\\s*video|" +
                    "visuali[sz]er|animated|amv|mv|m/v|" +
                    "8d\\s*audio|8d|16d|bass\\s*boosted|" +
                    "slowed\\s*(?:\\+\\s*reverb)?|slowed|reverb|" +
                    "nightcore|daycore|instrumental|karaoke|" +
                    "clean|explicit|" +
                    "from\\s*\"[^\"]*\"|from\\s*'[^']*')\\s*[)\\]]",
            RegexOption.IGNORE_CASE
        )

        private val FEAT_PATTERN = Regex(
            "\\s*[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+[^)\\]]+[)\\]]",
            RegexOption.IGNORE_CASE
        )

        private val TRAILING_NOISE = Regex(
            "\\s*[-–—|]\\s*(?:official\\s*(?:music\\s*)?video|lyrics?|audio|hd|hq)\\s*$",
            RegexOption.IGNORE_CASE
        )

        private val VERSION_REMIX = Regex("(?:^|\\W)(remix|rmx)(?:\\W|$)", RegexOption.IGNORE_CASE)
        private val VERSION_SLOWED = Regex("(?:^|\\W)(slowed|slowed\\s*\\+\\s*reverb)(?:\\W|$)", RegexOption.IGNORE_CASE)
        private val VERSION_LIVE = Regex("(?:^|\\W)(live|concert|unplugged)(?:\\W|$)", RegexOption.IGNORE_CASE)
        private val VERSION_COVER = Regex("(?:^|\\W)(cover|acoustic\\s*cover)(?:\\W|$)", RegexOption.IGNORE_CASE)
        private val VERSION_EXTENDED = Regex("(?:^|\\W)(extended|extended\\s*mix|extended\\s*version)(?:\\W|$)", RegexOption.IGNORE_CASE)
        private val VERSION_EDIT = Regex("(?:^|\\W)(edit|radio\\s*edit)(?:\\W|$)", RegexOption.IGNORE_CASE)

        private val EMOJI_PATTERN = Regex(
            "[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}" +
                    "\\x{1F1E0}-\\x{1F1FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}" +
                    "\\x{FE00}-\\x{FE0F}\\x{1F900}-\\x{1F9FF}\\x{200D}\\x{20E3}" +
                    "\\x{E0020}-\\x{E007F}]+"
        )

        private val DEVANAGARI = Regex("[\\x{0900}-\\x{097F}]")
        private val TAMIL = Regex("[\\x{0B80}-\\x{0BFF}]")
        private val TELUGU = Regex("[\\x{0C00}-\\x{0C7F}]")
        private val GURMUKHI = Regex("[\\x{0A00}-\\x{0A7F}]")
    }

    fun repair(
        rawTitle: String?,
        rawArtist: String?,
        album: String?,
        durationMs: Long,
        filePath: String?,
        videoId: String? = null
    ): TrackMetadata {
        var title = rawTitle?.trim() ?: ""
        var artist = rawArtist?.trim() ?: ""

        if (isMissing(title) || isMissing(artist)) {
            val extracted = extractFromFilename(filePath)
            if (isMissing(title)) title = extracted[1]
            if (isMissing(artist)) artist = extracted[0]
        }

        val version = detectVersion(title)
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)
        val language = detectLanguage(title, artist)

        Log.d(TAG, "Repaired: '$rawTitle' -> '$cleanedTitle' | '$rawArtist' -> '$cleanedArtist' | version=$version | lang=$language")

        return TrackMetadata(
            rawTitle = rawTitle ?: "",
            rawArtist = rawArtist ?: "",
            cleanedTitle = cleanedTitle,
            cleanedArtist = cleanedArtist,
            album = album ?: "",
            durationMs = durationMs,
            filePath = filePath ?: "",
            videoId = videoId,
            version = version,
            detectedLanguage = language
        )
    }

    private fun cleanTitle(title: String?): String {
        if (title.isNullOrEmpty()) return ""
        var cleaned = title
        cleaned = EMOJI_PATTERN.replace(cleaned, "")
        cleaned = NOISE_PAREN.replace(cleaned, "")
        cleaned = FEAT_PATTERN.replace(cleaned, "")
        cleaned = TRAILING_NOISE.replace(cleaned, "")
        cleaned = cleaned.replace(
            "(?i)\\s*[(\\[]\\s*(?:remix|rmx|slowed|reverb|live|cover|acoustic|extended|edit|radio\\s*edit|nightcore|daycore|bass\\s*boosted|8d|16d)\\s*[)\\]]".toRegex(),
            ""
        )
        cleaned = cleaned.replace("\\s+".toRegex(), " ").trim()
        cleaned = cleaned.replace("\\s*[-–—]\\s*$".toRegex(), "").trim()
        return cleaned.ifEmpty { title.trim() }
    }

    private fun cleanArtist(artist: String?): String {
        if (artist.isNullOrEmpty()) return ""
        var cleaned = artist
        cleaned = EMOJI_PATTERN.replace(cleaned, "")
        cleaned = cleaned.replace(
            "(?i)\\s*(?:feat\\.?|ft\\.?|featuring|&|,|\\bx\\b|\\bvs\\.?)\\s+.*$".toRegex(),
            ""
        )
        cleaned = cleaned.replace("\\s+".toRegex(), " ").trim()
        return cleaned.ifEmpty { artist.trim() }
    }

    private fun extractFromFilename(filePath: String?): Array<String> {
        val result = arrayOf("", "")
        if (filePath.isNullOrEmpty()) return result
        try {
            val file = File(filePath)
            var name = file.name
            val dotIndex = name.lastIndexOf('.')
            if (dotIndex > 0) {
                name = name.substring(0, dotIndex)
            }
            val parts = name.split("\\s*[-–—]\\s*".toRegex(), limit = 2)
            if (parts.size == 2) {
                result[0] = parts[0].trim()
                result[1] = parts[1].trim()
            } else {
                result[1] = name.trim()
            }
            result[0] = result[0].replace("_".toRegex(), " ").trim()
            result[1] = result[1].replace("_".toRegex(), " ").trim()
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting from filename: ${e.message}")
        }
        return result
    }

    private fun detectVersion(title: String?): String {
        if (title == null) return "original"
        return when {
            VERSION_REMIX.containsMatchIn(title) -> "remix"
            VERSION_SLOWED.containsMatchIn(title) -> "slowed"
            VERSION_LIVE.containsMatchIn(title) -> "live"
            VERSION_COVER.containsMatchIn(title) -> "cover"
            VERSION_EXTENDED.containsMatchIn(title) -> "extended"
            VERSION_EDIT.containsMatchIn(title) -> "edit"
            else -> "original"
        }
    }

    private fun detectLanguage(title: String?, artist: String?): String {
        val combined = "${title ?: ""} ${artist ?: ""}"
        return when {
            DEVANAGARI.containsMatchIn(combined) -> "hi"
            TAMIL.containsMatchIn(combined) -> "ta"
            TELUGU.containsMatchIn(combined) -> "te"
            GURMUKHI.containsMatchIn(combined) -> "pa"
            combined.contains("(?i).*(?:arijit|atif|shreya|lata|kishore|kumar|sonu|neha|badshah|honey|yo yo).*".toRegex()) -> "hi"
            else -> "en"
        }
    }

    private fun isMissing(value: String?): Boolean {
        return value.isNullOrEmpty() ||
                value.equals("Unknown Title", ignoreCase = true) ||
                value.equals("Unknown Artist", ignoreCase = true) ||
                value.equals("<unknown>", ignoreCase = true)
    }
}
