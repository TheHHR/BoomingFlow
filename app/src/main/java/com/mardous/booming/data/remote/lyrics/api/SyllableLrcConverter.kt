package com.mardous.booming.data.remote.lyrics.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Converts the syllable-based lyrics JSON shared by the Kouzu and YouLyPlus
 * v2 endpoints ({type: "Word", lyrics: [{time, text, syllabus: [{time, text}]}]})
 * into Enhanced LRC text with word-level timestamps, which the local parsers
 * already know how to render as karaoke lyrics.
 */
object SyllableLrcConverter {

    fun convert(body: String): String? = runCatching {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        val lyricsArray = root["lyrics"] as? JsonArray ?: return@runCatching null
        if (lyricsArray.isEmpty()) return@runCatching null

        val isWordType = root["type"]?.jsonPrimitive?.content.equals("Word", ignoreCase = true)
        val output = StringBuilder()
        for (element in lyricsArray) {
            val line = element as? JsonObject ?: continue
            val timeMs = line["time"]?.jsonPrimitive?.longOrNull ?: continue
            output.append(formatTimestamp(timeMs, bracketed = true))

            val syllables = line["syllabus"] as? JsonArray
            if (isWordType && !syllables.isNullOrEmpty()) {
                var lastSyllableTime = -1L
                for (syllableElement in syllables) {
                    val syllable = syllableElement as? JsonObject ?: continue
                    val syllableTime = syllable["time"]?.jsonPrimitive?.longOrNull
                    // A syllable without a usable time keeps the line
                    // timestamp: emitting <00:00.00> would create a
                    // zero-millisecond word that sorts first and breaks
                    // the karaoke gradient.
                    val stamp = when {
                        syllableTime != null && syllableTime > 0 -> syllableTime
                        lastSyllableTime > 0 -> lastSyllableTime
                        else -> timeMs
                    }
                    lastSyllableTime = stamp
                    output.append(formatTimestamp(stamp, bracketed = false))
                    output.append(syllable["text"]?.jsonPrimitive?.content ?: "")
                }
            } else {
                output.append(line["text"]?.jsonPrimitive?.content ?: "")
            }
            output.append('\n')
        }
        output.toString().trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun formatTimestamp(timeMs: Long, bracketed: Boolean): String {
        val safeTime = timeMs.coerceAtLeast(0L)
        val minutes = safeTime / 60000L
        val seconds = (safeTime % 60000L) / 1000L
        // Round to the nearest hundredth instead of truncating: up to 10ms
        // per timestamp is lost otherwise, which accumulates visibly for
        // fast syllables.
        val hundredths = (safeTime % 1000L).let { ms ->
            if (ms % 10L >= 5L) ms / 10L + 1L else ms / 10L
        }.coerceAtMost(99L)
        val timestamp = String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
        return if (bracketed) "[$timestamp]" else "<$timestamp>"
    }
}
