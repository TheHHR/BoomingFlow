package com.mardous.booming.data.local.lyrics.lrc

import com.mardous.booming.data.model.lyrics.LyricsActor
import com.mardous.booming.data.model.lyrics.SyncedLyrics

data class LrcNode(
    val start: Long,
    val text: String?,
    var bgText: String?,
    var rawLine: String?,
    var actor: LyricsActor? = null
) {

    private val children = mutableListOf<LrcNode>()
    var end: Long = INVALID_DURATION

    fun addChild(start: Long, end: Long = INVALID_DURATION, text: String?, actor: LyricsActor?): Boolean {
        if (start > INVALID_DURATION) {
            val node = LrcNode(
                start = start,
                text = text,
                bgText = null,
                rawLine = null,
                actor = actor
            )
            node.end = end
            return children.add(node)
        }
        return false
    }

    private fun toWord(startIndex: Int, trimEnd: Boolean = false): SyncedLyrics.Word {
        val currentText = checkNotNull(text) { "Word text cannot be null" }
        val wordText = if (trimEnd) currentText.trimEnd() else currentText
        // Malformed tags can carry an end before the start; the model
        // rejects negative durations.
        val wordEnd = end.coerceAtLeast(start)
        return SyncedLyrics.Word(
            content = wordText,
            start = start,
            startIndex = startIndex,
            end = wordEnd,
            endIndex = startIndex + (wordText.length - 1),
            duration = (wordEnd - start),
            actor = actor
        )
    }

    fun getTextContent(): SyncedLyrics.TextContent {
        if (children.isEmpty()) {
            return getLineSyncedTextContent()
        }

        val sortedChildren = children.sortedBy { it.start }
        val validChildren = sortedChildren.filterNot { it.text.isNullOrEmpty() }

        if (validChildren.isEmpty()) {
            return getLineSyncedTextContent()
        }

        // Fill missing word end times from the next child's start - over ALL
        // children, including empty-text ones: eLRC writers emit explicit
        // end markers (`word <end>` tags), which arrive as children whose
        // only payload is the timestamp.
        for (i in sortedChildren.indices) {
            val child = sortedChildren[i]
            if (child.end == INVALID_DURATION) {
                sortedChildren.getOrNull(i + 1)?.let { next ->
                    child.end = next.start.coerceAtLeast(child.start)
                }
            }
        }

        val lastWordIndex = validChildren.lastIndex
        val lastWord = validChildren[lastWordIndex]
        if (lastWord.end == INVALID_DURATION) {
            // No end marker for the last word: fill to the line end, but
            // cap the synthesized fill so the karaoke gradient doesn't
            // keep sweeping through instrumental gaps (the line end is
            // the next line's start, gap included).
            val median = medianDuration(sortedChildren)
            lastWord.end = (lastWord.start + maxOf(1_500L, median * 3)).coerceAtMost(end)
            if (lastWord.end < lastWord.start) lastWord.end = lastWord.start
        }

        var nextWordStartIndex = 0
        val words = mutableListOf<SyncedLyrics.Word>()
        for ((index, child) in validChildren.withIndex()) {
            if (index == lastWordIndex && child.text.isNullOrBlank())
                continue

            val trimEnd = if (index == lastWordIndex) true else {
                val nextText = validChildren[index + 1].text
                nextText.isNullOrBlank() || nextText.startsWith(" ")
            }

            val word = child.toWord(nextWordStartIndex, trimEnd = trimEnd)
            if (words.add(word)) {
                nextWordStartIndex += word.content.length
            }
        }

        return SyncedLyrics.TextContent(
            content = words.filterNot { it.isBackground }
                .joinToString(separator = "") { it.content }.trim(),
            backgroundContent = words.filter { it.isBackground }
                .joinToString(separator = "") { it.content }.trim(),
            rawContent = rawLine.orEmpty(),
            syllables = words
        )
    }

    private fun medianDuration(children: List<LrcNode>): Long {
        val durations = children.mapNotNull { child ->
            if (child.end > INVALID_DURATION && child.end > child.start) child.end - child.start else null
        }
        if (durations.isEmpty()) return 0L
        return durations.sorted()[durations.size / 2]
    }

    private fun getLineSyncedTextContent() = SyncedLyrics.TextContent(
        content = text.orEmpty(),
        backgroundContent = null,
        rawContent = rawLine.orEmpty(),
        syllables = emptyList()
    )

    fun toLine(): SyncedLyrics.Line? {
        if (start <= INVALID_DURATION && end <= INVALID_DURATION) {
            return null
        }
        return SyncedLyrics.Line(
            start = start,
            end = end,
            duration = (end - start),
            content = getTextContent(),
            transliteration = null,
            translation = null,
            actor = actor
        )
    }

    companion object {
        const val INVALID_DURATION = -1L
    }
}