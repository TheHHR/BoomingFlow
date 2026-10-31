package com.mardous.booming.extensions.media

import android.content.Context
import com.mardous.booming.R
import com.mardous.booming.data.model.Artist
import com.mardous.booming.data.model.Song
import com.mardous.booming.extensions.utilities.collapseSpaces
import com.mardous.booming.extensions.utilities.deserialize
import com.mardous.booming.util.Preferences
import org.koin.core.context.GlobalContext
import java.util.regex.Pattern

/**
 * Builds the main-artist extraction pattern from the user-configured
 * separator tokens (Library settings → Artist separators).
 *
 * - Letter tokens ("feat", "ft", "with") require a whitespace or bracket
 *   boundary on the left and allow an optional trailing dot; "feat" also
 *   matches "featuring".
 * - "x" (collab notation) requires whitespace on both sides.
 * - Everything else (",", "/", ";", "&", "@", ...) is a literal separator
 *   with optional surrounding whitespace.
 *
 * The original upstream pattern this replaces was:
 * `(.*)([(?\s](feat(uring)?|ft|with)\.?\s?|\s?[/;,]\s?|\s+x\s+)(.*)`
 */
private var cachedPattern: Pair<String, Pattern>? = null
private var cachedExceptions: Pair<String, List<String>>? = null

/**
 * Pattern that matches a single separator occurrence (used with find() to
 * cut the name at every separator, left to right).
 */
private fun currentSeparatorPattern(): Pattern {
    val tokenKey = Preferences.artistSeparatorsRaw
        .deserialize(Preferences.defaultArtistSeparators)
        .joinToString("\u0000")
    cachedPattern?.let { (key, pattern) ->
        if (key == tokenKey) return pattern
    }
    val alternatives = Preferences.artistSeparators.mapNotNull { token ->
        when {
            token.isBlank() -> null
            token.equals("x", ignoreCase = true) -> "\\s+x\\s+"
            token.first().isLetter() -> {
                val word = if (token.equals("feat", ignoreCase = true)) {
                    "(?:feat(?:uring)?|ft)"
                } else {
                    Pattern.quote(token)
                }
                "[(?\\s]$word\\.?\\s?"
            }
            else -> "\\s?${Pattern.quote(token)}\\s?"
        }
    }
    val pattern = if (alternatives.isEmpty()) {
        // Empty separator set: nothing can ever split a name.
        Pattern.compile("(?!x)x")
    } else {
        Pattern.compile(
            alternatives.joinToString("|"),
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }
    cachedPattern = tokenKey to pattern
    return pattern
}

private fun currentExceptions(): List<String> {
    val raw = Preferences.artistSeparatorExceptionsRaw
    cachedExceptions?.let { (key, list) ->
        if (key == raw) return list
    }
    val list = raw.deserialize(emptyList<String>()).map { it.trim() }.filter { it.isNotEmpty() }
    cachedExceptions = (raw ?: "") to list
    return list
}

/**
 * Splits a multi-artist name into its individual artists using the
 * user-configured separators. Exception entries (Library settings →
 * Artist separators) are masked before splitting and restored afterward,
 * so e.g. "JAY-Z" stays whole when "-" is a separator.
 */
fun String.splitArtistName(): List<String> {
    val exceptions = currentExceptions()
    var masked = this
    val placeholders = mutableListOf<Pair<String, String>>()
    exceptions.forEachIndexed { index, exception ->
        if (masked.contains(exception, ignoreCase = true)) {
            val placeholder = "\u0001$index\u0001"
            masked = masked.replace(exception, placeholder, ignoreCase = true)
            placeholders.add(placeholder to exception)
        }
    }

    val parts = mutableListOf<String>()
    val matcher = currentSeparatorPattern().matcher(masked)
    var start = 0
    while (start <= masked.length && matcher.find(start)) {
        // Cut at every separator occurrence left to right, so names built
        // with multiple different separators split completely.
        if (matcher.start() > start) {
            parts.add(masked.substring(start, matcher.start()))
        }
        start = matcher.end()
    }
    if (start < masked.length) {
        parts.add(masked.substring(start))
    }

    val result = parts.map { part ->
        var restored = part
        placeholders.forEach { (placeholder, original) ->
            restored = restored.replace(placeholder, original, ignoreCase = true)
        }
        restored.trim()
    }.filter { it.isNotEmpty() }
    return result.ifEmpty { listOf(trim()) }
}

/**
 * The main artist of a multi-artist name: every part except the last,
 * comma-joined. "A feat. B" -> "A"; "A & B & C" -> "A, B".
 */
fun String.extractMainArtistName(): String {
    val parts = splitArtistName()
    if (parts.size <= 1) return trim()
    return parts.dropLast(1).joinToString(", ").trim()
}

/**
 * Canonical artist spelling: resolves a user-defined alias
 * ("djali" -> "DJ ALI") to its canonical display name, otherwise
 * returns the trimmed name unchanged. Alias matching is exact and
 * case-insensitive, never fuzzy.
 */
fun String.resolveArtistAlias(): String =
    Preferences.canonicalArtistName(this)

/**
 * Split parts with aliases resolved to their canonical names, so
 * merged spellings group into one artist entry. Ignored artists are
 * NOT filtered here — callers keep their existing ignore checks on
 * the resolved name.
 */
fun String.splitCanonicalArtistNames(): List<String> =
    splitArtistName().map { it.resolveArtistAlias() }

/**
 * The names the Go To > Artist picker offers for a song: the song's
 * split artist names with aliases resolved, minus ignored artists.
 * Single-element (or empty) results mean "navigate directly".
 */
fun Song.gotoArtistNames(): List<String> {
    val raw: String? = if (Preferences.onlyAlbumArtists && !albumArtistName.isNullOrEmpty()) {
        albumArtistName
    } else {
        artistName
    }
    return raw?.splitCanonicalArtistNames().orEmpty()
        .filter { !Preferences.isIgnoredArtist(it) }
        .distinct()
}

fun String.displayArtistName(): String {
    val context = GlobalContext.get().get<Context>()
    return when {
        isArtistNameUnknown() -> context.getString(R.string.unknown_artist)
        isVariousArtists() -> context.getString(R.string.various_artists)
        else -> this
    }
}

internal fun String?.isVariousArtists(): Boolean {
    if (isNullOrBlank())
        return false
    if (this == Artist.VARIOUS_ARTISTS_DISPLAY_NAME)
        return true
    return false
}

internal fun String?.isArtistNameUnknown(): Boolean =
    if (isNullOrBlank()) false
    else trim().let { it.equals("unknown", true) || it.equals("<unknown>", true) }