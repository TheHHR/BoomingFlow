package com.mardous.booming.ui.component.preferences.dialog

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
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
 * Manage the artist entries hidden from the artists page ("Not artist"
 * action): uncheck a row to restore the artist, or add a name manually.
 */
class IgnoredArtistsPreferenceDialog : DialogFragment() {

    private val active = mutableSetOf<String>()
    private val rows = LinkedHashMap<String, MaterialCheckBox>()
    private lateinit var container: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        active.addAll(Preferences.ignoredArtists)

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val input = TextInputEditText(context).apply {
            hint = getString(R.string.ignored_artists_hint)
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
                if (value.isNotEmpty() && !rows.containsKey(value.lowercase())) {
                    active.add(value)
                    container.addView(row(context, value))
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
            .setTitle(R.string.ignored_artists_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Preferences.ignoredArtists = active.toList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun row(context: android.content.Context, name: String): MaterialCheckBox {
        val key = name.lowercase()
        val row = MaterialCheckBox(context).apply {
            text = name
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                if (checked) active.add(name) else {
                    active.removeAll { it.equals(name, ignoreCase = true) }
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
