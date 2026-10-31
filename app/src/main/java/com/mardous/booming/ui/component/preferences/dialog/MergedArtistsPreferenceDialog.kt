package com.mardous.booming.ui.component.preferences.dialog

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.mardous.booming.R
import com.mardous.booming.util.Preferences

/**
 * Manage merged artists ("alias -> canonical"), same pattern as the
 * ignored-artists dialog: uncheck a row to unmerge, or add an entry
 * manually as "alias - canonical". Unmerging also drops the cached
 * cover-art URL keyed under the alias so stale pictures refresh.
 */
class MergedArtistsPreferenceDialog : DialogFragment() {

    private val active = LinkedHashMap<String, String>()
    private val rows = LinkedHashMap<String, MaterialCheckBox>()
    private lateinit var container: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        active.putAll(Preferences.artistAliases)

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        for ((alias, canonical) in active) {
            container.addView(row(context, alias, canonical))
        }

        val input = TextInputEditText(context).apply {
            hint = getString(R.string.merged_artists_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val addButton = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.artist_separators_add)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty()
                val parts = value.split("-", limit = 2).map { it.trim() }
                val alias = parts.getOrNull(0).orEmpty()
                val canonical = parts.getOrNull(1).orEmpty()
                if (alias.isNotEmpty() && canonical.isNotEmpty() &&
                    !rows.containsKey(alias.lowercase())
                ) {
                    active[alias] = canonical
                    container.addView(row(context, alias, canonical))
                }
                input.setText("")
            }
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input)
            addView(addButton)
        }

        // Rows come from the live map; unmatching on uncheck uses the
        // alias spelling so the exact entry is removed.
        val density = resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (density * 20).toInt(), (density * 8).toInt(),
                (density * 20).toInt(), 0
            )
            addView(inputRow)
            addView(
                ScrollView(context).apply { addView(container) },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (density * 320).toInt()
                )
            )
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.merged_artists_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val cleaned = active.filterKeys { alias ->
                    rows[alias.lowercase()]?.isChecked != false
                }
                val before = Preferences.artistAliases
                Preferences.artistAliases = cleaned
                // Unmerged aliases lose their cached cover art so the
                // picture re-resolves fresh for the split entry.
                val removed = before.keys.filter { alias ->
                    cleaned.keys.none { it.equals(alias, ignoreCase = true) }
                }
                for (alias in removed) {
                    Preferences.removeArtistAlias(alias)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun row(context: android.content.Context, alias: String, canonical: String): MaterialCheckBox {
        val key = alias.lowercase()
        val row = MaterialCheckBox(context).apply {
            text = "$alias → $canonical"
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    active[alias] = canonical
                } else {
                    active.removeAll { it.key.equals(alias, ignoreCase = true) }
                }
                rows.remove(key)
                removeViewSelf()
            }
        }
        rows[key] = row
        return row
    }

    private fun MaterialCheckBox.removeViewSelf() {
        (parent as? ViewGroup)?.removeView(this)
    }
}
