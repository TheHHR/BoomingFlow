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

package com.mardous.booming.core.model.theme

import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import com.mardous.booming.R
import com.mardous.booming.util.GeneralTheme
import com.mardous.booming.util.Preferences

class AppTheme private constructor(
    val id: String,
    @StyleRes
    val themeRes: Int,
    val applyDynamicColors: Boolean
) {

    val isBlackTheme: Boolean
        get() = id == GeneralTheme.BLACK

    @get:StyleRes
    // Dynamic colors override black-theme surfaces, so the overlay is applied afterward
    val dynamicColorOverlay: Int?
        get() = if (isBlackTheme) R.style.BlackThemeOverlay else null

    enum class Mode(@StyleRes val themeRes: Int) {
        Light(R.style.Theme_Booming_Light),
        Dark(R.style.Theme_Booming),
        Black(R.style.Theme_Booming_Black),
        FollowSystem(R.style.Theme_Booming_FollowSystem)
    }

    companion object {
        /**
         * The app theme is always Material You (wallpaper-based dynamic colors)
         * when the platform supports it; the classic palette is only used on
         * devices below Android 12, where dynamic colors are unavailable.
         */
        fun createAppTheme(): AppTheme {
            val generalTheme = Preferences.generalTheme
            val themeMode = Preferences.getThemeMode(generalTheme)
            return AppTheme(
                id = generalTheme,
                themeRes = themeMode.themeRes,
                applyDynamicColors = DynamicColors.isDynamicColorAvailable()
            )
        }
    }
}
