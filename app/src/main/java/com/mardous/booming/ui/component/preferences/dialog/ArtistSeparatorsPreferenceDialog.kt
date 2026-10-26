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
 * Lets the user pick the tokens that split multi-artist names. Ships with
 * quick presets (",", "&", "@", "ft" and the historic defaults) plus a free
 * text field for custom tokens.
 */
class ArtistSeparatorsPreferenceDialog : DialogFragment() {

    private val active = mutableSetOf<String>()
    private val tokenRows = LinkedHashMap<String, MaterialCheckBox>()
    private lateinit var tokenContainer: LinearLayout
    private lateinit var input: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        active.addAll(Preferences.artistSeparators)

        tokenContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val presets = listOf(",", "&", "@", "ft", "/", ";", "feat", "with", "x")
        val allTokens = LinkedHashSet<String>().apply {
            addAll(presets)
            addAll(active)
        }
        for (token in allTokens) {
            tokenContainer.addView(tokenRow(context, token))
        }

        input = TextInputEditText(context).apply {
            hint = getString(R.string.artist_separators_hint)
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
                val token = input.text?.toString()?.trim().orEmpty()
                if (token.isNotEmpty() && !tokenRows.containsKey(token) && !active.contains(token)) {
                    active.add(token)
                    tokenContainer.addView(tokenRow(context, token))
                }
                input.setText("")
            }
        }
        val density = resources.displayMetrics.density
        fun px(value: Int): Int = (value * density).toInt()
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, px(8))
            addView(input)
            addView(addButton)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(inputRow)
            addView(
                ScrollView(context).apply { addView(tokenContainer) },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (density * 320).toInt()
                )
            )
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.artist_separators_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Preferences.artistSeparators = active.toList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun tokenRow(context: android.content.Context, token: String): MaterialCheckBox {
        val row = MaterialCheckBox(context).apply {
            text = "\"$token\""
            isChecked = token in active
            setOnCheckedChangeListener { _, checked ->
                if (checked) active.add(token) else active.remove(token)
            }
        }
        tokenRows[token] = row
        return row
    }
}
