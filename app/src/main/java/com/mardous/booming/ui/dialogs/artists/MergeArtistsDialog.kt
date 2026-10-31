package com.mardous.booming.ui.dialogs.artists

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mardous.booming.R

/**
 * Lets the user pick the canonical entry when merging artists: a
 * radio list of the selected names, defaulting to the first one.
 * The other names become exact-string aliases of the chosen name;
 * no song tags are changed.
 */
class MergeArtistsDialog(
    private val names: List<String> = emptyList(),
    private val onCanonicalSelected: (String) -> Unit = {}
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val distinct = names.distinct()
        var checked = 0
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.merge_artists_title)
            .setMessage(R.string.merge_artists_summary)
            .setSingleChoiceItems(
                distinct.toTypedArray(),
                checked
            ) { _, which -> checked = which }
            .setPositiveButton(R.string.action_merge_artists) { _, _ ->
                distinct.getOrNull(checked)?.let { onCanonicalSelected(it) }
            }
            .setNegativeButton(R.string.close_action, null)
            .create()
    }

    companion object {
        operator fun invoke(
            names: List<String>,
            onCanonicalSelected: (String) -> Unit
        ) = MergeArtistsDialog(names, onCanonicalSelected)
    }
}
