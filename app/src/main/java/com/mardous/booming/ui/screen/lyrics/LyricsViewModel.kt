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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    private val _lyricsViewSettings = MutableStateFlow(createViewSettings())
    val lyricsViewSettings = _lyricsViewSettings.asStateFlow()

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
                // The user edited the lyrics on purpose: block the
                // automatic word-by-word upgrade from replacing them.
                synchronized(wordUpgradeAttempted) { wordUpgradeAttempted.add(song.id) }
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
        // Fresh start: stored lyrics are gone, allow new upgrade sweeps.
        synchronized(wordUpgradeAttempted) { wordUpgradeAttempted.clear() }
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

    /**
     * Row status for the fullscreen provider switch dialog: which provider
     * is currently being fetched, and which provider last returned nothing.
     */
    data class ProviderSwitchState(
        val loadingProvider: LyricsProvider? = null,
        val failureProvider: LyricsProvider? = null
    )

    private val _providerSwitchState = MutableStateFlow(ProviderSwitchState())
    val providerSwitchState = _providerSwitchState.asStateFlow()

    /**
     * Whether the current song has stored (downloaded or provider-picked)
     * lyrics - i.e. whether the provider sheet's Clear action can remove
     * anything. Maintained by the load, switch, download and clear paths.
     */
    private val _hasStoredLyrics = MutableStateFlow(false)
    val hasStoredLyrics = _hasStoredLyrics.asStateFlow()

    /** Live per-provider results for the fullscreen provider sheet rows. */
    val providerLiveResults = repository.providerLiveResults

    /**
     * Per-song manual timing correction, in milliseconds. Positive shifts
     * the display later. The provider sheet's sync row adjusts this; the
     * viewers subtract it before updating the playback cursor. Persisted
     * per song on the song's lyrics row.
     */
    private val _lyricsSyncOffsetMs = MutableStateFlow(0L)
    val lyricsSyncOffsetMs = _lyricsSyncOffsetMs.asStateFlow()

    private var syncOffsetSaveJob: Job? = null
    private var syncOffsetLoadJob: Job? = null

    /**
     * Loads the stored offset for [song] into the live state. Runs in its
     * own job (not the cancellable lyrics load) so a provider switch can't
     * leave the offset unset.
     */
    fun loadLyricsSyncOffset(song: Song) {
        // Don't cancel a pending save: it captured its own song and must
        // still land even when the user skips to the next track fast.
        if (song == Song.emptySong) {
            _lyricsSyncOffsetMs.value = 0L
            return
        }
        syncOffsetLoadJob?.cancel()
        syncOffsetLoadJob = viewModelScope.launch {
            val offset = withContext(IO) {
                runCatching { repository.getSyncOffset(song) }.getOrDefault(0L)
            }
            _lyricsSyncOffsetMs.value = offset
        }
    }

    fun adjustLyricsSyncOffset(deltaMs: Long) {
        _lyricsSyncOffsetMs.value += deltaMs
        persistLyricsSyncOffset(debounced = true)
    }

    fun resetLyricsSyncOffset() {
        _lyricsSyncOffsetMs.value = 0L
        persistLyricsSyncOffset(debounced = false)
    }

    private fun persistLyricsSyncOffset(debounced: Boolean) {
        syncOffsetSaveJob?.cancel()
        val song = currentLyricsSong ?: return
        if (debounced) {
            syncOffsetSaveJob = viewModelScope.launch {
                // Debounce: each tap writes at most once, 500ms after the
                // last adjustment.
                delay(500)
                withContext(IO) {
                    runCatching { repository.saveSyncOffset(song, _lyricsSyncOffsetMs.value) }
                }
            }
        } else {
            syncOffsetSaveJob = viewModelScope.launch(IO) {
                runCatching { repository.saveSyncOffset(song, _lyricsSyncOffsetMs.value) }
            }
        }
    }

    private var refreshJob: Job? = null
    private var wordUpgradeJob: Job? = null
    private var wordUpgradeSongId: Long? = null

    /**
     * The song whose word-synced upgrade sweep was cancelled because the
     * provider sheet opened (the sheet's own sweep supersedes it); the
     * sweep resumes when the sheet closes.
     */
    private var suspendedWordUpgradeSong: Song? = null

    /**
     * Runs a background sweep for the provider sheet without persisting
     * anything: [providerLiveResults] updates each row live mid-race so
     * the sheet reflects reality instead of spinning forever when the
     * song already had stored lyrics.
     */
    fun refreshProviderResults(song: Song) {
        if (song == Song.emptySong) return
        // The sheet's sweep supersedes an in-flight word-synced upgrade
        // for the same song: both race the same providers into the same
        // live-results flow (duplicated traffic), and the upgrade resets
        // that flow at its start, which flipped the sheet's rows back to
        // Searching mid-browse. Cancel it first - the join guarantees
        // its settle writes land BEFORE this sweep resets the flow - and
        // remember it so providerDialogClosed() can resume the upgrade.
        val upgradeToCancel = wordUpgradeJob
            ?.takeIf { it.isActive && wordUpgradeSongId == song.id }
        if (upgradeToCancel != null) {
            suspendedWordUpgradeSong = song
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(IO) {
            if (upgradeToCancel != null) {
                upgradeToCancel.cancel()
                upgradeToCancel.join()
            }
            try {
                repository.refreshProviderResults(song)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Called when the provider sheet closes: resumes a word-synced upgrade
     * sweep that was cancelled to make room for the sheet's own sweep.
     * Only the current song resumes - a skipped song's upgrade is
     * abandoned until the next app start (wordUpgradeAttempted still
     * holds it), which keeps background sweeps from stacking up.
     */
    fun providerDialogClosed() {
        val song = suspendedWordUpgradeSong ?: return
        suspendedWordUpgradeSong = null
        if (wordUpgradeJob?.isActive != true && currentLyricsSong?.id == song.id) {
            launchWordSyncedUpgrade(song)
        }
    }

    /**
     * Small cache of provider-switch results per song, so re-tapping a
     * provider in the dialog doesn't refetch.
     */
    private val providerSwitchResults = mutableMapOf<Pair<Long, LyricsProvider>, ParsedLyrics>()

    fun switchToProvider(song: Song, provider: LyricsProvider) {
        if (_providerSwitchState.value.loadingProvider != null) return
        // Cancel any in-flight fetch so it can't overwrite the user's pick.
        lyricsJob?.cancel()
        viewModelScope.launch {
            _providerSwitchState.value = ProviderSwitchState(loadingProvider = provider)
            val cacheKey = song.id to provider
            // The whole switch is exception-guarded: a throw in parsing or
            // the DB write would otherwise leave loadingProvider set, and
            // the gate above then silently blocks EVERY further provider
            // tap while the row spins, until the screen is left.
            val parsed = try {
                providerSwitchResults[cacheKey]
                    ?: repository.applyProviderLyrics(song, provider)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("LyricsViewModel", "Switch to ${provider.displayName} failed", e)
                null
            }

            if (parsed == null || parsed is ParsedLyrics.Empty) {
                _providerSwitchState.value = ProviderSwitchState(failureProvider = provider)
            } else {
                // The user picked this provider on purpose: block the
                // automatic word-by-word upgrade from replacing it.
                synchronized(wordUpgradeAttempted) { wordUpgradeAttempted.add(song.id) }
                providerSwitchResults.clear()
                providerSwitchResults[cacheKey] = parsed
                _hasStoredLyrics.value = true
                if (song.id == _lyricsUiState.value.id) {
                    when (parsed) {
                        is ParsedLyrics.Instrumental -> _lyricsUiState.value = LyricsUiState.Instrumental(song.id)
                        is ParsedLyrics.Synced -> _lyricsUiState.value = LyricsUiState.Synced(song.id, parsed.lyrics)
                        is ParsedLyrics.Plain -> _lyricsUiState.value = LyricsUiState.Plain(song.id, parsed.lyrics, parsed.provider)
                        is ParsedLyrics.Empty -> Unit
                    }
                }
                _providerSwitchState.value = ProviderSwitchState()
            }
        }
    }

    /**
     * The provider sheet's Clear action: removes the song's stored
     * (downloaded) lyrics and falls back to what the file itself offers -
     * embedded tags, .lrc sidecars - WITHOUT the download stage, since
     * the user just removed the downloaded lyrics on purpose. The
     * repository records a negative-cache miss so the automatic download
     * doesn't re-fetch them on the next play (retries after the 30-min
     * miss TTL).
     */
    fun clearStoredLyrics(song: Song) = viewModelScope.launch {
        if (song.id == Song.emptySong.id) return@launch
        // The sync offset lives on the lyrics row being deleted: stop any
        // pending offset save/load and reset it - it was tuned for the
        // lyrics being removed.
        syncOffsetSaveJob?.cancel()
        syncOffsetLoadJob?.cancel()
        _lyricsSyncOffsetMs.value = 0L
        try {
            withContext(IO) { repository.clearStoredLyrics(song) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        providerSwitchResults.clear()
        _hasStoredLyrics.value = false
        if (song.id == _lyricsUiState.value.id) {
            _lyricsUiState.value = getBestLocalLyrics(
                song, listOf(LyricsSource.File, LyricsSource.Embedded)
            )
        }
    }

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
        loadLyricsSyncOffset(song)
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            if (song == Song.emptySong) {
                _lyricsUiState.value = LyricsUiState.Empty(song.id)
                _hasStoredLyrics.value = false
            } else {
                _lyricsUiState.value = LyricsUiState.Loading(song.id)

                // Stored lyrics are the top priority and final: whatever the
                // user downloaded, edited or picked is THE lyrics for this
                // song.
                val stored = repository.storedLyrics(song, allowDownload = false)
                if (stored != null) {
                    _hasStoredLyrics.value = true
                    val parsed = repository.parseRawLyrics(song, stored)
                    if (isActive) {
                        _lyricsUiState.value = when (parsed) {
                            is ParsedLyrics.Instrumental -> LyricsUiState.Instrumental(song.id)
                            is ParsedLyrics.Synced -> LyricsUiState.Synced(song.id, parsed.lyrics)
                            is ParsedLyrics.Plain -> LyricsUiState.Plain(song.id, parsed.lyrics, parsed.provider)
                            is ParsedLyrics.Empty -> LyricsUiState.Empty(song.id)
                        }
                        // Stored lyrics are final, but a line-synced result
                        // still gets ONE background upgrade sweep for a
                        // word-by-word version (older races could only ever
                        // store the first line-synced winner).
                        if (parsed is ParsedLyrics.Synced && !parsed.lyrics.isWordSynced) {
                            maybeUpgradeToWordSynced(song)
                        }
                    }
                } else {
                    _hasStoredLyrics.value = false
                    // Nothing stored: sidecar files and embedded tags show
                    // instantly, then the download stage replaces them when
                    // it finds something better (synced preferred over
                    // plain). Downloads are saved automatically.
                    val sources = listOf(LyricsSource.File, LyricsSource.Embedded)
                    val localState = getBestLocalLyrics(song, sources)

                    if (isActive && canUpgrade(localState)) {
                        if (localState is LyricsUiState.Empty) {
                            // Don't flash "no lyrics found" while the fetch is
                            // still in flight - keep the loading state instead.
                            _lyricsUiState.value = LyricsUiState.Loading(song.id)
                        } else if (isActive) {
                            _lyricsUiState.value = localState
                        }
                        val downloaded = fetchLyrics(song)
                        if (isActive) {
                            if (downloaded != null) {
                                // The download stage stored lyrics: the
                                // Clear action can remove them again.
                                _hasStoredLyrics.value = true
                            }
                            if (downloaded != null && isUpgrade(localState, downloaded)) {
                                _lyricsUiState.value = downloaded
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
    }

    /**
     * Synced lyrics are the target: the download stage runs whenever the
     * local result isn't synced yet (plain or nothing). Instrumental marks
     * are final.
     */
    private fun canUpgrade(localState: LyricsUiState): Boolean = when (localState) {
        is LyricsUiState.Instrumental -> false
        is LyricsUiState.Synced -> false
        else -> true
    }

    /**
     * One word-by-word upgrade sweep per song per session: replaces the
     * stored lyrics (and the displayed state) when a word-by-word version
     * is found. Runs in the background so the line-synced lyrics show
     * immediately.
     */
    private fun maybeUpgradeToWordSynced(song: Song) {
        if (synchronized(wordUpgradeAttempted) { wordUpgradeAttempted.contains(song.id) }) return
        synchronized(wordUpgradeAttempted) { wordUpgradeAttempted.add(song.id) }
        launchWordSyncedUpgrade(song)
    }

    /**
     * The upgrade sweep races every enabled provider - the exact sweep
     * the provider sheet runs for its live rows, into the SAME
     * live-results flow. While the sheet is open the two would duplicate
     * traffic and interleave, so refreshProviderResults() cancels an
     * in-flight sweep for the same song and providerDialogClosed()
     * resumes it.
     */
    private fun launchWordSyncedUpgrade(song: Song) {
        wordUpgradeSongId = song.id
        wordUpgradeJob = viewModelScope.launch {
            val upgraded = withContext(IO) {
                try {
                    repository.downloadWordSyncedUpgrade(song)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("LyricsViewModel", "Word-synced upgrade failed for ${song.title}", e)
                    null
                }
            } ?: return@launch
            if (isActive && currentLyricsSong?.id == song.id) {
                _lyricsUiState.value = LyricsUiState.Synced(song.id, upgraded)
            }
        }
    }

    private suspend fun fetchLyrics(song: Song): LyricsUiState? =
        withContext(IO) {
            val stored = repository.storedLyrics(song, allowDownload = true)
                ?: return@withContext null
            when (val parsed = repository.parseRawLyrics(song, stored)) {
                is ParsedLyrics.Instrumental -> LyricsUiState.Instrumental(song.id)
                is ParsedLyrics.Synced -> LyricsUiState.Synced(song.id, parsed.lyrics)
                is ParsedLyrics.Plain -> LyricsUiState.Plain(song.id, parsed.lyrics, parsed.provider)
                is ParsedLyrics.Empty -> null
            }
        }

    private fun isUpgrade(localState: LyricsUiState, downloaded: LyricsUiState): Boolean = when {
        downloaded is LyricsUiState.Instrumental -> true
        // Synced (word-by-word or line) replaces plain/empty lyrics.
        downloaded is LyricsUiState.Synced -> localState !is LyricsUiState.Synced
        downloaded is LyricsUiState.Plain -> localState is LyricsUiState.Empty
        else -> false
    }

    private suspend fun getBestLocalLyrics(
        song: Song,
        sources: List<LyricsSource>
    ): LyricsUiState = withContext(IO) {
        val rawBySource: Map<LyricsSource, RawLyrics?> = coroutineScope {
            sources.associateWith { source ->
                async {
                    when (source) {
                        LyricsSource.File -> repository.fileLyrics(song)
                        LyricsSource.Embedded -> repository.embeddedLyrics(song)
                        // Stored rows are handled before this point and are
                        // top priority; they never participate in the ladder.
                        LyricsSource.Downloaded -> null
                    }
                }
            }.mapValues { it.value.await() }
        }
        selectBestLyrics(song, sources, rawBySource)
    }

    private suspend fun selectBestLyrics(
        song: Song,
        sources: List<LyricsSource>,
        rawBySource: Map<LyricsSource, RawLyrics?>
    ): LyricsUiState = withContext(IO) {
        // Quality ladder across all local sources: word-by-word beats
        // line-synced beats plain. The provider race applies the same ladder,
        // with provider results replacing these in place when better.
        var plainLyrics: String? = null
        var plainProvider: String? = null
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
                }
                is ParsedLyrics.Plain -> if (plainLyrics.isNullOrEmpty()) {
                    plainLyrics = parsed.lyrics
                    plainProvider = parsed.provider
                }
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
            return@withContext LyricsUiState.Plain(song.id, plainLyrics, plainProvider)
        }
        return@withContext LyricsUiState.Empty(song.id)
    }

    private fun createViewSettings(): LyricsViewSettings {
        val enableSyllableLyrics = preferences.getBoolean(Key.ENABLE_SYLLABLE_LYRICS, true)
        val enableKaraokeStyle = preferences.getBoolean(Key.ENABLE_KARAOKE_STYLE, true)
        val progressiveColoring = preferences.getBoolean(Key.PROGRESSIVE_COLORING, true)
        val showTranslation = preferences.getBoolean(Key.SHOW_TRANSLATION, true)
        val showTransliteration = preferences.getBoolean(Key.SHOW_TRANSLITERATION, false)
        val resumeOnSeek = preferences.getBoolean(Key.RESUME_ON_SEEK, false)
        val blurEffect = preferences.getBoolean(Key.BLUR_EFFECT, false)
        val shadowEffect = preferences.getBoolean(Key.SHADOW_EFFECT, false)
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
        val lineSpacing = preferences.getInt(Key.LINE_SPACING, 25)
        val syncedFontSize = preferences.getInt(Key.SYNCED_FONT_SIZE, 40)
        val unsyncedFontSize = preferences.getInt(Key.UNSYNCED_FONT_SIZE, 40)
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
            isCenterCurrentLine = preferences.getBoolean(Key.CENTER_CURRENT_LINE, true),
            isCenterHorizontally = preferences.getBoolean(Key.CENTER_HORIZONTALLY, true),
            enableSyllableLyrics = enableSyllableLyrics,
            enableKaraokeStyle = enableKaraokeStyle,
            progressiveColoring = progressiveColoring,
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
            Key.BLUR_EFFECT,
            Key.SHADOW_EFFECT,
            Key.SYNCED_BOLD_FONT,
            Key.UNSYNCED_BOLD_FONT -> {
                if (key == Key.ENABLE_SYLLABLE_LYRICS) {
                    // Re-run lyrics selection so the word-by-word provider
                    // priority applies to the playing song immediately.
                    currentLyricsSong?.let { updateSong(it) }
                }
                _lyricsViewSettings.value = createViewSettings()
            }
            Key.SYNCED_FONT_SIZE,
            Key.UNSYNCED_FONT_SIZE -> {
                _lyricsViewSettings.value = createViewSettings()
            }
        }
    }

    companion object {
        /**
         * Song ids whose word-by-word upgrade sweep already ran this
         * session (static so it survives ViewModel recreation). Songs
         * whose sweep found nothing are retried next app start.
         */
        private val wordUpgradeAttempted = HashSet<Long>()
    }
}