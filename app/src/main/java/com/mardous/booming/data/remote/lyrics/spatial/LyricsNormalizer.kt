package com.mardous.booming.data.remote.lyrics.spatial

import java.text.Normalizer

/**
 * Query generation + fuzzy title/artist matching, ported from SpatialFlow.
 *
 * Generates several search query variations from the cleaned track
 * metadata so providers can be retried with different formulations.
 * Also owns the word-by-word heuristics used to route synced content
 * into the word-synced slot.
 */
class LyricsNormalizer {

    fun generateQueries(track: TrackMetadata): List<Array<String>> {
        val queries = ArrayList<Array<String>>()

        val artist = track.cleanedArtist
        val title = track.cleanedTitle

        if (!isEmpty(artist) && !isEmpty(title)) {
            queries.add(arrayOf(artist, title))
        }

        if (!isEmpty(title)) {
            queries.add(arrayOf("", title))
        }

        if (!isEmpty(track.rawTitle) && track.rawTitle != title) {
            val rawCleaned = stripDiacritics(track.rawTitle)
            if (!isEmpty(rawCleaned) && rawCleaned != title) {
                queries.add(arrayOf(artist, rawCleaned))
            }
        }

        if (track.version != "original" && !isEmpty(title)) {
            val coreTitle = title.replace(
                "(?i)\\s*(?:remix|rmx|slowed|reverb|live|cover|acoustic|extended|edit|radio\\s*edit)\\s*".toRegex(),
                ""
            ).trim()
            if (coreTitle != title && !isEmpty(coreTitle)) {
                queries.add(arrayOf(artist, coreTitle))
            }
        }

        val stripped = stripDiacritics(title)
        if (stripped != title && !isEmpty(stripped)) {
            queries.add(arrayOf(stripDiacritics(artist), stripped))
        }

        if (!isEmpty(artist) && !isEmpty(title)) {
            queries.add(arrayOf("", "$artist $title"))
        }

        if (!isEmpty(track.rawArtist) && track.rawArtist != artist && !isEmpty(title)) {
            queries.add(arrayOf(track.rawArtist, title))
        }

        return queries
    }

    private fun isEmpty(s: String?): Boolean = s.isNullOrBlank()

    companion object {
        private val LRC_WORD_PATTERN = Regex("""[<(]\d{1,2}:\d{2}[.:]\d{2,3}[>)]""")
        private val TTML_WORD_SPAN_PATTERN =
            Regex("""<span[^>]+(?:begin|ttm:begin)\s*=""", RegexOption.IGNORE_CASE)

        @JvmStatic
        fun checkIsWordByWord(lyricsText: String?): Boolean {
            if (lyricsText.isNullOrEmpty()) return false

            if (LRC_WORD_PATTERN.containsMatchIn(lyricsText)) {
                return true
            }

            val isTtml = lyricsText.contains("<tt") ||
                    lyricsText.contains("<p begin=") ||
                    lyricsText.contains("ttm:begin")
            if (isTtml) {
                return TTML_WORD_SPAN_PATTERN.containsMatchIn(lyricsText)
            }

            return false
        }

        @JvmStatic
        fun normalizeForComparison(input: String?): String {
            if (input == null) return ""
            var normalized = input.lowercase().trim()
            normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
            normalized = normalized.replace("\\p{InCombiningDiacriticalMarks}".toRegex(), "")
            normalized = normalized.replace("[^a-z0-9\\s]".toRegex(), "")
            normalized = normalized.replace("\\s+".toRegex(), " ").trim()
            return normalized
        }

        @JvmStatic
        fun similarity(a: String, b: String): Float {
            val na = normalizeForComparison(a)
            val nb = normalizeForComparison(b)

            if (na.isEmpty() && nb.isEmpty()) return 1.0f
            if (na.isEmpty() || nb.isEmpty()) return 0.0f
            if (na == nb) return 1.0f

            if (na.contains(nb) || nb.contains(na)) {
                val ratio = na.length.coerceAtMost(nb.length).toFloat() /
                        na.length.coerceAtLeast(nb.length)
                return 0.7f.coerceAtLeast(ratio)
            }

            val distance = levenshteinDistance(na, nb)
            val maxLen = na.length.coerceAtLeast(nb.length)
            return 1.0f - (distance.toFloat() / maxLen)
        }

        @JvmStatic
        private fun stripDiacritics(input: String?): String {
            if (input == null) return ""
            val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
            return normalized.replace("\\p{InCombiningDiacriticalMarks}".toRegex(), "").trim()
        }

        @JvmStatic
        private fun levenshteinDistance(a: String, b: String): Int {
            val lenA = a.length
            val lenB = b.length

            var prev = IntArray(lenB + 1) { it }
            var curr = IntArray(lenB + 1)

            for (i in 1..lenA) {
                curr[0] = i
                for (j in 1..lenB) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = (curr[j - 1] + 1)
                        .coerceAtMost(prev[j] + 1)
                        .coerceAtMost(prev[j - 1] + cost)
                }
                val temp = prev
                prev = curr
                curr = temp
            }
            return prev[lenB]
        }
    }
}
