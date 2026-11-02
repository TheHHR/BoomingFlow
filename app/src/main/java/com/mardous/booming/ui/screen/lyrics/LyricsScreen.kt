package com.mardous.booming.ui.screen.lyrics

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.mardous.booming.R
import com.mardous.booming.core.model.LibraryMargin
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.core.model.lyrics.LyricsViewSettings.BackgroundEffect
import com.mardous.booming.core.model.lyrics.LyricsViewState
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.data.model.Song
import com.mardous.booming.data.model.lyrics.RawLyrics
import com.mardous.booming.data.model.lyrics.SyncedLyrics
import com.mardous.booming.data.model.lyrics.isWordLevel
import com.mardous.booming.data.remote.lyrics.api.LyricsProvider
import com.mardous.booming.extensions.isPowerSaveMode
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.ui.component.compose.AnimatedEqBars
import com.mardous.booming.ui.component.compose.color.extractGradientColors
import com.mardous.booming.ui.component.compose.decoration.FadingEdges
import com.mardous.booming.ui.component.compose.decoration.animatedGradient
import com.mardous.booming.ui.component.compose.decoration.fadingEdges
import com.mardous.booming.ui.component.compose.decoration.spatialBackground
import com.mardous.booming.ui.component.compose.lyrics.LyricsView
import com.mardous.booming.ui.component.views.PlaceholderDrawable
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.theme.PlayerTheme
import com.mardous.booming.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinActivityViewModel
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
    var position by remember(id) { mutableLongStateOf(playerProgress) }
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
fun LyricsScreen(
    libraryViewModel: LibraryViewModel = koinActivityViewModel(),
    lyricsViewModel: LyricsViewModel = koinActivityViewModel(),
    playerViewModel: PlayerViewModel = koinActivityViewModel(),
    onEditClick: (Song) -> Unit
) {
    val context = LocalContext.current
    val isPowerSaveMode = context.isPowerSaveMode()

    val miniPlayerMargin by libraryViewModel.getMiniPlayerMargin().observeAsState(LibraryMargin(0))

    val lyricsViewSettings by lyricsViewModel.fullLyricsViewSettings.collectAsState()
    val uiState by lyricsViewModel.lyricsUiState.collectAsState()
    val syncOffsetMs by lyricsViewModel.lyricsSyncOffsetMs.collectAsStateWithLifecycle()

    val song by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlayingFlow.collectAsStateWithLifecycle()

    var gradientColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    var spatialColors by remember { mutableStateOf<List<Color>>(emptyList()) }
    val playerColorScheme by playerViewModel.colorSchemeFlow.collectAsStateWithLifecycle()
    val paletteColor by playerViewModel.paletteColorFlow.collectAsStateWithLifecycle()
    LaunchedEffect(song, playerColorScheme, paletteColor) {
        if (isPowerSaveMode)
            return@LaunchedEffect

        val effect = lyricsViewSettings.backgroundEffect
        if (effect == BackgroundEffect.Gradient || effect == BackgroundEffect.Spatial) {
            if (effect == BackgroundEffect.Spatial) {
                // The spatial fluid mesh uses the album's vibrant/dark
                // swatches over the player surface color, with a plain
                // scheme fallback while no palette swatches exist yet.
                val swatches = paletteColor.spatialColors
                spatialColors = if (swatches.vibrant != 0) {
                    listOf(
                        Color(if (playerColorScheme.surfaceColor != 0) playerColorScheme.surfaceColor else paletteColor.backgroundColor),
                        Color(swatches.vibrant),
                        Color(swatches.darkVibrant),
                        Color(swatches.darkMuted)
                    )
                } else {
                    listOf(
                        Color(if (playerColorScheme.surfaceColor != 0) playerColorScheme.surfaceColor else paletteColor.backgroundColor),
                        Color(paletteColor.primaryColor)
                    ).filter { it != Color(0) }
                }
                return@LaunchedEffect
            }
            // Prefer the player's extracted scheme so the lyrics background
            // uses the exact same colors as the rest of the app; the cover
            // extraction below is only a fallback while no scheme exists.
            val schemeColors = listOf(playerColorScheme.surfaceColor, playerColorScheme.primaryColor)
                .takeIf { colors -> colors.none { it == 0 } }
                ?.map { Color(it) }
            if (schemeColors != null) {
                gradientColors = schemeColors
            } else {
                withContext(Dispatchers.Default) {
                    val result = SingletonImageLoader.get(context).execute(
                        ImageRequest.Builder(context)
                            .data(song)
                            .build()
                    )
                    gradientColors = if (result is SuccessResult) {
                        result.image.toBitmap().extractGradientColors(
                            context.resolveColor(PlaceholderDrawable.BACKGROUND_COLOR)
                        )
                    } else {
                        emptyList()
                    }
                }
            }
        }
    }

    var hasBackgroundEffects by remember { mutableStateOf(false) }
    var showProviderDialog by remember { mutableStateOf(false) }

    // Small adaptive-color touchpoints: when "follow player color" is on,
    // the FAB picks up the extracted scheme like the rest of the app.
    val followPlayerColor = Preferences.followPlayerColor
    val fabContainer =
        if (followPlayerColor && playerColorScheme.surfaceColor != 0) {
            Color(playerColorScheme.surfaceColor)
        } else {
            MaterialTheme.colorScheme.surface
        }
    val fabContent =
        if (followPlayerColor && playerColorScheme.onSurfaceColor != 0) {
            Color(playerColorScheme.onSurfaceColor)
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Scaffold(
        contentWindowInsets = WindowInsets
            .navigationBars
            .add(WindowInsets(bottom = miniPlayerMargin.totalMargin)),
        floatingActionButton = {
            FloatingActionButton(
                onClick = {},
                containerColor = fabContainer,
                contentColor = fabContent
            ) {
                // Tap opens the provider switch dialog; tap and hold opens
                // the full lyrics editor.
                Box(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { showProviderDialog = true },
                            onLongClick = { onEditClick(song) }
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit_note_24dp),
                        contentDescription = stringResource(R.string.switch_lyrics_provider_title)
                    )
                }
            }
        },
        modifier = Modifier.keepScreenOn()
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = Triple(lyricsViewSettings.backgroundEffect, gradientColors, spatialColors),
                transitionSpec = {
                    fadeIn(tween(1000)).togetherWith(fadeOut(tween(1000)))
                }
            ) { (effect, gradientColors, spatialColors) ->
                when {
                    effect.isGradient && gradientColors.size >= 2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .animatedGradient(gradientColors, isPlaying)
                        )
                        hasBackgroundEffects = true
                    }

                    effect.isSpatial && spatialColors.size >= 2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .spatialBackground(
                                    backgroundColor = spatialColors[0],
                                    vibrant = spatialColors.getOrElse(1) { spatialColors[0] },
                                    darkVibrant = spatialColors.getOrElse(2) { spatialColors[1] },
                                    darkMuted = spatialColors.getOrElse(3) { spatialColors[0] },
                                    isAnimated = isPlaying
                                )
                        )
                        hasBackgroundEffects = true
                    }

                    effect.isBlur -> {
                        val backgroundColor = Color(0xFF1A1A1A)

                        Box(modifier = Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = song,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(90.dp)
                                    .drawWithContent {
                                        drawContent()

                                        drawRect(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    backgroundColor.copy(alpha = 0.8f),
                                                    backgroundColor
                                                ),
                                                radius = size.minDimension * 0.9f
                                            )
                                        )
                                    }
                            )
                        }
                        hasBackgroundEffects = true
                    }

                    else -> {
                        hasBackgroundEffects = false
                    }
                }
            }

            LyricsSurface(
                playerViewModel = playerViewModel,
                syncOffsetMs = syncOffsetMs,
                uiState = uiState,
                settings = lyricsViewSettings,
                contentPadding = PaddingValues(vertical = 96.dp, horizontal = 16.dp),
                fadingEdges = FadingEdges(top = 56.dp, bottom = 32.dp),
                textAlign = TextAlign.Start,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                hasBackgroundEffects = hasBackgroundEffects,
                onSeekTo = { position ->
                    playerViewModel.seekTo(position)
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }

    if (showProviderDialog) {
        val switchState by lyricsViewModel.providerSwitchState.collectAsState()
        val liveResults by lyricsViewModel.providerLiveResults.collectAsState()
        LaunchedEffect(song.id) {
            lyricsViewModel.refreshProviderResults(song)
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
            onSwitch = { provider -> lyricsViewModel.switchToProvider(song, provider) },
            onSyncOffset = { delta -> lyricsViewModel.adjustLyricsSyncOffset(delta) },
            onResetSyncOffset = { lyricsViewModel.resetLyricsSyncOffset() },
            onDismiss = { showProviderDialog = false }
        )
    }
}

@Composable
fun CoverLyricsScreen(
    lyricsViewModel: LyricsViewModel,
    playerViewModel: PlayerViewModel,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPowerSaveMode = context.isPowerSaveMode()

    val isPlaying by playerViewModel.isPlayingFlow.collectAsStateWithLifecycle()

    val lyricsViewSettings by lyricsViewModel.playerLyricsViewSettings.collectAsState()
    val uiState by lyricsViewModel.lyricsUiState.collectAsState()
    val syncOffsetMs by lyricsViewModel.lyricsSyncOffsetMs.collectAsState()

    val playerColorScheme by playerViewModel.colorSchemeFlow.collectAsState(
        initial = PlayerColorScheme.themeColorScheme(context)
    )

    PlayerTheme(playerColorScheme) {
        Box(modifier = modifier.fillMaxSize()) {
            LyricsSurface(
                uiState = uiState,
                playerViewModel = playerViewModel,
                syncOffsetMs = syncOffsetMs,
                settings = lyricsViewSettings,
                contentPadding = PaddingValues(vertical = 72.dp, horizontal = 12.dp),
                fadingEdges = FadingEdges(top = 72.dp, bottom = 64.dp),
                textAlign = TextAlign.Center,
                isPlaying = isPlaying,
                isPowerSaveMode = isPowerSaveMode,
                hasBackgroundEffects = false,
                onSeekTo = { position ->
                    playerViewModel.seekTo(position)
                    if (lyricsViewSettings.resumeOnSeek) {
                        playerViewModel.play()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            FilledIconButton(
                modifier = Modifier
                    .wrapContentSize()
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface
                ),
                onClick = onExpandClick
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_open_in_full_24dp),
                    contentDescription = stringResource(R.string.action_lyrics_editor)
                )
            }
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
    contentPadding: PaddingValues,
    fadingEdges: FadingEdges,
    textAlign: TextAlign?,
    isPlaying: Boolean,
    isPowerSaveMode: Boolean,
    hasBackgroundEffects: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = when {
        hasBackgroundEffects -> Color.White
        else -> when (settings.mode) {
            LyricsViewSettings.Mode.Player -> colorScheme.onSurface
            else -> colorScheme.secondary
        }
    }
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
                    if (settings.mode == LyricsViewSettings.Mode.Full &&
                        !uiState.provider.isNullOrEmpty()
                    ) {
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
                    hasBackgroundEffects = hasBackgroundEffects,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.switch_lyrics_provider_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Only providers enabled in the network settings take part
                // in the race, so only those get rows.
                val visibleProviders = remember {
                    LyricsProvider.entries.filter {
                        it.isAvailableForCurrentPolicy && it.isEnabled
                    }
                }
                val isSearchingAny = liveResults.size < visibleProviders.size
                val searchingLabel = stringResource(R.string.searching_lyrics_provider)
                val noLyricsLabel = stringResource(R.string.no_lyrics_found)
                val wordSyncedLabel = stringResource(R.string.word_by_word_lyrics_provider_status)
                val lineSyncedLabel = stringResource(R.string.line_synced_lyrics_provider_status)
                val plainLabel = stringResource(R.string.plain_text_lyrics_provider_status)
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
                        plainLabel = plainLabel
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
                                .alpha(if (!row.hasLyrics && !row.isPending) 0.6f else 1f)
                        ) {
                            Text(
                                text = row.provider.displayName,
                                fontWeight = if (row.isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                if (isSearchingAny) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                SyncOffsetRow(
                    syncOffsetMs = syncOffsetMs,
                    onSyncOffset = onSyncOffset,
                    onResetSyncOffset = onResetSyncOffset,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

private data class ProviderRow(
    val provider: LyricsProvider,
    val subtitle: String,
    val isSelected: Boolean,
    val hasLyrics: Boolean,
    val isPending: Boolean,
    val isLoading: Boolean,
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
    plainLabel: String
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
        val isPending = !liveResults.containsKey(provider.displayName)
        val isLoading = switchState.loadingProvider == provider
        val subtitle = when {
            switchState.failureProvider == provider -> noLyricsLabel
            isLoading -> searchingLabel
            isPending -> searchingLabel
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
                index = 0
            ),
            if (provider.displayName == activeProvider) 2
            else if (hasLyrics) 1 else 0
        )
    }
    return rows.sortedByDescending { it.third }.mapIndexed { index, (_, row, _) ->
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