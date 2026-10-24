package com.mardous.booming.ui.screen.player.styles.spatialstyle

import android.os.Bundle
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mardous.booming.R
import com.mardous.booming.core.model.player.PlayerColorScheme
import com.mardous.booming.data.model.Song
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.screen.player.PlayerViewModel
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SpatialControlsFragment : AbsPlayerControlsFragment(R.layout.fragment_spatial_player_playback_controls) {

    private val playerShared: PlayerViewModel by activityViewModel()

    private lateinit var composeView: ComposeView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        composeView = view.findViewById(R.id.compose_view)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        ViewCompat.setOnApplyWindowInsetsListener(view) { v: View, insets: WindowInsetsCompat ->
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = displayCutout.left, right = displayCutout.right)
            insets
        }
        composeView.setContent {
            val scheme by playerViewModel.colorSchemeFlow.collectAsStateWithLifecycle(
                initialValue = PlayerColorScheme.Unspecified
            )
            com.mardous.booming.ui.theme.PlayerTheme(scheme) {
                SpatialControlsScreen(
                    playerViewModel = playerShared,
                    scheme = scheme
                )
            }
        }
    }

    override fun onSongInfoChanged(currentSong: Song, nextSong: Song) {}
    override fun onExtraInfoChanged(extraInfo: String?) {}
    override fun onUpdatePlayPause(isPlaying: Boolean) {}
    override fun getTintTargets(scheme: PlayerColorScheme): List<com.mardous.booming.core.model.player.PlayerTintTarget> =
        emptyList()
}
