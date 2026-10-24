package com.mardous.booming.ui.screen.player.styles.spatialstyle

import android.os.Bundle
import android.view.View
import com.mardous.booming.R
import com.mardous.booming.core.model.player.*
import com.mardous.booming.core.model.theme.NowPlayingScreen
import com.mardous.booming.databinding.FragmentSpatialPlayerBinding
import com.mardous.booming.extensions.whichFragment
import com.mardous.booming.ui.component.base.AbsPlayerControlsFragment
import com.mardous.booming.ui.component.base.AbsPlayerFragment
import com.mardous.booming.util.Preferences

class SpatialPlayerFragment : AbsPlayerFragment(R.layout.fragment_spatial_player) {

    private var _binding: FragmentSpatialPlayerBinding? = null
    private val binding get() = _binding!!

    private var controlsFragment: SpatialControlsFragment? = null

    override val colorSchemeMode: PlayerColorSchemeMode
        get() = Preferences.getNowPlayingColorSchemeMode(NowPlayingScreen.Spatial)

    override val playerControlsFragment: AbsPlayerControlsFragment
        get() = controlsFragment ?: throw IllegalStateException("Spatial controls fragment not attached yet")

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSpatialPlayerBinding.bind(view)
    }

    override fun onCreateChildFragments() {
        super.onCreateChildFragments()
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
    }

    override fun getTintTargets(scheme: PlayerColorScheme): List<PlayerTintTarget> {
        return mutableListOf(
            binding.colorBackground.tintTarget(
                oldColor = R.color.spatial_background_default,
                newColor = scheme.surfaceColor
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
