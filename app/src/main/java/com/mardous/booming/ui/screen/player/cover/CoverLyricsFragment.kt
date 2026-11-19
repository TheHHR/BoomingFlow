package com.mardous.booming.ui.screen.player.cover

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnAttach
import com.mardous.booming.ui.component.compose.lyrics.LocalLyricsLineBounds
import com.mardous.booming.ui.component.compose.lyrics.LyricsLineBounds
import com.mardous.booming.ui.component.views.QueueGestureLayout
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.mardous.booming.R
import com.mardous.booming.extensions.navigation.findActivityNavController
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.ui.screen.lyrics.CoverLyricsScreen
import com.mardous.booming.ui.screen.lyrics.LyricsEditorFragmentArgs
import com.mardous.booming.ui.screen.lyrics.LyricsViewModel
import com.mardous.booming.ui.screen.player.PlayerViewModel
import com.mardous.booming.ui.theme.BoomingMusicTheme
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class CoverLyricsFragment : Fragment() {

    private val lyricsViewModel: LyricsViewModel by activityViewModel()
    private val playerViewModel: PlayerViewModel by activityViewModel()

    /**
     * True when this instance is hosted in the player layout's
     * expandedLyricsFragment container (parent = the player fragment
     * itself), false when it overlays the album cover (parent =
     * CoverPagerFragment).
     */
    private val isExpandedHost: Boolean
        get() = parentFragment is AbsPlayerFragment

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Cover lyrics only: swipes starting on the lyric lines scroll them,
        // anywhere else the player root may open the queue. Fullscreen lyrics
        // never open the queue, so no bounds are tracked there.
        val lineBounds = if (isExpandedHost) null else LyricsLineBounds()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            if (lineBounds != null) {
                doOnAttach { view ->
                    findQueueGestureLayout(view)?.swipeExclusion = { x, y ->
                        view.isShown && lineBounds.contains(x, y)
                    }
                }
            }
            setContent {
                BoomingMusicTheme {
                    CompositionLocalProvider(LocalLyricsLineBounds provides lineBounds) {
                    CoverLyricsScreen(
                        lyricsViewModel = lyricsViewModel,
                        playerViewModel = playerViewModel,
                        isExpanded = isExpandedHost,
                        onExpandClick = {
                            (parentFragment?.parentFragment as? AbsPlayerFragment)?.expandLyrics()
                        },
                        onCollapseClick = {
                            (parentFragment as? AbsPlayerFragment)?.collapseLyrics()
                        },
                        onEditClick = {
                            (parentFragment as? AbsPlayerFragment
                                ?: parentFragment?.parentFragment as? AbsPlayerFragment)
                                ?.collapseLyrics()
                            openLyricsEditor()
                        }
                    )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        if (!isExpandedHost) {
            view?.let { findQueueGestureLayout(it)?.swipeExclusion = null }
        }
        super.onDestroyView()
    }

    private fun findQueueGestureLayout(view: View): QueueGestureLayout? =
        generateSequence(view.parent) { it.parent }.filterIsInstance<QueueGestureLayout>().firstOrNull()

    private fun openLyricsEditor() {
        findActivityNavController(R.id.fragment_container).navigate(
            R.id.nav_lyrics_editor,
            LyricsEditorFragmentArgs.Builder(playerViewModel.currentSong)
                .build()
                .toBundle()
        )
    }
}
