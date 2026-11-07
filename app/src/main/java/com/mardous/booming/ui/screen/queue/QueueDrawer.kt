/*
 * Copyright (c) 2026 BoomingFlow
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
package com.mardous.booming.ui.screen.queue

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.mardous.booming.R
import com.mardous.booming.data.model.Song
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * In-player sliding queue drawer: a full-height rounded surface that
 * springs up over the expanded player - no modal window, no navigation.
 * Binary open/close with drag-to-dismiss: dragging the list down at its
 * top (or the header) drags the drawer; releasing past a quarter of its
 * height (or with a downward fling) dismisses it, a quick upward fling
 * re-opens it. The surface stays composed while closed, parked fully
 * offscreen below the player.
 */
@Composable
fun QueueDrawer(
    isExpanded: Boolean,
    onDismissRequest: () -> Unit,
    onAddToPlaylistClick: (List<Song>) -> Unit,
    onDeleteSongClick: (Song) -> Unit,
    onShareClick: (Song) -> Unit,
    onDetailsClick: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var drawerHeightPx by remember { mutableStateOf(1f) }

    // 0f = fully open (top), 1f = fully closed (below the screen).
    val offsetFraction = remember { Animatable(1f) }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            offsetFraction.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            )
        } else {
            offsetFraction.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            )
        }
    }

    fun reopenDrawer() {
        coroutineScope.launch {
            offsetFraction.animateTo(
                0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
            )
        }
    }

    fun settleRelease(velocityY: Float) {
        when {
            velocityY > 1500f -> onDismissRequest()
            velocityY < -1500f -> reopenDrawer()
            offsetFraction.value > 0.25f -> onDismissRequest()
            else -> reopenDrawer()
        }
    }

    val drawerConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // While the drawer is already dragged down, claim further
                // downward drags so the list stays put and the drawer follows.
                if (available.y > 0f && offsetFraction.value > 0f) {
                    val target = (offsetFraction.value + available.y / drawerHeightPx)
                        .coerceIn(0f, 1f)
                    coroutineScope.launch { offsetFraction.snapTo(target) }
                    return available
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // A downward drag the list cannot consume (it sits at its
                // top) starts the close drag.
                if (available.y > 0f) {
                    val target = (offsetFraction.value + available.y / drawerHeightPx)
                        .coerceIn(0f, 1f)
                    coroutineScope.launch { offsetFraction.snapTo(target) }
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offsetFraction.value > 0f) {
                    settleRelease(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    // Safety net: a slow finger release produces no fling callback, which
    // would leave the drawer stuck mid-way - settle it to the nearest edge
    // once the offset stops changing. Guarded by isRunning so open/close
    // animations never fight this (they are also Animatable "runs").
    LaunchedEffect(isExpanded) {
        snapshotFlow { offsetFraction.value }
            .collectLatest { value ->
                if (isExpanded && !offsetFraction.isRunning && value > 0.001f && value < 0.999f) {
                    delay(150)
                    settleRelease(0f)
                }
            }
    }

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { drawerHeightPx = it.height.coerceAtLeast(1).toFloat() }
                .offset { IntOffset(0, (offsetFraction.value * drawerHeightPx).roundToInt()) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                val closeLabel = stringResource(R.string.action_close)
                QueueScreen(
                    dragHandle = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDismissRequest() }
                                .padding(top = 12.dp, bottom = 12.dp)
                                .semantics {
                                    contentDescription = closeLabel
                                    role = Role.Button
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(4.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    },
                    headerModifier = Modifier.draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dy ->
                            val target = (offsetFraction.value + dy / drawerHeightPx)
                                .coerceIn(0f, 1f)
                            coroutineScope.launch { offsetFraction.snapTo(target) }
                        },
                        onDragStopped = { velocityY -> settleRelease(velocityY) }
                    ),
                    contentModifier = Modifier.nestedScroll(drawerConnection),
                    onAddToPlaylistClick = onAddToPlaylistClick,
                    onDeleteSongClick = onDeleteSongClick,
                    onShareClick = onShareClick,
                    onDetailsClick = onDetailsClick
                )
            }
        }
    }
}
