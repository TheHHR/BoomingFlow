package com.mardous.booming.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide accent color state sourced from the current song's artwork.
 * Updated by the player's color pipeline; consumed by the Compose theme
 * and View-layer tint targets when "Follow player color" is enabled.
 */
object PlayerAccentState {

    private val _accentColor = MutableStateFlow(0)
    val accentColor: StateFlow<Int> = _accentColor.asStateFlow()

    val hasAccent: Boolean
        get() = _accentColor.value != 0

    fun update(accent: Int) {
        if (accent != 0) {
            _accentColor.value = accent
        }
    }

    fun clear() {
        _accentColor.value = 0
    }
}
