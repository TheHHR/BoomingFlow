/*
 * Copyright (c) 2026 Christians Martínez Alvarado
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

package com.mardous.booming.data.local.search

import android.util.LruCache
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import java.text.Normalizer

/**
 * Spotify-style in-memory search matching shared by every local search
 * surface (library search, scoped searches, playlist search).
 *
 * Compared to the old SQL `LIKE '%query%'` matching this is relaxed:
 *  - the query is normalized and split into TOKENS; every token must
 *    appear somewhere across the searchable fields, order-free, so
 *    "lose eminem" finds "Lose Yourself" by Eminem;
 *  - normalization folds case, accents, punctuation, zero-width
 *    joiners (Farsi ZWNJ), Arabic presentation forms and tashkeel,
 *    so "beyonce" finds "Beyoncé", "dont" finds "Don't" and the
 *    joined and ZWNJ spellings of a Farsi word match each other;
 *  - results are ranked: title exact > title prefix > title word
 *    starts > title contains > artist > album; ties keep the
 *    underlying order (stable sort);
 *  - a fuzzy (Jaro-Winkler) pass runs ONLY when the strict token
 *    pass found nothing, so typos like "beatels" still find
 *    "The Beatles" without polluting exact matches.
 *
 * Zero-width format chars are REMOVED rather than turned into spaces
 * on purpose: "می‌خوام" (ZWNJ) normalizes to the joined form, which the
 * space-separated tokens of "می خوام" still match as substrings, while
 * the joined query also matches the ZWNJ tag — replacing them with
 * spaces would break the joined-query case.
 */
object SearchMatcher {

    /** Tokens shorter than this never fuzzy-match (too noisy). */
    private const val FUZZY_MIN_TOKEN_LENGTH = 4
    private const val FUZZY_SIMILARITY = 0.85

    private const val SCORE_TITLE_EXACT = 100
    private const val SCORE_TITLE_PREFIX = 90
    private const val SCORE_TITLE_WORDS = 80
    private const val SCORE_TITLE_CONTAINS = 70
    private const val SCORE_ARTIST_EXACT = 60
    private const val SCORE_ARTIST_PREFIX = 55
    private const val SCORE_ARTIST_WORDS = 50
    private const val SCORE_ARTIST_CONTAINS = 45
    private const val SCORE_ALBUM_EXACT = 40
    private const val SCORE_ALBUM_PREFIX = 35
    private const val SCORE_ALBUM_CONTAINS = 30
    private const val SCORE_MIXED_FIELDS = 20

    /**
     * Normalization memo. [normalizeForSearch] is a pure function so a
     * raw-string key is always valid; artist and album strings repeat
     * heavily across a library, so most lookups hit after the first
     * keystroke of a session.
     */
    private val normalizedCache = LruCache<String, String>(8192)

    private val jaroWinkler = JaroWinklerSimilarity()

    private val combiningMarksRegex = Regex("\\p{M}+")
    private val formatCharsRegex = Regex("\\p{Cf}+")
    private val nonAlphanumericRegex = Regex("[^\\p{L}\\p{Nd}]+")
    private val multipleSpacesRegex = Regex("\\s+")

    /**
     * Searchable fields of one item: [primary] is the title (or entity
     * name), [secondary] the artist, [tertiary] the album.
     */
    class SearchableText(
        val primary: String,
        val secondary: String? = null,
        val tertiary: String? = null
    )

    /**
     * Filters [items] against [query] and returns them ranked by
     * relevance (stable sort: ties keep the input order).
     */
    fun <T> filterRanked(items: List<T>, query: String, searchable: (T) -> SearchableText): List<T> {
        val tokens = tokenize(normalizeForSearch(query))
        if (tokens.isEmpty() || items.isEmpty()) return emptyList()
        val phrase = tokens.joinToString(" ")
        val strict = items.mapNotNull { item ->
            score(searchable(item), phrase, tokens, fuzzy = false)?.let { it to item }
        }
        val ranked = if (strict.isNotEmpty()) strict else items.mapNotNull { item ->
            score(searchable(item), phrase, tokens, fuzzy = true)?.let { it to item }
        }
        return ranked.sortedByDescending { it.first }.map { it.second }
    }

    /**
     * Folds the string into a canonical search form: NFKC (folds Arabic
     * presentation forms and full-width characters) plus a few script
     * variants NFKC doesn't touch, then strips accents/diacritics,
     * lowercases, removes zero-width format characters and turns the
     * remaining punctuation into word separators.
     */
    fun normalizeForSearch(input: String): String {
        if (input.isEmpty()) return input
        synchronized(normalizedCache) { normalizedCache.get(input) }?.let { return it }
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
            .replace('\u06CC', '\u064A') // Farsi Yeh ی -> Arabic Yeh ي
            .replace('\u06A9', '\u0643') // Keheh ک -> Kaf ك
            .replace('\u0622', '\u0627') // Alef Madda آ -> Alef ا
            .replace('\u0623', '\u0627') // Alef Hamza Above أ -> Alef ا
            .replace('\u0625', '\u0627') // Alef Hamza Below إ -> Alef ا
            .replace('\u0671', '\u0627') // Alef Wasla ٱ -> Alef ا
            .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            .replace(combiningMarksRegex, "")
            .lowercase()
            .replace(formatCharsRegex, "")
            .replace(nonAlphanumericRegex, " ")
            .trim()
            .replace(multipleSpacesRegex, " ")
        synchronized(normalizedCache) { normalizedCache.put(input, normalized) }
        return normalized
    }

    private fun tokenize(normalized: String): List<String> =
        normalized.split(' ').filter { it.isNotEmpty() }

    /**
     * @return the relevance score, or null when the text doesn't match.
     * A match requires every token to appear in at least one field.
     */
    private fun score(text: SearchableText, phrase: String, tokens: List<String>, fuzzy: Boolean): Int? {
        val title = normalizeForSearch(text.primary)
        val artist = text.secondary?.let { normalizeForSearch(it) }
        val album = text.tertiary?.let { normalizeForSearch(it) }
        val fields = listOfNotNull(
            title.takeIf { it.isNotEmpty() },
            artist?.takeIf { it.isNotEmpty() },
            album?.takeIf { it.isNotEmpty() }
        )
        if (fields.isEmpty()) return null
        if (!tokens.all { token ->
                fields.any { field -> field.contains(token) || (fuzzy && fuzzyMatch(field, token)) }
            }
        ) return null

        var best = SCORE_MIXED_FIELDS
        if (tokens.all { title.contains(it) }) {
            best = when {
                title == phrase -> SCORE_TITLE_EXACT
                title.startsWith(phrase) -> SCORE_TITLE_PREFIX
                tokens.all { wordStarts(title, it) } -> SCORE_TITLE_WORDS
                else -> SCORE_TITLE_CONTAINS
            }
        }
        if (artist != null && artist.isNotEmpty() && tokens.all { artist.contains(it) }) {
            best = maxOf(best, when {
                artist == phrase -> SCORE_ARTIST_EXACT
                artist.startsWith(phrase) -> SCORE_ARTIST_PREFIX
                tokens.all { wordStarts(artist, it) } -> SCORE_ARTIST_WORDS
                else -> SCORE_ARTIST_CONTAINS
            })
        }
        if (album != null && album.isNotEmpty() && tokens.all { album.contains(it) }) {
            best = maxOf(best, when {
                album == phrase -> SCORE_ALBUM_EXACT
                album.startsWith(phrase) -> SCORE_ALBUM_PREFIX
                else -> SCORE_ALBUM_CONTAINS
            })
        }
        return best
    }

    /** Whether [token] starts a word of [field] (the first word included). */
    private fun wordStarts(field: String, token: String): Boolean =
        field.startsWith(token) || field.contains(" $token")

    private fun fuzzyMatch(field: String, token: String): Boolean {
        if (token.length < FUZZY_MIN_TOKEN_LENGTH) return false
        return field.split(' ').any { word ->
            word.length >= FUZZY_MIN_TOKEN_LENGTH && jaroWinkler.apply(token, word) >= FUZZY_SIMILARITY
        }
    }
}
