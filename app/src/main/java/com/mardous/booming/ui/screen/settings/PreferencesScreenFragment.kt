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

package com.mardous.booming.ui.screen.settings

import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode
import androidx.core.graphics.drawable.toDrawable
import androidx.core.os.LocaleListCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import coil3.SingletonImageLoader
import com.mardous.booming.BuildConfig
import com.mardous.booming.R
import com.mardous.booming.core.model.lyrics.LyricsViewSettings
import com.mardous.booming.data.local.room.InclExclDao
import com.mardous.booming.data.model.network.ScrobblingService
import com.mardous.booming.extensions.hasR
import com.mardous.booming.extensions.hasS
import com.mardous.booming.extensions.isTablet
import com.mardous.booming.extensions.materialSharedAxis
import com.mardous.booming.extensions.navigation.findActivityNavController
import com.mardous.booming.extensions.requestContext
import com.mardous.booming.extensions.showToast
import com.mardous.booming.extensions.utilities.dateStr
import com.mardous.booming.extensions.utilities.toEnum
import com.mardous.booming.ui.component.compose.languageEntries
import com.mardous.booming.ui.component.preferences.ProgressIndicatorPreference
import com.mardous.booming.ui.component.preferences.SwitchWithButtonPreference
import com.mardous.booming.ui.component.preferences.ThemePreference
import com.mardous.booming.ui.component.preferences.dialog.ActionOnCoverPreferenceDialog
import com.mardous.booming.ui.component.preferences.dialog.ArtistSeparatorsPreferenceDialog
import com.mardous.booming.ui.component.preferences.dialog.CategoriesPreferenceDialog
import com.mardous.booming.ui.component.preferences.dialog.IgnoredArtistsPreferenceDialog
import com.mardous.booming.ui.component.preferences.dialog.ClearQueueActionPreferenceDialog
import com.mardous.booming.ui.component.preferences.dialog.ExtraInfoPreferenceDialog
import com.mardous.booming.ui.component.preferences.dialog.LanguageSelectionDialog
import com.mardous.booming.ui.component.preferences.dialog.NowPlayingScreenPreferenceDialog
import com.mardous.booming.ui.component.preferences.dialog.SingleSelectionDialog
import com.mardous.booming.ui.component.preferences.dialog.SongClickActionPreferenceDialog
import com.mardous.booming.ui.dialogs.library.BlacklistWhitelistDialog
import com.mardous.booming.ui.screen.backup.BackupActivity
import com.mardous.booming.ui.screen.library.LibraryViewModel
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.ui.screen.scrobbling.ScrobblingServiceLoginFragment
import com.mardous.booming.online.data.innertube.SearchFilter
import com.mardous.booming.online.data.innertube.SearchItem
import com.mardous.booming.online.data.innertube.YouTubeMusic
import com.mardous.booming.online.playback.buildOnlineQueueItem
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.screen.settings.streaming.StreamingLoginDialogFragment
import com.mardous.booming.ui.screen.settings.streaming.StreamingLoginMode
import com.mardous.booming.ui.screen.settings.streaming.StreamingService
import com.mardous.booming.ui.screen.update.UpdateSearchResult
import com.mardous.booming.ui.screen.update.UpdateViewModel
import com.mardous.booming.util.ADD_EXTRA_CONTROLS
import com.mardous.booming.util.AUTO_LANGUAGE
import com.mardous.booming.util.BLACKLIST_ENABLED
import com.mardous.booming.util.COVER_DOUBLE_TAP_ACTION
import com.mardous.booming.util.COVER_LEFT_DOUBLE_TAP_ACTION
import com.mardous.booming.util.COVER_LONG_PRESS_ACTION
import com.mardous.booming.util.COVER_RIGHT_DOUBLE_TAP_ACTION
import com.mardous.booming.util.COVER_SINGLE_TAP_ACTION
import com.mardous.booming.util.ENABLE_ROTATION_LOCK
import com.mardous.booming.util.GENERAL_THEME
import com.mardous.booming.util.BLACK_THEME
import com.mardous.booming.util.IGNORE_MEDIA_STORE
import com.mardous.booming.util.LANGUAGE_NAME
import com.mardous.booming.util.LASTFM_LOGIN
import com.mardous.booming.util.ARTIST_SEPARATORS
import com.mardous.booming.util.IGNORED_ARTISTS
import com.mardous.booming.util.LIBRARY_CATEGORIES
import com.mardous.booming.util.LISTENBRAINZ_LOGIN
import com.mardous.booming.util.FOLLOW_PLAYER_COLOR
import com.mardous.booming.util.NOW_PLAYING_EXTRA_INFO
import com.mardous.booming.util.NOW_PLAYING_SCREEN
import com.mardous.booming.util.ON_CLEAR_QUEUE_ACTION
import com.mardous.booming.util.ON_SONG_CLICK_ACTION
import com.mardous.booming.util.GeneralTheme
import com.mardous.booming.util.Preferences
import com.mardous.booming.util.SPOTIFY_LOGIN
import com.mardous.booming.util.SPOTIFY_TOKEN_LOGIN
import com.mardous.booming.util.TRASH_MUSIC_FILES
import com.mardous.booming.util.USE_CUSTOM_FONT
import com.mardous.booming.util.USE_FOLDER_ART
import com.mardous.booming.util.WHITELIST_ENABLED
import com.mardous.booming.util.YOUTUBE_LOGIN
import com.mardous.booming.util.YOUTUBE_TOKEN_LOGIN
import android.content.res.Configuration
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup
import com.mardous.booming.core.model.theme.AccentSchemes
import com.mardous.booming.extensions.collectPlayerAccent
import com.mardous.booming.extensions.resources.applyPreferenceSwitchAccent
import android.widget.TextView
import com.mardous.booming.extensions.resolveColor
import com.mardous.booming.extensions.resources.applyPreferenceToggleAccent
import com.mardous.booming.extensions.resources.applyCategoryTitleAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mardous.booming.util.YT_STREAM_TEST
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class AppearancePreferencesFragment : PreferenceScreenFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_screen_appearance)
    }
}

class NowPlayingPreferencesFragment : PreferenceScreenFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_screen_now_playing)
    }
}

class LyricsPreferencesFragment : PreferenceScreenFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_screen_lyrics)
    }
}

class PlaybackPreferencesFragment : PreferenceScreenFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_screen_playback)
    }
}

class LibraryPreferencesFragment : PreferenceScreenFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_screen_library)
        findPreference<Preference>(ARTIST_SEPARATORS)?.apply {
            summaryProvider = Preference.SummaryProvider<Preference> {
                Preferences.artistSeparators.joinToString(separator = "  ")
            }
        }
        findPreference<Preference>(IGNORED_ARTISTS)?.apply {
            summaryProvider = Preference.SummaryProvider<Preference> {
                val ignored = Preferences.ignoredArtists
                if (ignored.isEmpty()) {
                    context?.getString(R.string.ignored_artists_summary)
                } else {
                    ignored.joinToString(separator = ", ")
                }
            }
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference.key) {
            ARTIST_SEPARATORS ->
                ArtistSeparatorsPreferenceDialog()
                    .show(childFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
            IGNORED_ARTISTS ->
                IgnoredArtistsPreferenceDialog()
                    .show(childFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }
}

class NetworkPreferencesFragment : PreferenceScreenFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_screen_network)
    }
}

class AdvancedPreferencesFragment : PreferenceScreenFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences_screen_advanced)
        findPreference<ListPreference>(LANGUAGE_NAME)?.let { preference ->
            preference.entries = requireContext().languageEntries()
                .map { it.title }
                .toTypedArray()
        }
    }
}

open class PreferenceScreenFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val libraryViewModel: LibraryViewModel by activityViewModel()
    private val lyricsViewModel: LyricsViewModel by activityViewModel()
    private val updateViewModel: UpdateViewModel by activityViewModel()
    private val playerViewModel: PlayerViewModel by activityViewModel()

    private val preferences: SharedPreferences by inject()

    private val importFontLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                lyricsViewModel.importCustomFont(requireContext(), uri)
                    .observe(viewLifecycleOwner) { success ->
                        if (success) {
                            showToast(R.string.font_imported_successfully)
                        } else {
                            showToast(R.string.could_not_import_font)
                        }
                    }
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferences.registerOnSharedPreferenceChangeListener(this)
        findPreference<ListPreference>("stream_quality")?.setOnPreferenceChangeListener { _, newValue ->
            requireContext().getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
                .edit().putString("audio_quality", newValue as? String ?: "High").apply()
            com.mardous.booming.online.data.innertube.YouTubeMusic.clearCache()
            com.mardous.booming.online.data.innertube.NewPipeStreamExtractor.clearCache()
            true
        }
        libraryViewModel.getMiniPlayerMargin().observe(viewLifecycleOwner) {
            listView.updatePadding(bottom = it.getWithSpace())
        }
        setDivider(Color.TRANSPARENT.toDrawable())
        materialSharedAxis(view)
        preparePreferences()
        listView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(child: View) {
                applyAccentToPreferenceRow(child)
            }

            override fun onChildViewDetachedFromWindow(child: View) {}
        })
        collectPlayerAccent { accent ->
            preferenceAccent = accent
            for (index in 0 until listView.childCount) {
                applyAccentToPreferenceRow(listView.getChildAt(index))
            }
        }
    }

    private var preferenceAccent: Int = 0

    private fun applyAccentToPreferenceRow(row: View) {
        val rowGroup = row as? ViewGroup ?: return
        if (!Preferences.followPlayerColor || preferenceAccent == 0) {
            rowGroup.applyPreferenceSwitchAccent(0, 0, 0)
            rowGroup.applyPreferenceToggleAccent(0, 0, 0)
            applyCategoryTitleAccentToRow(rowGroup, 0, 0)
            return
        }
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val scheme = AccentSchemes.viewScheme(preferenceAccent, dark)
        rowGroup.applyPreferenceSwitchAccent(preferenceAccent, scheme.primary, scheme.onPrimary)
        rowGroup.applyPreferenceToggleAccent(preferenceAccent, scheme.secondaryContainer, scheme.onSecondaryContainer)
        applyCategoryTitleAccentToRow(rowGroup, preferenceAccent, scheme.primary)
    }

    private fun applyCategoryTitleAccentToRow(row: ViewGroup, accent: Int, accentPrimary: Int) {
        val themedPrimary = requireContext().resolveColor(androidx.appcompat.R.attr.colorPrimary)
        walkForCategoryTitle(row, accent, themedPrimary, accentPrimary)
    }

    private fun walkForCategoryTitle(group: ViewGroup, accent: Int, themedPrimary: Int, accentPrimary: Int) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextView && child.id == android.R.id.title) {
                child.applyCategoryTitleAccent(accent, themedPrimary, accentPrimary)
            } else if (child is ViewGroup) {
                walkForCategoryTitle(child, accent, themedPrimary, accentPrimary)
            }
        }
    }

    fun preparePreferences() {
        updateBlackThemeAvailability()

        findPreference<Preference>("about")?.summary =
            getString(R.string.about_summary, BuildConfig.VERSION_NAME)

        updateStreamingPreferenceSummaries()

        findPreference<Preference>(YT_STREAM_TEST)?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                playTestYouTubeStream()
                true
            }

        findPreference<ThemePreference>(GENERAL_THEME)?.apply {
            customCallback = object : ThemePreference.Callback {
                override fun onThemeSelected(themeName: String) {
                    // While Pure Black is on, Auto and Light are locked
                    // (unclickable): ignore their taps so the stored
                    // theme can't leave Dark underneath the overlay.
                    if (Preferences.blackTheme && themeName != GeneralTheme.DARK) return
                    // Pure Black only exists under Dark: leaving Dark
                    // clears the stale flag so it can't linger into
                    // Light/Auto through getGeneralTheme().
                    if (themeName != GeneralTheme.DARK && Preferences.blackTheme) {
                        Preferences.blackTheme = false
                    }
                    Preferences.generalTheme = themeName
                    applyDayNightMode(Preferences.getDayNightMode(themeName))
                }
            }
        }

        findPreference<Preference>(BLACK_THEME)?.setOnPreferenceChangeListener { _, newValue ->
            val themeName = Preferences.getGeneralTheme(newValue as Boolean)
            applyDayNightMode(Preferences.getDayNightMode(themeName))
            true
        }

        findPreference<Preference>(FOLLOW_PLAYER_COLOR)?.setOnPreferenceChangeListener { _, _ ->
            requireActivity().recreate()
            true
        }

        findPreference<Preference>(USE_CUSTOM_FONT)?.setOnPreferenceChangeListener { _, _ ->
            requireActivity().recreate()
            true
        }

        findPreference<Preference>(ADD_EXTRA_CONTROLS)?.isVisible = !resources.isTablet

        findPreference<ListPreference>(LyricsViewSettings.Key.BACKGROUND_EFFECT)?.apply {
            if (!hasS()) {
                val indexOfBlur = entryValues.indexOf("blur")
                entries = entries.filterIndexed { index, _ -> index != indexOfBlur }
                    .toTypedArray()
                entryValues = entryValues.filterIndexed { index, _ -> index != indexOfBlur }
                    .toTypedArray()
            }
        }

        findPreference<Preference>(LyricsViewSettings.Key.BLUR_EFFECT)
            ?.isVisible = hasS()

        findPreference<Preference>(LyricsViewSettings.Key.SELECTED_CUSTOM_FONT)
            ?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            importFontLauncher.launch(
                arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf")
            )
            true
        }

        findPreference<Preference>("clear_lyrics")
            ?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            lyricsViewModel.deleteLyrics()
            showToast(R.string.lyrics_cleared)
            true
        }

        if (!hasR()) {
            findPreference<Preference>(TRASH_MUSIC_FILES)?.isVisible = false
        }

        findPreference<SwitchWithButtonPreference>(WHITELIST_ENABLED)?.apply {
            setButtonPressedListener(object : SwitchWithButtonPreference.OnButtonPressedListener {
                override fun onButtonPressed() {
                    showLibraryFolderSelector(InclExclDao.WHITELIST)
                }
            })
        }

        findPreference<SwitchWithButtonPreference>(BLACKLIST_ENABLED)?.apply {
            setButtonPressedListener(object : SwitchWithButtonPreference.OnButtonPressedListener {
                override fun onButtonPressed() {
                    showLibraryFolderSelector(InclExclDao.BLACKLIST)
                }
            })
        }

        findPreference<Preference>(IGNORE_MEDIA_STORE)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                clearImageLoaderCache()
                true
            }

        findPreference<Preference>(USE_FOLDER_ART)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, _ ->
                clearImageLoaderCache()
                true
            }

        findPreference<Preference>(LANGUAGE_NAME)?.setOnPreferenceChangeListener { _, newValue ->
            val languageTag = (newValue as? String)?.takeIf { it != AUTO_LANGUAGE }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
            true
        }

        findPreference<Preference>("backup_and_restore")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), BackupActivity::class.java))
            true
        }

        findPreference<Preference>(ENABLE_ROTATION_LOCK)?.isVisible = !resources.isTablet

        val updateSearchPreference = findPreference<ProgressIndicatorPreference>("search_for_update")
        if (updateSearchPreference != null) {
            updateSearchPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                updateViewModel.searchForUpdate(true)
                true
            }

            updateViewModel.updateEventObservable.observe(viewLifecycleOwner) {
                val result = it.peekContent()
                when (result.state) {
                    UpdateSearchResult.State.Searching -> {
                        updateSearchPreference.showProgressIndicator()
                        updateSearchPreference.isEnabled = false
                        updateSearchPreference.summary = getString(R.string.checking_please_wait)
                    }

                    UpdateSearchResult.State.Completed,
                    UpdateSearchResult.State.Failed -> {
                        updateSearchState(updateSearchPreference, result.executedAtMillis)
                    }

                    else -> {
                        updateSearchState(updateSearchPreference, Preferences.lastUpdateSearch)
                    }
                }
            }
        }

        onUpdateNowPlayingScreen()
        onUpdateCoverActions()
        onUpdateLyricsPreferences()
        onUpdateQueuePreferences()
    }

    private fun updateStreamingPreferenceSummaries() {
        val context = requireContext()

        findPreference<Preference>(SPOTIFY_LOGIN)?.summary =
            if (com.mardous.booming.online.data.spotify.SpotifyPrefs.isConnected(context)) {
                val name = com.mardous.booming.online.data.spotify.SpotifyPrefs.getAccountName(context)
                if (name.isNotBlank()) {
                    "${getString(R.string.connected_account_label)} $name"
                } else {
                    getString(R.string.connected_account_label)
                }
            } else {
                getString(R.string.spotify_login_summary)
            }

        findPreference<Preference>(YOUTUBE_LOGIN)?.summary =
            if (com.mardous.booming.online.data.innertube.AccountManager.isLoggedIn(context)) {
                "${getString(R.string.connected_account_label)} ${com.mardous.booming.online.data.innertube.AccountManager.getAccountName(context)}"
            } else {
                getString(R.string.youtube_login_summary)
            }
    }

    private fun playTestYouTubeStream() {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val searchResult = YouTubeMusic.search("Never Gonna Give You Up", SearchFilter.SONGS)
                        .getOrElse { throw it }
                    val online = searchResult.items.filterIsInstance<SearchItem.Song>().firstOrNull()?.song
                        ?: searchResult.items.filterIsInstance<SearchItem.TopResult>().firstOrNull()?.song
                        ?: error("No results found")
                    buildOnlineQueueItem(online)
                }
            }
            result.fold(
                onSuccess = { item ->
                    playerViewModel.playOnlineQueue(listOf(item))
                    showToast("Playing: ${item.song.title}")
                },
                onFailure = { e ->
                    showToast("YouTube test failed: ${e.message}")
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is ListPreference) {
            val dialogFragment = if (preference.key == LANGUAGE_NAME) {
                LanguageSelectionDialog.newInstance(preference.key)
            } else SingleSelectionDialog.newInstance(preference.key)
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
        } else {
            val dialogFragment: DialogFragment? = when (preference.key) {
                LIBRARY_CATEGORIES -> CategoriesPreferenceDialog()
                NOW_PLAYING_SCREEN -> NowPlayingScreenPreferenceDialog()
                NOW_PLAYING_EXTRA_INFO -> ExtraInfoPreferenceDialog.nowPlaying()
                ON_SONG_CLICK_ACTION -> SongClickActionPreferenceDialog()
                ON_CLEAR_QUEUE_ACTION -> ClearQueueActionPreferenceDialog()
                COVER_DOUBLE_TAP_ACTION,
                COVER_SINGLE_TAP_ACTION,
                COVER_LONG_PRESS_ACTION,
                COVER_LEFT_DOUBLE_TAP_ACTION,
                COVER_RIGHT_DOUBLE_TAP_ACTION -> ActionOnCoverPreferenceDialog.newInstance(preference.key)
                LASTFM_LOGIN -> ScrobblingServiceLoginFragment.create(ScrobblingService.Lastfm)
                LISTENBRAINZ_LOGIN -> ScrobblingServiceLoginFragment.create(ScrobblingService.ListenBrainz)
                SPOTIFY_LOGIN -> StreamingLoginDialogFragment.create(StreamingService.Spotify, StreamingLoginMode.WebView)
                SPOTIFY_TOKEN_LOGIN -> StreamingLoginDialogFragment.create(StreamingService.Spotify, StreamingLoginMode.Token)
                YOUTUBE_LOGIN -> StreamingLoginDialogFragment.create(StreamingService.YouTube, StreamingLoginMode.WebView)
                YOUTUBE_TOKEN_LOGIN -> StreamingLoginDialogFragment.create(StreamingService.YouTube, StreamingLoginMode.Token)
                else -> null
            }
            if (dialogFragment != null) {
                dialogFragment.show(childFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
            } else {
                super.onDisplayPreferenceDialog(preference)
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val settingsScreen = preference.key.toEnum<SettingsScreen>()
        return when {
            settingsScreen != null -> {
                findNavController().navigate(settingsScreen.navAction)
                true
            }
            preference.key == "about" -> {
                findActivityNavController(R.id.fragment_container).navigate(R.id.nav_about)
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?) {
        when (key) {
            GENERAL_THEME,
            BLACK_THEME -> {
                findPreference<ThemePreference>(GENERAL_THEME)?.syncRow()
                updateBlackThemeAvailability()
            }
            NOW_PLAYING_SCREEN -> onUpdateNowPlayingScreen()
            COVER_DOUBLE_TAP_ACTION,
            COVER_LEFT_DOUBLE_TAP_ACTION,
            COVER_RIGHT_DOUBLE_TAP_ACTION,
            COVER_LONG_PRESS_ACTION -> onUpdateCoverActions()
            ON_SONG_CLICK_ACTION,
            ON_CLEAR_QUEUE_ACTION -> onUpdateQueuePreferences()
            LyricsViewSettings.Key.BACKGROUND_EFFECT -> onUpdateLyricsPreferences()
        }
    }

    private fun updateBlackThemeAvailability() {
        // Pure Black is only meaningful under Dark: unclickable in
        // Auto/Light so toggling it there can't corrupt the stored theme.
        findPreference<Preference>(BLACK_THEME)?.isEnabled =
            Preferences.storedGeneralTheme == GeneralTheme.DARK
    }

    private fun onUpdateNowPlayingScreen() {
        findPreference<Preference>(NOW_PLAYING_SCREEN)?.summary =
            getString(Preferences.nowPlayingScreen.titleRes)
    }

    private fun onUpdateCoverActions() {
        findPreference<Preference>(COVER_SINGLE_TAP_ACTION)?.summary =
            getString(Preferences.coverSingleTapAction.titleRes)

        findPreference<Preference>(COVER_DOUBLE_TAP_ACTION)?.summary =
            getString(Preferences.coverDoubleTapAction.titleRes)

        findPreference<Preference>(COVER_LEFT_DOUBLE_TAP_ACTION)?.summary =
            getString(Preferences.coverLeftDoubleTapAction.titleRes)

        findPreference<Preference>(COVER_RIGHT_DOUBLE_TAP_ACTION)?.summary =
            getString(Preferences.coverRightDoubleTapAction.titleRes)

        findPreference<Preference>(COVER_LONG_PRESS_ACTION)?.summary =
            getString(Preferences.coverLongPressAction.titleRes)
    }

    private fun onUpdateLyricsPreferences() {
        val hasBackgroundEffects =
            preferences.getString(LyricsViewSettings.Key.BACKGROUND_EFFECT, "none") != "none"
        findPreference<Preference>(LyricsViewSettings.Key.SHADOW_EFFECT)
            ?.isEnabled = hasBackgroundEffects
        findPreference<Preference>(LyricsViewSettings.Key.BLUR_EFFECT)
            ?.isEnabled = hasBackgroundEffects
    }

    private fun onUpdateQueuePreferences() {
        findPreference<Preference>(ON_SONG_CLICK_ACTION)
            ?.summary = getString(Preferences.songClickAction.titleRes)
        findPreference<Preference>(ON_CLEAR_QUEUE_ACTION)
            ?.summary = getString(Preferences.clearQueueAction.titleRes)
    }

    private fun showLibraryFolderSelector(type: Int) {
        BlacklistWhitelistDialog.newInstance(type).show(childFragmentManager, "LIBRARY_PATHS_PREFERENCE")
    }

    private fun clearImageLoaderCache() = lifecycleScope.launch(Dispatchers.IO) {
        try {
            val imageLoader = SingletonImageLoader.get(requireContext())
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        } catch (e: Exception) {
            Log.e("Settings", "Failed to clear image loader cache", e)
        }
    }

    private fun updateSearchState(preference: ProgressIndicatorPreference?, lastUpdateSearch: Long) {
        requestContext {
            preference?.hideProgressIndicator()
            preference?.isEnabled = true
            preference?.summary = getString(R.string.last_update_search_x, it.dateStr(lastUpdateSearch))
        }
    }

    private fun restartActivity() {
        activity?.recreate()
    }

    /**
     * Applies a day/night mode immediately: setDefaultNightMode recreates
     * the activity itself whenever the night mode actually changed, so a
     * manual recreate is only needed when it didn't.
     */
    private fun applyDayNightMode(mode: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == mode) {
            restartActivity()
        } else {
            setDefaultNightMode(mode)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
    }
}