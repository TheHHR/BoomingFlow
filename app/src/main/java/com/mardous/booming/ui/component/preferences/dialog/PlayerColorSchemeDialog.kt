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
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceDialogFragmentCompat
import com.mardous.booming.core.model.player.PlayerColorSchemeMode
import com.mardous.booming.ui.component.compose.DialogListItemWithRadio
import com.mardous.booming.ui.theme.BoomingMusicTheme
import com.mardous.booming.util.Preferences

/**
 * Background (player color scheme) picker for the Now Playing screen.
 * Replaces the old style/transition picker: the only style left is
 * Expressive, so this dialog offers just the background modes.
 */
class PlayerColorSchemeDialog : PreferenceDialogFragmentCompat() {

    private var selectedMode: PlayerColorSchemeMode = Preferences.nowPlayingColorSchemeMode

    override fun onCreateDialogView(context: android.content.Context): View {
        val modes = listOf(
            PlayerColorSchemeMode.AppTheme,
            PlayerColorSchemeMode.MaterialYou,
            PlayerColorSchemeMode.Blur
        )
        selectedMode = Preferences.nowPlayingColorSchemeMode
        return ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                BoomingMusicTheme {
                    DialogView(
                        modes = modes,
                        selected = selectedMode,
                        onSelection = ::selectMode
                    )
                }
            }
        }
    }

    private fun selectMode(mode: PlayerColorSchemeMode) {
        selectedMode = mode
        dialog?.let {
            onClick(it, AlertDialog.BUTTON_POSITIVE)
            it.dismiss()
        }
    }

    override fun onBindDialogView(view: View) {}

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        builder.setTitle(null)
        builder.setPositiveButton(null, null)
        builder.setNegativeButton(null, null)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            Preferences.setNowPlayingColorSchemeMode(selectedMode)
        }
    }

    @Composable
    private fun DialogView(
        modes: List<PlayerColorSchemeMode>,
        selected: PlayerColorSchemeMode,
        onSelection: (PlayerColorSchemeMode) -> Unit
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
                        onClick = { onSelection(mode) }
                    )
                }
            }
        }
    }

    companion object {
        fun newInstance(): PlayerColorSchemeDialog = PlayerColorSchemeDialog()
    }
}
