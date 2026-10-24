package com.mardous.booming.ui.screen.player.styles.spatialstyle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mardous.booming.R
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.ui.screen.player.PlayerViewModel
import kotlin.math.sin

@Composable
fun SpatialControlsScreen(
    playerViewModel: PlayerViewModel,
    scheme: PlayerColorScheme
) {
    val accent = Color(scheme.primaryColor)
    val contentColor = Color(scheme.onSurfaceColor)
    val secondaryColor = Color(scheme.onSurfaceVariantColor)

    val currentSong by playerViewModel.currentSongFlow.collectAsStateWithLifecycle()
    val isPlaying by playerViewModel.isPlayingFlow.collectAsStateWithLifecycle()
    val position by playerViewModel.progressFlow.collectAsStateWithLifecycle()
    val duration by playerViewModel.durationFlow.collectAsStateWithLifecycle()
    val shuffleMode by playerViewModel.shuffleModeFlow.collectAsStateWithLifecycle()
    val repeatMode by playerViewModel.repeatModeFlow.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(scheme.surfaceColor))
            .padding(horizontal = 24.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = currentSong.title,
            color = contentColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = currentSong.artistName,
            color = secondaryColor,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(4.dp))

        SpatialWavySlider(
            position = position,
            duration = duration,
            accent = accent,
            contentColor = contentColor,
            onSeek = { playerViewModel.seekTo(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        SpatialTransportControls(
            isPlaying = isPlaying,
            shuffleEnabled = shuffleMode,
            repeatMode = repeatMode,
            accent = accent,
            contentColor = contentColor,
            onPlayPause = { playerViewModel.togglePlayPause() },
            onPrevious = { playerViewModel.seekToPrevious() },
            onNext = { playerViewModel.seekToNext() },
            onShuffle = { playerViewModel.toggleShuffleMode() },
            onRepeat = { playerViewModel.cycleRepeatMode() }
        )
    }
}

@Composable
private fun SpatialWavySlider(
    position: Long,
    duration: Long,
    accent: Color,
    contentColor: Color,
    onSeek: (Long) -> Unit
) {
    val safeDuration = if (duration > 0) duration.toFloat() else 1f
    val fraction = (position.toFloat() / safeDuration).coerceIn(0f, 1f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val f = offset.x / size.width.toFloat()
                        onSeek((safeDuration * f).toLong())
                    }
                }
        ) {
            val waveHeight = 14.dp.toPx()
            val waveLength = 40.dp.toPx()
            val centerY = size.height / 2
            val progressX = size.width * fraction

            val playedPath = Path()
            var playedStarted = false
            var x = 0f
            while (x <= size.width) {
                val amplitude = if (x < progressX) waveHeight * 0.4f else waveHeight * 0.25f
                val y = centerY + sin((x / waveLength) * 2 * Math.PI).toFloat() * amplitude
                if (x <= progressX) {
                    if (!playedStarted) {
                        playedPath.moveTo(x, centerY)
                        playedStarted = true
                    } else {
                        playedPath.lineTo(x, y)
                    }
                }
                x += 2.dp.toPx()
            }

            drawLine(
                color = contentColor.copy(alpha = 0.25f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 3.dp.toPx()
            )
            drawPath(
                path = playedPath,
                color = accent,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(progressX - 6.dp.toPx(), centerY - 6.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(12.dp.toPx(), 12.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(position),
                color = contentColor,
                fontSize = 12.sp
            )
            Text(
                text = "-${formatDuration((duration - position).coerceAtLeast(0))}",
                color = contentColor.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SpatialTransportControls(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    accent: Color,
    contentColor: Color,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onShuffle) {
            Icon(
                painter = painterResource(R.drawable.ic_shuffle_24dp),
                contentDescription = "Shuffle",
                tint = if (shuffleEnabled) accent else contentColor.copy(alpha = 0.5f)
            )
        }

        IconButton(onClick = onPrevious) {
            Icon(
                painter = painterResource(R.drawable.ic_previous_24dp),
                contentDescription = "Previous",
                tint = contentColor,
                modifier = Modifier.size(36.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(accent)
                .clickable { onPlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp),
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }

        IconButton(onClick = onNext) {
            Icon(
                painter = painterResource(R.drawable.ic_next_24dp),
                contentDescription = "Next",
                tint = contentColor,
                modifier = Modifier.size(36.dp)
            )
        }

        IconButton(onClick = onRepeat) {
            Icon(
                painter = painterResource(
                    if (repeatMode >= 2) R.drawable.ic_repeat_one_24dp else R.drawable.ic_repeat_24dp
                ),
                contentDescription = "Repeat",
                tint = if (repeatMode > 0) accent else contentColor.copy(alpha = 0.5f)
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
