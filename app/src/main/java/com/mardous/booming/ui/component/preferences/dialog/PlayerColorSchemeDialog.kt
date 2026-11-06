/*
 * Copyright (c) 2026 BoomingFlow contributors
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

package com.mardous.booming.ui.component.preferences.dialog

import android.app.Dialog
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.ui.component.compose.DialogListItemWithRadio
import com.mardous.booming.ui.theme.BoomingMusicTheme
import com.mardous.booming.extensions.create
import com.mardous.booming.extensions.resources.applyPlayerAccent
import com.mardous.booming.util.Preferences

/**
 * Background (player color scheme) picker for the Now Playing screen.
 * Replaces the old style/transition picker: the only style left is
 * Expressive, so this dialog offers just the background modes.
 */
class PlayerColorSchemeDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Matches the sibling preference dialogs: no Material title/buttons
        // (the dialog window background would otherwise sandwich the Compose
        // content), uniform surfaceContainerHigh, selection persists and
        // dismisses immediately.
        val modes = listOf(
            PlayerColorSchemeMode.AppTheme,
            PlayerColorSchemeMode.MaterialYou,
            PlayerColorSchemeMode.Spatial,
            PlayerColorSchemeMode.Blur
        )
        return MaterialAlertDialogBuilder(requireContext())
            .setView(
                ComposeView(requireContext()).apply {
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                    )
                    setContent {
                        BoomingMusicTheme {
                            var selectedMode by remember {
                                mutableStateOf(Preferences.nowPlayingColorSchemeMode)
                            }
                            DialogScreen(
                                modes = modes,
                                selected = selectedMode,
                                onModeClick = { mode ->
                                    selectedMode = mode
                                    Preferences.setNowPlayingColorSchemeMode(mode)
                                    dialog?.dismiss()
                                }
                            )
                        }
                    }
                }
            )
            .create { it.applyPlayerAccent() }
    }

    @Composable
    private fun DialogScreen(
        modes: List<PlayerColorSchemeMode>,
        selected: PlayerColorSchemeMode,
        onModeClick: (PlayerColorSchemeMode) -> Unit
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.wrapContentHeight()
        ) {
            LazyColumn(
                state = rememberLazyListState(modes.indexOf(selected).coerceAtLeast(0)),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(vertical = 24.dp)
            ) {
                items(modes) { mode ->
                    DialogListItemWithRadio(
                        title = stringResource(mode.titleRes),
                        subtitle = stringResource(mode.descriptionRes),
                        isSelected = mode == selected,
                        onClick = { onModeClick(mode) }
                    )
                }
            }
        }
    }

    companion object {
        fun newInstance(): PlayerColorSchemeDialog = PlayerColorSchemeDialog()
    }
}
