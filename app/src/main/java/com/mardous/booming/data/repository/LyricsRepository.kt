package com.mardous.booming.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.mardous.booming.data.local.EditTarget
import com.mardous.booming.data.local.MetadataReader
import com.mardous.booming.data.local.MetadataWriter
import com.mardous.booming.data.local.lyrics.LyricsInfo
import com.mardous.booming.data.local.lyrics.LyricsParser
import com.mardous.booming.data.local.lyrics.lrc.LrcLyricsParser
import com.mardous.booming.data.local.lyrics.ttml.TtmlLyricsParser
import com.mardous.booming.data.local.room.LyricsDao
import com.mardous.booming.data.local.room.LyricsEntity
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.LyricsFile
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.ParsedLyrics
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.data.model.network.NetworkFeature
import com.mardous.booming.data.remote.lyrics.LyricsDownloadService
import com.mardous.booming.data.remote.lyrics.LyricsProviderParams
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.extensions.hasR
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.util.MARK_INSTRUMENTAL_TRACKS_BY_TITLE
import com.mardous.booming.util.Preferences.requireString
import kotlinx.coroutines.CancellationException
import org.mozilla.universalchardet.UniversalDetector
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.regex.Pattern

interface LyricsRepository {
    suspend fun parseRawLyrics(song: Song, rawLyrics: RawLyrics): ParsedLyrics

    suspend fun fileLyrics(song: Song): RawLyrics.File?
    suspend fun embeddedLyrics(song: Song): RawLyrics.Embedded?
    suspend fun storedLyrics(
        song: Song,
        allowDownload: Boolean,
        preferWordSynced: Boolean = false
    ): RawLyrics.Stored?
    suspend fun downloadLyrics(song: Song, searchTitle: String, searchArtist: String, providerParams: LyricsProviderParams): RawLyrics.Remote?

    /**
     * Fetches lyrics from a single provider (ignoring the enable/wifi
     * settings, like the editor does), saves them as the song's stored
     * lyrics and returns the parsed result. Used by the fullscreen lyrics
     * provider switch.
     */
    suspend fun applyProviderLyrics(song: Song, provider: LyricsProvider): ParsedLyrics

    /**
     * False once a word-by-word upgrade sweep already completed without
     * finding a word-by-word version for this song's stored lyrics.
     */
    fun canAttemptWordSyncedUpgrade(song: Song): Boolean

    suspend fun saveLyrics(
        song: Song,
        originalLyricsBySource: Map<LyricsSource, RawLyrics?>,
        newContentBySource: Map<LyricsSource, String>
    ): Boolean?

    suspend fun writableUris(song: Song): List<Uri>
    suspend fun deleteAllLyrics()
}

class RealLyricsRepository(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val lyricsDownloadService: LyricsDownloadService,
    private val lyricsDao: LyricsDao
) : LyricsRepository {

    private val memoryCache = LruCache<Long, Map<LyricsSource, RawLyrics>>(20)

    /**
     * Songs whose provider sweep recently found nothing. A miss suppresses
     * further network sweeps for [NEGATIVE_CACHE_TTL_MS] so replaying a song
     * without lyrics doesn't repeat the full race on every play. The
     * editor's manual download bypasses this via [downloadLyrics].
     */
    private val negativeCache = HashMap<Long, Long>()

    /**
     * Songs whose stored lyrics already went through a background
     * word-by-word upgrade sweep without finding a word-by-word version.
     * Cleared when the user saves or switches lyrics.
     */
    private val upgradeAttempted = HashSet<Long>()

    private val charsetDetector = UniversalDetector()

    private val lrcLyricsParser = LrcLyricsParser()
    private val ttmlLyricsParser = TtmlLyricsParser()

    private val lyricsParsers = listOf(lrcLyricsParser, ttmlLyricsParser)

    override suspend fun parseRawLyrics(song: Song, rawLyrics: RawLyrics): ParsedLyrics {
        val ignoreBlankLines = preferences.getBoolean(IGNORE_BLANK_LINES, false)
        val markInstrumentalByTitle = preferences.getBoolean(MARK_INSTRUMENTAL_TRACKS_BY_TITLE, false)
        try {
            return when (rawLyrics) {
                is RawLyrics.File -> {
                    parseTextLyrics(
                        song = song,
                        lyrics = rawLyrics.lyrics,
                        ignoreBlankLines = ignoreBlankLines,
                        markInstrumentalByTitle = markInstrumentalByTitle,
                        lyricsParsers = listOf(
                            when (rawLyrics.file.format) {
                                LyricsFile.Format.LRC -> lrcLyricsParser
                                LyricsFile.Format.TTML -> ttmlLyricsParser
                            }
                        )
                    )
                }

                is RawLyrics.Embedded -> {
                    parseTextLyrics(
                        song = song,
                        lyrics = rawLyrics.lyrics,
                        ignoreBlankLines = ignoreBlankLines,
                        markInstrumentalByTitle = markInstrumentalByTitle,
                    )
                }

                is RawLyrics.Stored -> {
                    if (rawLyrics.instrumental) {
                        ParsedLyrics.Instrumental
                    } else {
                        parseTextLyrics(
                            song = song,
                            lyrics = rawLyrics.lyrics,
                            ignoreBlankLines = ignoreBlankLines,
                            markInstrumentalByTitle = markInstrumentalByTitle,
                            provider = rawLyrics.provider
                        )
                    }
                }

                is RawLyrics.Edited -> {
                    if (rawLyrics.instrumental) {
                        ParsedLyrics.Instrumental
                    } else {
                        parseTextLyrics(
                            song = song,
                            lyrics = rawLyrics.lyrics,
                            ignoreBlankLines = ignoreBlankLines,
                            markInstrumentalByTitle = markInstrumentalByTitle,
                            provider = rawLyrics.newContentProvider
                        )
                    }
                }

                is RawLyrics.Remote -> {
                    parseTextLyrics(
                        song = song,
                        lyrics = rawLyrics.lyrics,
                        ignoreBlankLines = ignoreBlankLines,
                        markInstrumentalByTitle = markInstrumentalByTitle,
                        provider = rawLyrics.synced?.source ?: rawLyrics.plain?.source
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't parse lyrics for song ${song.data}", e)
        }
        return ParsedLyrics.Empty
    }

    private fun parseTextLyrics(
        song: Song,
        lyrics: String?,
        ignoreBlankLines: Boolean,
        markInstrumentalByTitle: Boolean,
        lyricsParsers: List<LyricsParser> = this.lyricsParsers,
        provider: String? = null
    ): ParsedLyrics {
        if (lyrics.isNullOrBlank()) return ParsedLyrics.Empty

        for (parser in lyricsParsers) {
            return when (val info = parser.getInfo(lyrics)) {
                is LyricsInfo.Instrumental -> ParsedLyrics.Instrumental
                is LyricsInfo.Valid -> {
                    if (info.actuallySynced) {
                        val synced = parser.parse(lyrics, song.duration, ignoreBlankLines)
                            ?.let { if (provider != null) it.copy(provider = provider) else it }
                        if (synced != null) ParsedLyrics.Synced(synced) else ParsedLyrics.Empty
                    } else {
                        val plain = parser.parseAsPlain(lyrics)
                        if (plain != null) ParsedLyrics.Plain(plain, provider) else ParsedLyrics.Empty
                    }
                }
                is LyricsInfo.Invalid -> continue
            }
        }
        return if (markInstrumentalByTitle && INSTRUMENTAL_REGEX.matches(song.title)) {
            ParsedLyrics.Instrumental
        } else {
            ParsedLyrics.Plain(lyrics, provider)
        }
    }

    private fun parseSyncedLyrics(song: Song, lyrics: String): SyncedLyrics? {
        for (parser in lyricsParsers) {
            val info = parser.getInfo(lyrics)
            if (info is LyricsInfo.Valid && info.actuallySynced) {
                return parser.parse(
                    lyrics,
                    song.duration,
                    preferences.getBoolean(IGNORE_BLANK_LINES, false)
                )
            }
        }
        return null
    }

    private fun isWordSyncedText(song: Song, lyrics: String?): Boolean =
        lyrics != null && parseSyncedLyrics(song, lyrics)?.isWordSynced == true

    override suspend fun fileLyrics(song: Song): RawLyrics.File? {
        getCachedLyrics<RawLyrics.File>(LyricsSource.File, song.id)?.let { return it }
        try {
            val preferredFormatValue =
                preferences.requireString("preferred_lyrics_file_format", "ttml")
            val preferredFormat =
                LyricsFile.Format.entries.firstOrNull { it.value == preferredFormatValue }

            val rawLyricsList = mutableListOf<RawLyrics.File>()
            for (file in findLyricsFiles(song)) {
                val actualFile = File(file.path)
                val lyrics = runCatching {
                    actualFile.inputStream().buffered().use { stream ->
                        val charset = detectEncoding(stream)
                        stream.reader(charset).use { it.readText() }
                    }
                }.getOrNull() ?: continue

                if (lyrics.isNotEmpty()) {
                    val rawLyrics = RawLyrics.File(file, lyrics)
                    if (file.format == preferredFormat) {
                        return cacheLyrics(song.id, rawLyrics)
                    }
                    rawLyricsList.add(rawLyrics)
                }
            }

            return rawLyricsList.firstOrNull()?.let {
                cacheLyrics(song.id, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't find/read lyrics files for song ${song.data}", e)
        }
        return null
    }

    override suspend fun embeddedLyrics(song: Song): RawLyrics.Embedded? {
        if (song.id != Song.emptySong.id) {
            getCachedLyrics<RawLyrics.Embedded>(LyricsSource.Embedded, song.id)?.let { return it }
            try {
                val metadataReader = MetadataReader(song.uri)
                var lyrics = metadataReader.value(MetadataReader.LYRICS)
                if (lyrics.isNullOrEmpty()) {
                    lyrics = metadataReader.value("UNSYNCEDLYRICS")
                }
                return cacheLyrics(song.id, RawLyrics.Embedded(lyrics))
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't read embedded lyrics for song ${song.data}", e)
            }
        }
        return null
    }

    override suspend fun storedLyrics(
        song: Song,
        allowDownload: Boolean,
        preferWordSynced: Boolean
    ): RawLyrics.Stored? {
        if (song.id == Song.emptySong.id) return null
        try {
            val cached = getCachedLyrics<RawLyrics.Stored>(LyricsSource.Downloaded, song.id)
            if (cached != null && (!preferWordSynced || isWordSyncedText(song, cached.lyrics))) {
                return cached
            }
            val storedRow = lyricsDao.getLyrics(song.id)
            val stored = storedRow?.let {
                RawLyrics.Stored(
                    lyrics = it.lyrics,
                    provider = it.provider,
                    instrumental = it.instrumental
                )
            }
            if (stored != null &&
                (!preferWordSynced || stored.instrumental || isWordSyncedText(song, stored.lyrics))
            ) {
                return cacheLyrics(song.id, stored)
            }
            if (allowDownload && !isRecentlyMissed(song.id)) {
                // The upgrade attempt is failure-isolated: a network error
                // must never discard the stored lyrics.
                val sweep = try {
                    lyricsDownloadService.remoteLyricsDetailed(
                        song,
                        providerParams = LyricsProviderParams(preferWordSynced = preferWordSynced)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val storableLyrics = sweep?.remote?.prepareToStore()
                if (storableLyrics != null) {
                    if (storableLyrics.instrumental) {
                        lyricsDao.insertLyrics(
                            LyricsEntity(song.id, instrumental = true)
                        )
                        return cacheLyrics(song.id, storableLyrics)
                    }
                    val candidate = RawLyrics.Stored(
                        lyrics = storableLyrics.lyrics,
                        provider = storableLyrics.provider
                    )
                    // A word-synced fetch replaces a stored row that lacks
                    // word timestamps.
                    if (stored == null || isWordSyncedText(song, candidate.lyrics)) {
                        lyricsDao.insertLyrics(
                            LyricsEntity(
                                id = song.id,
                                lyrics = storableLyrics.lyrics,
                                provider = storableLyrics.provider
                            )
                        )
                        clearUpgradeAttempted(song.id)
                        return cacheLyrics(song.id, candidate)
                    }
                    if (stored != null && sweep?.sweptAll == true) {
                        // Every provider answered and none of them had a
                        // word-by-word version - stop sweeping on replays.
                        markUpgradeAttempted(song.id)
                    }
                } else if (sweep != null && sweep.sweptAll && NetworkFeature.isOnline()) {
                    // Only a sweep where every provider genuinely finished and
                    // reported nothing counts as a miss. Timeout-truncated or
                    // failed sweeps are retried on the next play instead of
                    // showing "no lyrics" for the next half hour.
                    recordMiss(song.id)
                    if (stored != null) markUpgradeAttempted(song.id)
                }
            }
            return stored?.let { cacheLyrics(song.id, it) } ?: cached
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't fetch/download lyrics for song ${song.data}", e)
        }
        return null
    }

    override suspend fun downloadLyrics(
        song: Song,
        searchTitle: String,
        searchArtist: String,
        providerParams: LyricsProviderParams
    ): RawLyrics.Remote? {
        if (song.id == Song.emptySong.id || searchArtist.isArtistNameUnknown()) {
            return null
        }
        return try {
            lyricsDownloadService.remoteLyrics(song, searchTitle, searchArtist, providerParams)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun applyProviderLyrics(song: Song, provider: LyricsProvider): ParsedLyrics {
        if (song.id == Song.emptySong.id) return ParsedLyrics.Empty

        val params = LyricsProviderParams(
            providers = listOf(provider),
            ignoreWifiSetting = true,
            ignoreProviderSetting = true
        )
        val remote = try {
            lyricsDownloadService.remoteLyrics(song, song.title, song.artistName, params)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return ParsedLyrics.Empty

        val stored = remote.prepareToStore() ?: return ParsedLyrics.Empty
        lyricsDao.insertLyrics(
            if (stored.instrumental) {
                LyricsEntity(song.id, instrumental = true)
            } else {
                LyricsEntity(
                    id = song.id,
                    lyrics = stored.lyrics,
                    provider = stored.provider
                )
            }
        )
        // The user picked these lyrics on purpose; a previous miss must not
        // suppress anything anymore.
        clearNegativeCache(song.id)
        clearUpgradeAttempted(song.id)
        return parseRawLyrics(song, cacheLyrics(song.id, stored))
    }

    override fun canAttemptWordSyncedUpgrade(song: Song): Boolean =
        !isUpgradeAttempted(song.id)

    override suspend fun saveLyrics(
        song: Song,
        originalLyricsBySource: Map<LyricsSource, RawLyrics?>,
        newContentBySource: Map<LyricsSource, String>
    ): Boolean? {
        try {
            val editedLyrics = newContentBySource.mapNotNull { (source, content) ->
                if (source == LyricsSource.File) return@mapNotNull null

                val originalLyrics = originalLyricsBySource[source] ?: when (source) {
                    LyricsSource.Embedded -> RawLyrics.Embedded(null)
                    LyricsSource.Downloaded -> RawLyrics.Stored()
                }
                if (originalLyrics.lyrics != content) {
                    RawLyrics.Edited(
                        originalLyrics = originalLyrics,
                        newContent = content
                    )
                } else null
            }

            if (editedLyrics.isEmpty()) {
                return null
            }

            return editedLyrics.all {
                when (it.originalLyrics) {
                    is RawLyrics.Embedded -> {
                        runCatching {
                            val metadataWriter = MetadataWriter()
                            metadataWriter.propertyMap(hashMapOf(MetadataReader.LYRICS to it.newContent))
                            metadataWriter.write(this.context, EditTarget.song(song)).isSuccess
                        }.getOrDefault(false).also { success ->
                            if (success) removeCachedLyrics(LyricsSource.Embedded, song.id)
                        }
                    }

                    is RawLyrics.Stored -> {
                        runCatching {
                            lyricsDao.insertLyrics(
                                LyricsEntity(
                                    id = song.id,
                                    lyrics = it.newContent,
                                    provider = it.newContentProvider,
                                    instrumental = it.instrumental
                                )
                            )
                            true
                        }.getOrDefault(false).also { success ->
                            if (success) {
                                removeCachedLyrics(LyricsSource.Downloaded, song.id)
                                clearNegativeCache(song.id)
                                clearUpgradeAttempted(song.id)
                            }
                        }
                    }

                    else -> false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while saving lyrics for song ${song.data}", e)
        }
        return false
    }

    override suspend fun writableUris(song: Song): List<Uri> {
        if (hasR()) {
            return listOf(song.uri).filterNot { it == Uri.EMPTY }
        }
        return emptyList()
    }

    override suspend fun deleteAllLyrics() {
        lyricsDao.removeLyrics()
        memoryCache.evictAll()
        synchronized(negativeCache) { negativeCache.clear() }
        synchronized(upgradeAttempted) { upgradeAttempted.clear() }
    }

    private fun isUpgradeAttempted(songId: Long): Boolean =
        synchronized(upgradeAttempted) { songId in upgradeAttempted }

    private fun markUpgradeAttempted(songId: Long) {
        synchronized(upgradeAttempted) { upgradeAttempted.add(songId) }
    }

    private fun clearUpgradeAttempted(songId: Long) {
        synchronized(upgradeAttempted) { upgradeAttempted.remove(songId) }
    }

    private fun isRecentlyMissed(songId: Long): Boolean {
        val missedAt = synchronized(negativeCache) { negativeCache[songId] } ?: return false
        return System.currentTimeMillis() - missedAt < NEGATIVE_CACHE_TTL_MS
    }

    private fun recordMiss(songId: Long) {
        synchronized(negativeCache) {
            val now = System.currentTimeMillis()
            negativeCache.entries.removeIf { now - it.value >= NEGATIVE_CACHE_TTL_MS }
            negativeCache[songId] = now
        }
    }

    private fun clearNegativeCache(songId: Long) {
        synchronized(negativeCache) { negativeCache.remove(songId) }
    }

    private inline fun <reified T : RawLyrics> getCachedLyrics(
        source: LyricsSource,
        songId: Long
    ): T? {
        val cachedLyrics = memoryCache[songId]
        if (cachedLyrics != null && cachedLyrics.containsKey(source)) {
            return cachedLyrics[source] as? T
        }
        return null
    }

    private fun <T : RawLyrics> cacheLyrics(songId: Long, lyrics: T): T {
        val source = when (lyrics) {
            is RawLyrics.Embedded -> LyricsSource.Embedded
            is RawLyrics.Stored -> LyricsSource.Downloaded
            is RawLyrics.File -> LyricsSource.File
            else -> null
        }
        if (source != null) {
            val cachedLyrics = memoryCache[songId]?.toMutableMap() ?: mutableMapOf()
            cachedLyrics[source] = lyrics
            memoryCache.put(songId, cachedLyrics)
        }
        return lyrics
    }

    private fun removeCachedLyrics(source: LyricsSource, songId: Long) {
        val cachedLyrics = memoryCache[songId]?.toMutableMap()
        if (cachedLyrics != null) {
            cachedLyrics.remove(source)
            memoryCache.put(songId, cachedLyrics)
        }
    }

    private fun findLyricsFiles(song: Song): List<LyricsFile> {
        val songFile = File(song.data)
        val parentDir = songFile.parentFile ?: return emptyList()

        val baseNames = listOf(
            songFile.nameWithoutExtension,
            "${song.artistName} - ${song.title}"
        ).filter { it.isNotBlank() }.map { Pattern.quote(it) }

        val patterns = baseNames.map { base ->
            Regex(".*$base.*\\.(lrc|ttml)", RegexOption.IGNORE_CASE)
        }

        return parentDir.listFiles()
            ?.filter { file -> file.isFile && patterns.any { it.matches(file.name) } }
            ?.mapNotNull { file ->
                val extension = file.extension.lowercase()
                LyricsFile.Format.entries.firstOrNull { it.value == extension }?.let { format ->
                    LyricsFile(file.absolutePath, format)
                }
            }
            .orEmpty()
    }

    private fun detectEncoding(bis: BufferedInputStream): Charset {
        return if (preferences.getBoolean(FORCE_UTF_8_ENCODING, true)) {
            Charsets.UTF_8
        } else try {
            charsetDetector.reset()
            bis.mark(BUFFER_SIZE)

            val buf = ByteArray(BUFFER_SIZE)
            var nread: Int
            while ((bis.read(buf).also { nread = it }) > 0 && !charsetDetector.isDone) {
                charsetDetector.handleData(buf, 0, nread)
            }

            charsetDetector.dataEnd()
            charsetDetector.detectedCharset?.let {
                Charset.forName(it)
            } ?: Charsets.UTF_8
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't detect lyrics file encoding", e)
            Charsets.UTF_8
        } finally {
            bis.reset()
            charsetDetector.reset()
        }
    }

    companion object {
        private const val TAG = "LyricsRepository"

        private val INSTRUMENTAL_REGEX = Regex("""(?i)[(\[\-]\s*(?:acoustic(?:\s+version)?|instr?|instrumental|karaoke|orchestral)(?:\s+version)?\s*[)\]]?\s*$""")

        private const val BUFFER_SIZE = 4096
        private const val FORCE_UTF_8_ENCODING = "force_utf8_encoding_for_lyrics"
        private const val IGNORE_BLANK_LINES = "ignore_blank_lines_in_lyrics"
        private const val NEGATIVE_CACHE_TTL_MS = 30L * 60 * 1000
    }
}