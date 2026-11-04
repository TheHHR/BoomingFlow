/*
 * Copyright (c) 2024 Christians Martínez Alvarado
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
package com.mardous.booming.ui.dialogs.playlists

import androidx.core.view.isVisible
import com.mardous.booming.core.playlist.PlaylistSyncRules
import com.mardous.booming.databinding.DialogCreatePlaylistBinding

/**
 * Shared wiring for the "Synced playlist" section of the create/edit
 * playlist dialog: a switch that reveals the folder/artist/genre rule
 * fields.
 */
fun DialogCreatePlaylistBinding.setupSyncFields(initialRules: PlaylistSyncRules?) {
    val rules = initialRules ?: PlaylistSyncRules()
    playlistSyncedSwitch.isChecked = initialRules != null
    syncRulesContainer.isVisible = initialRules != null
    playlistSyncFoldersEditText.setText(rules.folders.joinToString(", "))
    playlistSyncArtistsEditText.setText(rules.artists.joinToString(", "))
    playlistSyncGenresEditText.setText(rules.genres.joinToString(", "))
    playlistSyncedSwitch.setOnCheckedChangeListener { _, isChecked ->
        syncRulesContainer.isVisible = isChecked
    }
}

/** True when the switch is on and at least one rule field is filled. */
fun DialogCreatePlaylistBinding.validateSyncFields(): Boolean {
    if (!playlistSyncedSwitch.isChecked) {
        return true
    }
    return readSyncFields().isValid()
}

fun DialogCreatePlaylistBinding.markInvalidSyncFields() {
    playlistSyncFoldersLayout.error =
        root.context.getString(com.mardous.booming.R.string.synced_playlist_rules_error)
    playlistSyncedSwitch.setOnCheckedChangeListener { _, isChecked ->
        syncRulesContainer.isVisible = isChecked
        if (isChecked) {
            playlistSyncFoldersLayout.error = null
        }
    }
}

/** The parsed rules, or null when the synced switch is off. */
fun DialogCreatePlaylistBinding.collectSyncRules(): PlaylistSyncRules? {
    if (!playlistSyncedSwitch.isChecked) {
        return null
    }
    return readSyncFields()
}

private fun DialogCreatePlaylistBinding.readSyncFields(): PlaylistSyncRules =
    PlaylistSyncRules.parse(
        folders = playlistSyncFoldersEditText.text?.toString(),
        artists = playlistSyncArtistsEditText.text?.toString(),
        genres = playlistSyncGenresEditText.text?.toString()
    )
