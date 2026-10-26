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
import com.google.android.material.textview.MaterialTextView
import com.mardous.booming.R
import com.mardous.booming.util.Preferences

/**
 * Lets the user pick the tokens that split multi-artist names, with quick
 * presets (",", "&", "@", "ft" and the historic defaults), a free text
 * field for custom tokens, and an exceptions list of artist-name fragments
 * that must never be split ("JAY-Z" with "-" enabled).
 */
class ArtistSeparatorsPreferenceDialog : DialogFragment() {

    private val active = mutableSetOf<String>()
    private val activeExceptions = mutableSetOf<String>()
    private val tokenRows = LinkedHashMap<String, MaterialCheckBox>()
    private val exceptionRows = LinkedHashMap<String, MaterialCheckBox>()
    private lateinit var tokenContainer: LinearLayout
    private lateinit var exceptionContainer: LinearLayout
    private lateinit var input: EditText
    private lateinit var exceptionInput: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        active.addAll(Preferences.artistSeparators)
        activeExceptions.addAll(Preferences.artistSeparatorExceptions)

        tokenContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        exceptionContainer = LinearLayout(context).apply {
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

        val allExceptions = LinkedHashSet<String>().apply {
            addAll(activeExceptions)
        }
        for (exception in allExceptions) {
            exceptionContainer.addView(exceptionRow(context, exception))
        }

        input = addInput(context, R.string.artist_separators_hint)
        exceptionInput = addInput(context, R.string.artist_separators_exception_hint)
        val separatorInputRow = inputRow(context, input, R.string.artist_separators_add) { token ->
            if (token.isNotEmpty() && !tokenRows.containsKey(token) && !active.contains(token)) {
                active.add(token)
                tokenContainer.addView(tokenRow(context, token))
            }
        }
        val exceptionInputRow = inputRow(context, exceptionInput, R.string.artist_separators_add) { exception ->
            if (exception.isNotEmpty() && !exceptionRows.containsKey(exception)) {
                activeExceptions.add(exception)
                exceptionContainer.addView(exceptionRow(context, exception))
            }
        }

        val density = resources.displayMetrics.density
        fun px(value: Int): Int = (value * density).toInt()
        fun header(titleRes: Int) = MaterialTextView(context).apply {
            text = getString(titleRes)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setPadding(0, px(12), 0, px(4))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(header(R.string.artist_separators_section))
            addView(separatorInputRow)
            addView(
                ScrollView(context).apply { addView(tokenContainer) },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (density * 200).toInt()
                )
            )
            addView(header(R.string.artist_separators_exceptions_section))
            addView(exceptionInputRow)
            addView(
                ScrollView(context).apply { addView(exceptionContainer) },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (density * 140).toInt()
                )
            )
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.artist_separators_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Preferences.artistSeparators = active.toList()
                Preferences.artistSeparatorExceptions = activeExceptions.toList()
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

    private fun exceptionRow(context: android.content.Context, exception: String): MaterialCheckBox {
        val row = MaterialCheckBox(context).apply {
            text = exception
            isChecked = exception in activeExceptions
            setOnCheckedChangeListener { _, checked ->
                if (checked) activeExceptions.add(exception) else activeExceptions.remove(exception)
            }
        }
        exceptionRows[exception] = row
        return row
    }

    private fun addInput(
        context: android.content.Context,
        hintRes: Int
    ): TextInputEditText {
        return TextInputEditText(context).apply {
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
    }

    private fun inputRow(
        context: android.content.Context,
        field: EditText,
        buttonLabelRes: Int,
        onCommit: (String) -> Unit
    ): LinearLayout {
        val addButton = MaterialButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(buttonLabelRes)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val value = field.text?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) onCommit(value)
                field.setText("")
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field)
            addView(addButton)
        }
    }
}
