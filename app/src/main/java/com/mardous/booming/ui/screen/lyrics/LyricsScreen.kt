package com.mardous.booming.ui.screen.lyrics

import android.os.SystemClock
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mardous.booming.R
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.core.model.lyrics.LyricsViewState
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.data.model.lyrics.isWordLevel
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.extensions.isPowerSaveMode
import com.mardous.booming.ui.component.compose.AnimatedEqBars
import com.mardous.booming.ui.component.compose.color.onThis
import com.mardous.booming.ui.component.compose.decoration.FadingEdges
import com.mardous.booming.ui.component.compose.decoration.fadingEdges
import com.mardous.booming.ui.component.compose.lyrics.LyricsView
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.theme.PlayerTheme
import kotlinx.coroutines.isActive
import java.util.Locale

sealed class LyricsUiState(open val id: Long) {
    data class Loading(override val id: Long) : LyricsUiState(id)
    data class Empty(override val id: Long) : LyricsUiState(id)
    data class Instrumental(override val id: Long) : LyricsUiState(id)
    data class Plain(override val id: Long, val lyrics: String, val provider: String? = null) : LyricsUiState(id)
    data class Synced(override val id: Long, val syncedLyrics: SyncedLyrics) : LyricsUiState(id)
}

@Composable
private fun rememberLyricsViewState(lyrics: SyncedLyrics): LyricsViewState {
    return remember(lyrics) { LyricsViewState(lyrics) }
}

@Composable
fun rememberSmoothPlaybackPosition(
    id: Long,
    playerProgress: Long,
    playbackSpeed: Float,
    isPlaying: Boolean
): Long {
    var position by remember(id) { mutableStateOf(playerProgress) }
    LaunchedEffect(id, playerProgress, isPlaying) {
        val baseRealtime = SystemClock.elapsedRealtime()
        if (!isPlaying) {
            position = playerProgress
            return@LaunchedEffect
        }

        while (isActive) {
            withFrameNanos {
                val elapsed = SystemClock.elapsedRealtime() - baseRealtime
                position = playerProgress + (elapsed * playbackSpeed).toLong()
            }
        }
    }

    return position
}

@Composable
fun CoverLyricsScreen(
    lyricsViewModel: LyricsViewModel,
    playerViewModel: PlayerViewModel,
    isExpanded: Boolean,
    onExpandClick: () -> Unit,
    onCollapseClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPowerSaveMode = context.isPowerSaveMode()

    val isPlaying by playerViewModel.isPlayingFlow.collectAsStateWithLifecycle()

    val lyricsViewSettings by lyricsViewModel.lyricsViewSettings.collectAsState()
    val uiState by lyricsViewModel.lyricsUiState.collectAsState()
    val syncOffsetMs by lyricsViewModel.lyricsSyncOffsetMs.collectAsState()

    val playerColorScheme by playerViewModel.colorSchemeFlow.collectAsState(
        initial = PlayerColorScheme.themeColorScheme(context)
    )

    var showProviderDialog by remember { mutableStateOf(false) }

    // Tonal button colors matching the player's Like/more buttons:
    // the scheme's secondaryContainer with a contrast-computed icon
    // color (AppTheme mode falls back to the theme roles, since
    // PlayerTheme does not remap secondaryContainer).
    val actionContainer =
        if (playerColorScheme.mode == PlayerColorScheme.Mode.AppTheme ||
            playerColorScheme.secondaryContainerColor == 0
        ) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color(playerColorScheme.secondaryContainerColor)
        }

    PlayerTheme(playerColorScheme) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .then(if (isExpanded) Modifier.systemBarsPadding() else Modifier)
        ) {
            LyricsSurface(
                uiState = uiState,
                playerViewModel = playerViewModel,
                syncOffsetMs = syncOffsetMs,
                settings = lyricsViewSettings,
                isExpanded = isExpanded,
                contentPadding = if (isExpanded) {
                    PaddingValues(vertical = 96.dp, horizontal = 16.dp)
                } else {
                    PaddingValues(vertical = 72.dp, horizontal = 12.dp)
                },
                fadingEdges = if (isExpanded) {
                    FadingEdges(top = 56.dp, bottom = 96.dp)
                } else {
                    FadingEdges(top = 72.dp, bottom = 64.dp)
                },
                textAlign = if (isExpanded) TextAlign.Start else TextAlign.Center,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                onSeekTo = { position ->
                    playerViewModel.seekTo(position)
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .wrapContentSize()
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    LyricsActionButton(
                        containerColor = actionContainer,
                        iconRes = R.drawable.ic_edit_note_24dp,
                        contentDescription = stringResource(R.string.switch_lyrics_provider_title),
                        onClick = { showProviderDialog = true },
                        onLongClick = onEditClick
                    )
                    LyricsActionButton(
                        containerColor = actionContainer,
                        iconRes = R.drawable.ic_close_fullscreen_24dp,
                        contentDescription = stringResource(R.string.action_collapse_lyrics),
                        onClick = onCollapseClick
                    )
                }
            } else {
                LyricsActionButton(
                    containerColor = actionContainer,
                    iconRes = R.drawable.ic_open_in_full_24dp,
                    contentDescription = stringResource(R.string.action_expand_lyrics),
                    onClick = onExpandClick,
                    onLongClick = onEditClick,
                    modifier = Modifier
                        .wrapContentSize()
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                )
            }
        }
    }

    if (showProviderDialog) {
        val switchState by lyricsViewModel.providerSwitchState.collectAsState()
        val liveResults by lyricsViewModel.providerLiveResults.collectAsState()
        val currentSong by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
        LaunchedEffect(currentSong.id) {
            lyricsViewModel.refreshProviderResults(currentSong)
        }
        val activeProvider = when (val state = uiState) {
            is LyricsUiState.Synced -> state.syncedLyrics.provider
            is LyricsUiState.Plain -> state.provider
            else -> null
        }
        ProviderSwitchDialog(
            activeProvider = activeProvider,
            switchState = switchState,
            liveResults = liveResults,
            syncOffsetMs = syncOffsetMs,
            onSwitch = { provider -> lyricsViewModel.switchToProvider(currentSong, provider) },
            onSyncOffset = { delta -> lyricsViewModel.adjustLyricsSyncOffset(delta) },
            onResetSyncOffset = { lyricsViewModel.resetLyricsSyncOffset() },
            onDismiss = { showProviderDialog = false }
        )
    }
}

@Composable
private fun LyricsActionButton(
    containerColor: Color,
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    // Same recipe as the player's Like/more buttons: a tonal
    // secondaryContainer square with a contrast-computed icon.
    FilledIconButton(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = containerColor.onThis()
        ),
        onClick = onClick
    ) {
        Box(
            modifier = if (onLongClick != null) {
                Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
            } else {
                Modifier
            },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsSurface(
    playerViewModel: PlayerViewModel,
    syncOffsetMs: Long,
    uiState: LyricsUiState,
    settings: LyricsViewSettings,
    isExpanded: Boolean,
    contentPadding: PaddingValues,
    fadingEdges: FadingEdges,
    textAlign: TextAlign?,
    isPlaying: Boolean,
    isPowerSaveMode: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    Box(modifier) {
        when (uiState) {
            is LyricsUiState.Empty -> {
                Text(
                    text = stringResource(R.string.no_lyrics_found),
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .align(Alignment.Center)
                )
            }

            is LyricsUiState.Loading -> {
                CircularWavyProgressIndicator(
                    color = contentColor,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            is LyricsUiState.Instrumental -> {
                AnimatedEqBars(
                    color = contentColor,
                    isPlaying = isPlaying,
                    barCount = 5,
                    modifier = Modifier
                        .size(56.dp)
                        .align(Alignment.Center)
                )
            }

            is LyricsUiState.Plain -> {
                val scrollState = rememberScrollState()

                val song by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
                LaunchedEffect(song) {
                    scrollState.scrollTo(0)
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(rememberNestedScrollInteropConnection())
                        .fadingEdges(fadingEdges)
                        .verticalScroll(scrollState)
                        .padding(contentPadding)
                ) {
                    Text(
                        text = uiState.lyrics,
                        color = contentColor,
                        textAlign = textAlign,
                        style = settings.syncedStyle
                    )
                    if (isExpanded && !uiState.provider.isNullOrEmpty()) {
                        Text(
                            text = stringResource(R.string.lyrics_by_x, uiState.provider!!),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = .5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentWidth(Alignment.CenterHorizontally)
                                .padding(top = 56.dp)
                        )
                    }
                }
            }

            is LyricsUiState.Synced -> {
                val lyricsViewState = rememberLyricsViewState(uiState.syncedLyrics)

                val song by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
                val playerProgress by playerViewModel.progressFlow.collectAsStateWithLifecycle()
                val playbackSpeed by playerViewModel.playbackSpeed.collectAsStateWithLifecycle()

                val smoothProgress = rememberSmoothPlaybackPosition(
                    id = song.id,
                    playerProgress = playerProgress,
                    playbackSpeed = playbackSpeed,
                    isPlaying = isPlaying
                )

                // Keyed on the frame-smoothed position so the karaoke word
                // fill advances at display frame rate instead of the 100ms
                // progress-tick rate. The manual sync offset shifts the
                // cursor so lines land earlier or later as requested.
                LaunchedEffect(smoothProgress, syncOffsetMs) {
                    lyricsViewState.updatePosition(smoothProgress - syncOffsetMs)
                }

                LyricsView(
                    state = lyricsViewState,
                    settings = settings,
                    contentPadding = contentPadding,
                    fadingEdges = fadingEdges,
                    contentColor = contentColor,
                    isPowerSaveMode = isPowerSaveMode,
                    isExpanded = isExpanded,
                    onSeekTo = onSeekTo
                )
            }
        }
    }
}

@Composable
private fun ProviderSwitchDialog(
    activeProvider: String?,
    switchState: LyricsViewModel.ProviderSwitchState,
    liveResults: Map<String, RawLyrics.Remote?>,
    syncOffsetMs: Long,
    onSwitch: (LyricsProvider) -> Unit,
    onSyncOffset: (Long) -> Unit,
    onResetSyncOffset: () -> Unit,
    onDismiss: () -> Unit
) {
    var dimmed by remember { mutableStateOf(false) }
    val windowAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.5f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "providerDialogWindowAlpha"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.switch_lyrics_provider_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                DialogWindowAlpha(windowAlpha)
                // Every policy-available provider gets a row, including the
                // ones disabled in network settings: the user can tap a
                // disabled provider to fetch from just that one, on demand
                // (applyProviderLyrics ignores the provider setting). Only
                // ENABLED providers take part in the background race, so
                // disabled rows start as "Disabled — tap to fetch"
                // instead of a live result.
                val visibleProviders = remember {
                    LyricsProvider.entries.filter { it.isAvailableForCurrentPolicy }
                }
                val searchingLabel = stringResource(R.string.searching_lyrics_provider)
                val noLyricsLabel = stringResource(R.string.no_lyrics_found)
                val wordSyncedLabel = stringResource(R.string.word_by_word_lyrics_provider_status)
                val lineSyncedLabel = stringResource(R.string.line_synced_lyrics_provider_status)
                val plainLabel = stringResource(R.string.plain_text_lyrics_provider_status)
                val disabledLabel = stringResource(R.string.disabled_lyrics_provider_status)
                val rows = remember(
                    activeProvider, switchState, liveResults, visibleProviders
                ) {
                    sortedProviderRows(
                        activeProvider = activeProvider,
                        switchState = switchState,
                        liveResults = liveResults,
                        visibleProviders = visibleProviders,
                        searchingLabel = searchingLabel,
                        noLyricsLabel = noLyricsLabel,
                        wordSyncedLabel = wordSyncedLabel,
                        lineSyncedLabel = lineSyncedLabel,
                        plainLabel = plainLabel,
                        disabledLabel = disabledLabel
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(rows, key = { it.provider.name }) { row ->
                        val itemBgColor = when {
                            row.isSelected -> MaterialTheme.colorScheme.primaryContainer
                            row.hasLyrics -> MaterialTheme.colorScheme.surfaceContainer
                            row.isPending -> MaterialTheme.colorScheme.surfaceContainerLow
                            else -> MaterialTheme.colorScheme.surfaceContainerLowest
                        }
                        SegmentedListItem(
                            onClick = { onSwitch(row.provider) },
                            shapes = ListItemDefaults.segmentedShapes(row.index, rows.size),
                            supportingContent = {
                                Text(text = row.subtitle)
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lyrics_24dp),
                                    contentDescription = row.provider.displayName,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            trailingContent = {
                                when {
                                    row.isSelected -> {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check_24dp),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    row.isLoading -> {
                                        CircularWavyProgressIndicator(
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            colors = if (row.isSelected) {
                                ListItemDefaults.segmentedColors(
                                    containerColor = itemBgColor,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    supportingContentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                        alpha = 0.85f
                                    )
                                )
                            } else {
                                ListItemDefaults.segmentedColors(
                                    containerColor = itemBgColor
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(
                                    if ((!row.hasLyrics && !row.isPending) || row.isDisabled) {
                                        0.5f
                                    } else {
                                        1f
                                    }
                                )
                        ) {
                            Text(
                                text = row.provider.displayName,
                                fontWeight = if (row.isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                SyncOffsetRow(
                    syncOffsetMs = syncOffsetMs,
                    onSyncOffset = { delta ->
                        dimmed = true
                        onSyncOffset(delta)
                    },
                    onResetSyncOffset = {
                        dimmed = true
                        onResetSyncOffset()
                    },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    )
}

@Composable
private fun DialogWindowAlpha(alpha: Float) {
    val view = LocalView.current
    LaunchedEffect(alpha) {
        var target: View? = view
        while (target != null && target !is DialogWindowProvider) {
            target = target.parent as? View
        }
        val window = (target as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        val attributes = window.attributes
        attributes.alpha = alpha
        window.attributes = attributes
    }
}

private data class ProviderRow(
    val provider: LyricsProvider,
    val subtitle: String,
    val isSelected: Boolean,
    val hasLyrics: Boolean,
    val isPending: Boolean,
    val isLoading: Boolean,
    val isDisabled: Boolean,
    val index: Int
)

private fun sortedProviderRows(
    activeProvider: String?,
    switchState: LyricsViewModel.ProviderSwitchState,
    liveResults: Map<String, RawLyrics.Remote?>,
    visibleProviders: List<LyricsProvider>,
    searchingLabel: String,
    noLyricsLabel: String,
    wordSyncedLabel: String,
    lineSyncedLabel: String,
    plainLabel: String,
    disabledLabel: String
): List<ProviderRow> {
    val rows = visibleProviders.map { provider ->
        val remote = liveResults[provider.displayName]
        // Providers deliver word-level content through the synced slot
        // (YouLyPlus/BetterLyrics TTML, Netease klyric); detect it for the
        // row badge with the same heuristic the merge uses.
        val isWordSynced = remote?.hasWordSynced == true ||
                remote?.synced?.isWordLevel() == true
        val isSynced = remote?.hasSynced == true || isWordSynced
        val hasLyrics = remote != null && (isSynced || remote.hasPlain)
        val isDisabled = !provider.isEnabled
        // Only enabled providers take part in the background race, so only
        // they can be "pending" — a disabled row would otherwise spin
        // forever waiting for a result the race never produces.
        val isPending = provider.isEnabled && !liveResults.containsKey(provider.displayName)
        val isLoading = switchState.loadingProvider == provider
        val subtitle = when {
            switchState.failureProvider == provider -> noLyricsLabel
            isLoading -> searchingLabel
            isPending -> searchingLabel
            isDisabled && !hasLyrics -> disabledLabel
            !hasLyrics -> noLyricsLabel
            isWordSynced -> wordSyncedLabel
            isSynced -> lineSyncedLabel
            else -> plainLabel
        }
        Triple(
            provider,
            ProviderRow(
                provider = provider,
                subtitle = subtitle,
                isSelected = provider.displayName == activeProvider,
                hasLyrics = hasLyrics,
                isPending = isPending || isLoading,
                isLoading = isPending || isLoading,
                isDisabled = isDisabled,
                index = 0
            ),
            if (provider.displayName == activeProvider) 2
            else if (hasLyrics) 1 else 0
        )
    }
    // Enabled providers first (active > has lyrics > no result), disabled
    // providers pinned below them: they can't take part in the race, so
    // they never have live results to rank.
    return rows
        .sortedWith(
            compareByDescending<Triple<LyricsProvider, ProviderRow, Int>> { it.second.isDisabled.not() }
                .thenByDescending { it.third }
        )
        .mapIndexed { index, (_, row, _) ->
            row.copy(index = index)
        }
}

@Composable
private fun SyncOffsetRow(
    syncOffsetMs: Long,
    onSyncOffset: (Long) -> Unit,
    onResetSyncOffset: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The center preset doubles as the live readout: it shows the current
    // offset (+0.5s, -0.3s...) and settles back to 0.0s once reset.
    val resetLabel = remember(syncOffsetMs) {
        if (syncOffsetMs == 0L) "0.0s"
        else String.format(Locale.US, "%+.1fs", syncOffsetMs / 1000f)
    }
    val presetValues = remember {
        listOf(-500L, -100L, 0L, 100L, 500L)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        presetValues.forEachIndexed { index, delta ->
            val isReset = delta == 0L
            val label = when {
                isReset -> resetLabel
                delta < 0 -> String.format(Locale.US, "-%.1fs", kotlin.math.abs(delta) / 1000f)
                else -> String.format(Locale.US, "+%.1fs", delta / 1000f)
            }
            val buttonShape = when (index) {
                0 -> RoundedCornerShape(
                    topStart = 20.dp, bottomStart = 20.dp, topEnd = 6.dp, bottomEnd = 6.dp
                )
                presetValues.size - 1 -> RoundedCornerShape(
                    topEnd = 20.dp, bottomEnd = 20.dp, topStart = 6.dp, bottomStart = 6.dp
                )
                else -> RoundedCornerShape(6.dp)
            }
            val containerColor = when {
                isReset && syncOffsetMs != 0L -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
            val itemContentColor = when {
                isReset && syncOffsetMs != 0L -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
            FilledTonalButton(
                onClick = {
                    if (isReset) onResetSyncOffset() else onSyncOffset(delta)
                },
                shape = buttonShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = containerColor,
                    contentColor = itemContentColor
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isReset && syncOffsetMs != 0L) FontWeight.ExtraBold else FontWeight.Bold
                    )
                }
            }
        }
    }
}
