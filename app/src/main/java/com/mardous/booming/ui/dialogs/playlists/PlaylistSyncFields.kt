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

import android.text.Editable
import android.text.TextWatcher
import androidx.core.view.isVisible
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mardous.booming.R
import com.mardous.booming.core.playlist.PlaylistSyncRules
import com.mardous.booming.core.playlist.SyncRuleValidationData
import com.mardous.booming.core.playlist.validate
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
    // Typing clears the field's validation error.
    clearErrorOnEdit(playlistSyncFoldersLayout, playlistSyncFoldersEditText)
    clearErrorOnEdit(playlistSyncArtistsLayout, playlistSyncArtistsEditText)
    clearErrorOnEdit(playlistSyncGenresLayout, playlistSyncGenresEditText)
}

private fun DialogCreatePlaylistBinding.clearErrorOnEdit(
    layout: TextInputLayout,
    editText: TextInputEditText
) {
    editText.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            layout.error = null
        }
    })
}

/**
 * Validates the rule input against [data] and shows a per-field error
 * for every entry that resolves to nothing ("Invalid folder: X", etc.).
 * Returns true when the input is acceptable (or the sync switch is off).
 *
 * A null [data] means the library snapshot could not be fetched; in that
 * case only the structural checks run and saving is never blocked by a
 * transient failure.
 */
fun DialogCreatePlaylistBinding.validateSyncFields(
    data: SyncRuleValidationData?
): Boolean {
    if (!playlistSyncedSwitch.isChecked) {
        return true
    }
    val rules = readSyncFields()
    if (!rules.isValid()) {
        playlistSyncFoldersLayout.error =
            root.context.getString(R.string.synced_playlist_rules_error)
        return false
    }
    if (data == null) {
        return true
    }
    val errors = rules.validate(data)
    if (errors.invalidFolders.isNotEmpty()) {
        playlistSyncFoldersLayout.error = root.context.getString(
            R.string.sync_invalid_folder_x,
            errors.invalidFolders.joinToString(", ")
        )
    }
    if (errors.invalidArtists.isNotEmpty()) {
        playlistSyncArtistsLayout.error = root.context.getString(
            R.string.sync_invalid_artist_x,
            errors.invalidArtists.joinToString(", ")
        )
    }
    if (errors.invalidGenres.isNotEmpty()) {
        playlistSyncGenresLayout.error = root.context.getString(
            R.string.sync_invalid_genre_x,
            errors.invalidGenres.joinToString(", ")
        )
    }
    return !errors.hasErrors
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
