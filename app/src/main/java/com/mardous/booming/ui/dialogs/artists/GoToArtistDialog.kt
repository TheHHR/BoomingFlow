package com.mardous.booming.ui.dialogs.artists

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mardous.booming.R

/**
 * Go To > Artist picker for multi-artist songs: one row per split
 * artist name (alias-resolved, ignored artists already removed).
 * Tapping a row opens that artist's detail page directly.
 */
class GoToArtistDialog(
    private val names: List<String> = emptyList(),
    private val onArtistSelected: (String) -> Unit = {}
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.go_to_artist_label)
            .setItems(names.toTypedArray()) { _, which ->
                names.getOrNull(which)?.let { onArtistSelected(it) }
            }
            .setNegativeButton(R.string.close_action, null)
            .create()
    }

    companion object {
        operator fun invoke(
            names: List<String>,
            onArtistSelected: (String) -> Unit
        ) = GoToArtistDialog(names, onArtistSelected)
    }
}
