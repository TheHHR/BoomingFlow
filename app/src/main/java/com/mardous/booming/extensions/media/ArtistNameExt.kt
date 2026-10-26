package com.mardous.booming.extensions.media

import android.content.Context
import com.mardous.booming.R
import com.mardous.booming.data.model.Artist
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

private fun currentArtistNamePattern(): Pattern {
    val tokenKey = Preferences.artistSeparators.joinToString("\u0000")
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
        Pattern.compile("(.*)(${alternatives.joinToString("|")})(.*)")
    }
    cachedPattern = tokenKey to pattern
    return pattern
}

fun String.extractMainArtistName(): String {
    val matcher = currentArtistNamePattern().matcher(lowercase())
    if (matcher.matches()) {
        val goIndex = matcher.start(2)
        return this.substring(0, goIndex).trim()
    }
    return this
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