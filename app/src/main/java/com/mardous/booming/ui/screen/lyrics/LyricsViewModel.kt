package com.mardous.booming.ui.screen.lyrics

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.BackgroundEffect
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.Key
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.LyricsSource
import com.mardous.booming.data.model.lyrics.ParsedLyrics
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.data.remote.lyrics.LyricsProviderParams
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.data.repository.LyricsRepository
import com.mardous.booming.extensions.files.belongsTo
import com.mardous.booming.extensions.media.isArtistNameUnknown
import com.mardous.booming.extensions.utilities.sanitize
import com.mardous.booming.util.FileTypeVerifier
import com.mardous.booming.util.FileUtil
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.Mode as LyricsViewMode

/**
 * @author Christians M. A. (mardous)
 */
class LyricsViewModel(
    application: Application,
    private val preferences: SharedPreferences,
    private val repository: LyricsRepository
) : AndroidViewModel(application), OnSharedPreferenceChangeListener {

    private val _lyricsUiState = MutableStateFlow<LyricsUiState>(LyricsUiState.Empty(-1))
    val lyricsUiState = _lyricsUiState.asStateFlow()

    private val _lyricsEditorUiState = MutableStateFlow<LyricsEditorUiState>(LyricsEditorUiState.Disposed)
    val lyricsEditorUiState = _lyricsEditorUiState.asStateFlow()

    private val _saveEvent = Channel<LyricsEditorResult>(Channel.BUFFERED)
    val saveEvent = _saveEvent.receiveAsFlow()

    private val _downloadEvent = Channel<RawLyrics.Remote>(Channel.BUFFERED)
    val downloadEvent = _downloadEvent.receiveAsFlow()

    private val _permissionRequestEvent = Channel<List<Uri>>(Channel.BUFFERED)
    val permissionRequestEvent = _permissionRequestEvent.receiveAsFlow()

    private val _playerLyricsViewSettings = MutableStateFlow(createViewSettings(LyricsViewMode.Player))
    val playerLyricsViewSettings = _playerLyricsViewSettings.asStateFlow()

    private val _fullLyricsViewSettings = MutableStateFlow(createViewSettings(LyricsViewMode.Full))
    val fullLyricsViewSettings = _fullLyricsViewSettings.asStateFlow()

    private var lyricsJob: Job? = null

    init {
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCleared() {
        lyricsJob?.cancel()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    fun getSearchUrl(song: Song): String {
        val query = if (song.isArtistNameUnknown()) song.title
        else "${song.artistName} ${song.title}"
        return "https://lrclib.net/search/${Uri.encode(query)}"
    }

    fun loadEditorContent(song: Song) = viewModelScope.launch(IO) {
        _lyricsEditorUiState.value = LyricsEditorUiState.Visible(isLoading = true)
        val lyrics = getEditorLyricsBySources(song, LyricsSource.entries)
        _lyricsEditorUiState.value = LyricsEditorUiState.Visible(
            isLoading = false,
            lyrics = lyrics
        )
    }

    fun disposeEditorContent() = viewModelScope.launch(IO) {
        _lyricsEditorUiState.value = LyricsEditorUiState.Disposed
    }

    fun saveLyrics(song: Song, newLyrics: Map<LyricsSource, String>) = viewModelScope.launch(IO) {
        val uiState = _lyricsEditorUiState.updateAndGet {
            if (it is LyricsEditorUiState.Visible) {
                it.copy(isLoading = true)
            } else it
        }
        if (uiState is LyricsEditorUiState.Visible) {
            val event = when (val result = repository.saveLyrics(song, uiState.lyrics, newLyrics)) {
                null -> LyricsEditorResult.NoChanges
                else -> if (result) LyricsEditorResult.Success else LyricsEditorResult.Failed
            }

            _saveEvent.send(event)

            if (event == LyricsEditorResult.Success) {
                // Lyrics need to be updated to avoid unnecessary save operations
                val newLyrics = getEditorLyricsBySources(song, newLyrics.keys.toList())
                _lyricsEditorUiState.value = uiState.copy(isLoading = false, lyrics = newLyrics)

                // Update current song lyrics if necessary
                if (song.id == lyricsUiState.value.id) {
                    updateSong(song)
                }
            } else {
                _lyricsEditorUiState.value = uiState.copy(isLoading = false)
            }
        }
    }

    fun downloadLyrics(song: Song, title: String, artist: String, providers: List<LyricsProvider>) =
        viewModelScope.launch(IO) {
            val uiState = _lyricsEditorUiState.updateAndGet {
                if (it is LyricsEditorUiState.Visible) {
                    it.copy(isLoading = true)
                } else it
            }
            if (uiState is LyricsEditorUiState.Visible) {
                val providerParams = LyricsProviderParams(
                    providers = providers,
                    ignoreWifiSetting = true,
                    ignoreProviderSetting = true
                )
                val onlineLyrics = repository.downloadLyrics(song, title, artist, providerParams)
                if (onlineLyrics != null) {
                    _downloadEvent.send(onlineLyrics)
                } else {
                    _downloadEvent.send(RawLyrics.Remote())
                }
                _lyricsEditorUiState.value = uiState.copy(isLoading = false)
            }
        }

    fun preparePermissionRequest(song: Song) = viewModelScope.launch(IO) {
        _permissionRequestEvent.send(repository.writableUris(song))
    }

    fun deleteLyrics() = viewModelScope.launch(IO) {
        repository.deleteAllLyrics()
    }

    fun importCustomFont(context: Context, uri: Uri) = liveData(IO) {
        try {
            val defaultName = "custom_font_${System.currentTimeMillis()}.ttf"
            val fontsDir = FileUtil.fontsDirectory() ?: return@liveData
            val rawFileName = context.contentResolver.query(uri, null, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) cursor.getString(nameIndex) else null
                    } else null
                } ?: defaultName

            // Sanitize the filename to prevent path traversal
            val fileName = File(rawFileName).name.sanitize()
                .ifBlank { defaultName }

            // 1. Initial extension check
            var isValid = fileName.lowercase().endsWith(".ttf") || fileName.lowercase().endsWith(".otf")

            // 2. Magic-byte check before choosing any destination path or writing
            if (isValid) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    isValid = with(FileTypeVerifier) { input.isFontFile() }
                }
            }

            if (!isValid) {
                emit(false)
                return@liveData
            }

            // 3. Resolve destination and verify containment
            val outFile = File(fontsDir, fileName)
            if (!outFile.belongsTo(fontsDir)) {
                emit(false)
                return@liveData
            }

            // 4. Perform the actual import
            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            preferences.edit(commit = true) {
                putBoolean(Key.USE_CUSTOM_FONT, true)
                putString(Key.SELECTED_CUSTOM_FONT, outFile.absolutePath)
            }

            emit(outFile.length() > 0)
        } catch (e: Exception) {
            e.printStackTrace()
            emit(false)
        }
    }

    private var currentLyricsSong: Song? = null

    private suspend fun getEditorLyricsBySources(
        song: Song,
        sources: List<LyricsSource>
    ) = sources.associateWith { source ->
        when (source) {
            LyricsSource.Downloaded -> repository.storedLyrics(song, false)
            LyricsSource.Embedded -> repository.embeddedLyrics(song)
            LyricsSource.File -> repository.fileLyrics(song)
        }
    }

    fun updateSong(song: Song) {
        currentLyricsSong = song
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            if (song == Song.emptySong) {
                _lyricsUiState.value = LyricsUiState.Empty(song.id)
            } else {
                _lyricsUiState.value = LyricsUiState.Loading(song.id)

                val preferWordSynced = preferences.getBoolean(Key.ENABLE_SYLLABLE_LYRICS, false)
                val sources = listOf(
                    LyricsSource.File,
                    LyricsSource.Embedded,
                    LyricsSource.Downloaded
                )

                // Stage 1: local sources (sidecar files, embedded tags and
                // stored rows) are cheap disk reads - fetch them in parallel
                // and show the best result immediately.
                val localState = getBestLocalLyrics(song, sources, preferWordSynced)

                // Stage 2: only hit the network when the local result can
                // still be improved. The upgrade replaces the lyrics in
                // place, so stored line-synced lyrics never wait on it.
                if (isActive && canUpgrade(localState, preferWordSynced)) {
                    if (localState is LyricsUiState.Empty) {
                        // Don't flash "no lyrics found" while the fetch is
                        // still in flight - keep the loading state instead.
                        _lyricsUiState.value = LyricsUiState.Loading(song.id)
                    } else if (isActive) {
                        _lyricsUiState.value = localState
                    }
                    val upgradedState = fetchUpgradedLyrics(song, preferWordSynced)
                    if (isActive) {
                        if (upgradedState != null && isUpgrade(localState, upgradedState)) {
                            _lyricsUiState.value = upgradedState
                        } else if (localState is LyricsUiState.Empty) {
                            _lyricsUiState.value = LyricsUiState.Empty(song.id)
                        }
                    }
                } else if (isActive) {
                    _lyricsUiState.value = localState
                }
            }
        }
    }

    private fun canUpgrade(localState: LyricsUiState, preferWordSynced: Boolean): Boolean = when {
        localState is LyricsUiState.Instrumental -> false
        localState is LyricsUiState.Synced && localState.syncedLyrics.isWordSynced -> false
        localState is LyricsUiState.Synced -> preferWordSynced
        else -> true
    }

    private suspend fun fetchUpgradedLyrics(song: Song, preferWordSynced: Boolean): LyricsUiState? =
        withContext(IO) {
            val stored = repository.storedLyrics(
                song,
                allowDownload = true,
                preferWordSynced = preferWordSynced
            ) ?: return@withContext null
            when (val parsed = repository.parseRawLyrics(song, stored)) {
                is ParsedLyrics.Instrumental -> LyricsUiState.Instrumental(song.id)
                is ParsedLyrics.Synced -> LyricsUiState.Synced(song.id, parsed.lyrics)
                is ParsedLyrics.Plain -> LyricsUiState.Plain(song.id, parsed.lyrics)
                is ParsedLyrics.Empty -> null
            }
        }

    private fun isUpgrade(localState: LyricsUiState, upgraded: LyricsUiState): Boolean = when {
        upgraded is LyricsUiState.Instrumental -> true
        upgraded is LyricsUiState.Synced -> when {
            // Word-synced lyrics beat any local content.
            upgraded.syncedLyrics.isWordSynced ->
                localState !is LyricsUiState.Synced || !localState.syncedLyrics.isWordSynced
            // Line-synced only beats plain/empty lyrics.
            else -> localState is LyricsUiState.Plain || localState is LyricsUiState.Empty
        }
        upgraded is LyricsUiState.Plain -> localState is LyricsUiState.Empty
        else -> false
    }

    private suspend fun getBestLocalLyrics(
        song: Song,
        sources: List<LyricsSource>,
        preferWordSynced: Boolean
    ): LyricsUiState = withContext(IO) {
        val rawBySource: Map<LyricsSource, RawLyrics?> = coroutineScope {
            sources.associateWith { source ->
                async {
                    when (source) {
                        LyricsSource.File -> repository.fileLyrics(song)
                        LyricsSource.Embedded -> repository.embeddedLyrics(song)
                        LyricsSource.Downloaded -> repository.storedLyrics(
                            song,
                            allowDownload = false,
                            preferWordSynced = preferWordSynced
                        )
                    }
                }
            }.mapValues { it.value.await() }
        }
        selectBestLyrics(song, sources, rawBySource, preferWordSynced)
    }

    private suspend fun selectBestLyrics(
        song: Song,
        sources: List<LyricsSource>,
        rawBySource: Map<LyricsSource, RawLyrics?>,
        preferWordSynced: Boolean
    ): LyricsUiState = withContext(IO) {
        // With "Word-by-word lyrics" enabled, lyrics from a provider that
        // carries word-level timestamps win over any local synced lyrics,
        // with line-synced and then plain lyrics as fallbacks.
        var plainLyrics: String? = null
        var lineSyncedLyrics: SyncedLyrics? = null
        var wordSyncedLyrics: SyncedLyrics? = null
        for (source in sources) {
            val rawLyrics = rawBySource[source] ?: continue
            when (val parsed = repository.parseRawLyrics(song, rawLyrics)) {
                is ParsedLyrics.Instrumental -> return@withContext LyricsUiState.Instrumental(song.id)
                is ParsedLyrics.Synced -> {
                    if (parsed.lyrics.isWordSynced) {
                        wordSyncedLyrics = wordSyncedLyrics ?: parsed.lyrics
                    } else {
                        lineSyncedLyrics = lineSyncedLyrics ?: parsed.lyrics
                    }
                    if (!preferWordSynced) {
                        // Classic behavior: the first synced result wins.
                        return@withContext LyricsUiState.Synced(song.id, parsed.lyrics)
                    }
                }
                is ParsedLyrics.Plain -> if (plainLyrics.isNullOrEmpty()) plainLyrics = parsed.lyrics
                is ParsedLyrics.Empty -> {
                    Log.d("LyricsViewModel", "No lyrics found for song ${song.data} in source $source")
                }
            }
        }
        val bestSynced = wordSyncedLyrics ?: lineSyncedLyrics
        if (bestSynced != null) {
            return@withContext LyricsUiState.Synced(song.id, bestSynced)
        }
        if (!plainLyrics.isNullOrEmpty()) {
            return@withContext LyricsUiState.Plain(song.id, plainLyrics)
        }
        return@withContext LyricsUiState.Empty(song.id)
    }

    private fun createViewSettings(mode: LyricsViewMode): LyricsViewSettings {
        val background: BackgroundEffect =
            if (!mode.isFull) {
                BackgroundEffect.None
            } else when (preferences.getString(Key.BACKGROUND_EFFECT, null)) {
                "gradient" -> BackgroundEffect.Gradient
                "blur" -> BackgroundEffect.Blur
                else -> BackgroundEffect.None
            }
        val enableSyllableLyrics = preferences.getBoolean(Key.ENABLE_SYLLABLE_LYRICS, false)
        val enableKaraokeStyle = preferences.getBoolean(Key.ENABLE_KARAOKE_STYLE, false)
        val progressiveColoring = preferences.getBoolean(Key.PROGRESSIVE_COLORING, false)
        val showTranslation = preferences.getBoolean(Key.SHOW_TRANSLATION, true)
        val showTransliteration = preferences.getBoolean(Key.SHOW_TRANSLITERATION, false)
        val resumeOnSeek = preferences.getBoolean(Key.RESUME_ON_SEEK, false)
        val blurEffect = !background.isNone && preferences.getBoolean(Key.BLUR_EFFECT, false)
        val shadowEffect = !background.isNone && preferences.getBoolean(Key.SHADOW_EFFECT, false)
        val fontFamily: FontFamily = if (preferences.getBoolean(Key.USE_CUSTOM_FONT, false)) {
            try {
                preferences.getString(Key.SELECTED_CUSTOM_FONT, null)
                    ?.let { FontFamily(Typeface.createFromFile(it)) }
                    ?: FontFamily.Default
            } catch (_: Exception) {
                preferences.edit {
                    remove(Key.SELECTED_CUSTOM_FONT)
                }
                FontFamily.Default
            }
        } else {
            FontFamily.Default
        }
        val lineSpacing = preferences.getInt(Key.LINE_SPACING, 40)
        val syncedFontSize = if (mode == LyricsViewMode.Player) {
            preferences.getInt(Key.SYNCED_FONT_SIZE_PLAYER, 20)
        } else {
            preferences.getInt(Key.SYNCED_FONT_SIZE_FULL, 24)
        }
        val unsyncedFontSize = if (mode == LyricsViewMode.Player) {
            preferences.getInt(Key.UNSYNCED_FONT_SIZE_PLAYER, 16)
        } else {
            preferences.getInt(Key.UNSYNCED_FONT_SIZE_FULL, 20)
        }
        val syncedBoldFont = preferences.getBoolean(Key.SYNCED_BOLD_FONT, false)
        val syncedStyle = TextStyle(
            fontFamily = fontFamily,
            fontSize = syncedFontSize.sp,
            fontWeight = if (syncedBoldFont) FontWeight.Bold else FontWeight.Normal,
            lineHeight = (1f + (lineSpacing / 100f)).em
        )
        val unsyncedBoldFont = preferences.getBoolean(Key.UNSYNCED_BOLD_FONT, false)
        val unsyncedStyle = TextStyle(
            fontFamily = fontFamily,
            fontSize = unsyncedFontSize.sp,
            fontWeight = if (unsyncedBoldFont) FontWeight.Bold else FontWeight.Normal,
            lineHeight = (1f + (lineSpacing / 100f)).em
        )
        return LyricsViewSettings(
            mode = mode,
            isCenterCurrentLine = preferences.getBoolean(Key.CENTER_CURRENT_LINE, false),
            isCenterHorizontally = preferences.getBoolean(Key.CENTER_HORIZONTALLY, false),
            enableSyllableLyrics = enableSyllableLyrics,
            enableKaraokeStyle = enableKaraokeStyle,
            progressiveColoring = progressiveColoring,
            backgroundEffect = background,
            blurEffect = blurEffect,
            shadowEffect = shadowEffect,
            showTranslation = showTranslation,
            showTransliteration = showTransliteration,
            resumeOnSeek = resumeOnSeek,
            syncedStyle = syncedStyle,
            unsyncedStyle = unsyncedStyle,
            lineSpacing = ((lineSpacing / 2) + 8).coerceIn(8, 48)
        )
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Key.ENABLE_SYLLABLE_LYRICS,
            Key.ENABLE_KARAOKE_STYLE,
            Key.CENTER_CURRENT_LINE,
            Key.CENTER_HORIZONTALLY,
            Key.USE_CUSTOM_FONT,
            Key.SELECTED_CUSTOM_FONT,
            Key.LINE_SPACING,
            Key.PROGRESSIVE_COLORING,
            Key.SHOW_TRANSLATION,
            Key.SHOW_TRANSLITERATION,
            Key.RESUME_ON_SEEK,
            Key.BACKGROUND_EFFECT,
            Key.BLUR_EFFECT,
            Key.SHADOW_EFFECT,
            Key.SYNCED_BOLD_FONT,
            Key.UNSYNCED_BOLD_FONT -> {
                if (key == Key.ENABLE_SYLLABLE_LYRICS) {
                    // Re-run lyrics selection so the word-by-word provider
                    // priority applies to the playing song immediately.
                    currentLyricsSong?.let { updateSong(it) }
                }
                _playerLyricsViewSettings.value = createViewSettings(LyricsViewMode.Player)
                _fullLyricsViewSettings.value = createViewSettings(LyricsViewMode.Full)
            }
            Key.SYNCED_FONT_SIZE_PLAYER,
            Key.UNSYNCED_FONT_SIZE_PLAYER -> {
                _playerLyricsViewSettings.value = createViewSettings(LyricsViewMode.Player)
            }
            Key.SYNCED_FONT_SIZE_FULL,
            Key.UNSYNCED_FONT_SIZE_FULL -> {
                _fullLyricsViewSettings.value = createViewSettings(LyricsViewMode.Full)
            }
        }
    }
}