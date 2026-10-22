package com.mardous.booming.online.data.sourcepool

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object TrackMatching {

    const val MIN_MATCH_SCORE = 80
    private const val STRICT_DURATION_TOLERANCE_MS = 6_000L
    private const val MAX_DURATION_TOLERANCE_MS = 8_000L

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

    fun score(
        wantedTitle: String,
        wantedArtists: List<String>,
        candidateTitle: String,
        candidateArtist: String,
        wantedDurationMs: Long?,
        candidateDurationMs: Long?
    ): Int {
        // 1. Blacklist check: reject cheap covers, karaoke, tributes, slowed/reverb on unstripped titles
        if (hasUnwantedToken(wantedTitle, candidateTitle)) return Int.MIN_VALUE
        val normCandidateArtist = normalize(candidateArtist)
        if (UNWANTED_CANDIDATE_TOKENS.any { token -> normCandidateArtist.contains(token) }) {
            return Int.MIN_VALUE
        }

        // 2. Version mismatch check: live vs studio, acoustic vs regular, remix vs original
        if (hasVersionMismatch(wantedTitle, candidateTitle)) {
            return Int.MIN_VALUE
        }

        val normWantedTitle = normalizeTitle(wantedTitle)
        val normCandidateTitle = normalizeTitle(candidateTitle)
        if (normWantedTitle.isBlank() || normCandidateTitle.isBlank()) return Int.MIN_VALUE

        // 3. Mandatory Artist Matching
        val cleanWantedArtists = wantedArtists.map { normalize(it) }.filter { it.isNotBlank() }
        var artistScore = 0
        if (cleanWantedArtists.isNotEmpty()) {
            if (normCandidateArtist.isBlank()) return Int.MIN_VALUE

            val maxArtistOverlap = cleanWantedArtists.maxOf { wantedArt ->
                if (wantedArt == normCandidateArtist) 1.0
                else tokenOverlap(significantTokens(wantedArt), significantTokens(normCandidateArtist))
            }

            // Reject candidate immediately if artist similarity is under 65%
            if (maxArtistOverlap < 0.65) return Int.MIN_VALUE

            artistScore = when {
                maxArtistOverlap >= 0.95 -> 30
                maxArtistOverlap >= 0.80 -> 25
                else -> 20
            }
        } else {
            artistScore = 15 // Neutral if no artist provided
        }

        // 4. Strict Title Matching
        val wantedTokens = significantTokens(normWantedTitle)
        val candidateTokens = significantTokens(normCandidateTitle)
        val titleOverlap = if (normWantedTitle == normCandidateTitle) 1.0
        else tokenOverlap(wantedTokens, candidateTokens)

        // For short titles (<= 2 significant words), require very high overlap (>= 0.8) or exact match
        if (wantedTokens.size <= 2 && titleOverlap < 0.80 && normWantedTitle != normCandidateTitle) {
            return Int.MIN_VALUE
        }
        if (titleOverlap < 0.70) return Int.MIN_VALUE

        val titleScore = when {
            titleOverlap >= 0.95 -> 50
            titleOverlap >= 0.80 -> 40
            else -> 30
        }

        // 5. Strict Duration Matching
        var durationScore = 5 // Neutral fallback
        if (wantedDurationMs != null && candidateDurationMs != null && wantedDurationMs > 0 && candidateDurationMs > 0) {
            val deltaMs = abs(wantedDurationMs - candidateDurationMs)
            if (deltaMs > MAX_DURATION_TOLERANCE_MS) {
                // Hard reject if duration discrepancy exceeds 8 seconds
                return Int.MIN_VALUE
            }
            durationScore = when {
                deltaMs <= 3_000L -> 20
                deltaMs <= STRICT_DURATION_TOLERANCE_MS -> 10
                else -> 0
            }
        }

        return titleScore + artistScore + durationScore
    }

    data class Target(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?
    )

    data class Candidate(
        val id: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?
    )

    fun best(
        target: Target,
        candidates: List<Candidate>
    ): Candidate? {
        val cleanWantedArtists = target.artists.map { normalize(it) }.filter { it.isNotBlank() }
        val cleanWantedTitle = normalizeTitle(target.title)

        return candidates
            .asSequence()
            .map { candidate ->
                // Check all candidate artists for the best match, not just the first
                val candidateArtistStr = candidate.artists.joinToString(" ")
                val matchScore = score(
                    wantedTitle = cleanWantedTitle,
                    wantedArtists = cleanWantedArtists,
                    candidateTitle = normalizeTitle(candidate.title),
                    candidateArtist = candidateArtistStr,
                    wantedDurationMs = target.durationMs,
                    candidateDurationMs = candidate.durationMs
                )
                candidate to matchScore
            }
            .filter { (_, score) -> score >= MIN_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun significantTokens(value: String): Set<String> =
        value
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()

    private fun tokenOverlap(
        wanted: Set<String>,
        candidate: Set<String>
    ): Double {
        if (wanted.isEmpty() || candidate.isEmpty()) return 0.0
        val shared = wanted.intersect(candidate).size
        return shared.toDouble() / wanted.size.coerceAtLeast(candidate.size).toDouble()
    }

    fun durationMatches(
        wantedDurationMs: Long?,
        candidateDurationMs: Long?
    ): Boolean {
        if (wantedDurationMs == null || candidateDurationMs == null) return true
        return abs(wantedDurationMs - candidateDurationMs) <= STRICT_DURATION_TOLERANCE_MS
    }

    private fun hasUnwantedToken(wanted: String, candidate: String): Boolean {
        val normWanted = " ${normalize(wanted)} "
        val normCandidate = " ${normalize(candidate)} "
        return UNWANTED_CANDIDATE_TOKENS.any { token ->
            val paddedToken = " $token "
            normCandidate.contains(paddedToken) && !normWanted.contains(paddedToken)
        }
    }

    private fun hasVersionMismatch(
        wanted: String,
        candidate: String
    ): Boolean {
        val normWanted = " ${normalize(wanted)} "
        val normCandidate = " ${normalize(candidate)} "
        return VERSION_TOKENS.any { token ->
            val paddedToken = " $token "
            normWanted.contains(paddedToken) != normCandidate.contains(paddedToken)
        }
    }

    fun normalize(value: String?): String =
        value
            ?.lowercase(Locale.US)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            ?.replace(Regex("\\p{Mn}+"), "")
            ?.replace(Regex("[^a-z0-9]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

    fun normalizeTitle(value: String): String {
        val cleaned = value
            .replace(Regex("""\s*[\[(][^\])]*\b(remaster|remastered|anniversary|deluxe|version|edition|bonus|explicit|clean|official|audio|video)\b[^\])]*[\])]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*-\s*[^-\n]*\b(remaster|remastered|anniversary|deluxe|version|edition|bonus|explicit|clean|official|audio|video)\b.*$""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*[\[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE), " ")

        return normalize(cleaned)
            .replace(Regex("""\b(feat|ft|featuring)\b.*$"""), "")
            .replace(Regex("""\b(explicit|clean|remaster|remastered|anniversary|deluxe|edition|audio|official|video|mv|hd|4k)\b"""), " ")
            .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun searchTitle(value: String): String =
        value
            .trim()
            .replace(Regex("""\s*[\[(]\s*(feat\.?|ft\.?|featuring)\b.*?[\])]""", RegexOption.IGNORE_CASE), "")
            .replace(
                Regex("""\s*-\s*(explicit|clean|remaster(?:ed)?|audio|official)\b.*$""", RegexOption.IGNORE_CASE),
                ""
            )
            .replace(Regex("\\s+"), " ")
            .trim()

    fun searchArtist(value: String): String =
        value
            .trim()
            .substringBefore(',')
            .replace(Regex("\\s+"), " ")
            .trim()
}
