package com.mardous.booming.ui.dialogs.artists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import com.mardous.booming.R
import com.mardous.booming.ui.component.compose.DialogListItemWithRadio
import com.mardous.booming.ui.theme.BoomingMusicTheme

/**
 * One-tap Go To menu: artist rows first (alias-resolved split names),
 * then Album and Genre rows, then Close.
 *
 * A Compose dialog wrapped in [BoomingMusicTheme] so rows and buttons
 * follow follow-player-color like the rest of the app. Row taps
 * navigate directly (dialog dismisses first) instead of going through
 * the system list callback that occasionally dropped taps.
 */
class GoToMenuDialog(
    private val artistNames: List<String> = emptyList(),
    private val showAlbumRow: Boolean = true,
    private val showGenreRow: Boolean = true,
    private val onArtistSelected: (String) -> Unit = {},
    private val onAlbumSelected: () -> Unit = {},
    private val onGenreSelected: () -> Unit = {}
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            BoomingMusicTheme {
                GoToMenuContent(
                    artistNames = artistNames,
                    showAlbumRow = showAlbumRow,
                    showGenreRow = showGenreRow,
                    onArtistSelected = {
                        dismissAllowingStateLoss()
                        onArtistSelected(it)
                    },
                    onAlbumSelected = {
                        dismissAllowingStateLoss()
                        onAlbumSelected()
                    },
                    onGenreSelected = {
                        dismissAllowingStateLoss()
                        onGenreSelected()
                    },
                    onClose = { dismissAllowingStateLoss() }
                )
            }
        }
    }

    companion object {
        operator fun invoke(
            artistNames: List<String>,
            showAlbumRow: Boolean = true,
            showGenreRow: Boolean = true,
            onArtistSelected: (String) -> Unit = {},
            onAlbumSelected: () -> Unit = {},
            onGenreSelected: () -> Unit = {}
        ) = GoToMenuDialog(
            artistNames = artistNames,
            showAlbumRow = showAlbumRow,
            showGenreRow = showGenreRow,
            onArtistSelected = onArtistSelected,
            onAlbumSelected = onAlbumSelected,
            onGenreSelected = onGenreSelected
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoToMenuContent(
    artistNames: List<String>,
    showAlbumRow: Boolean,
    showGenreRow: Boolean,
    onArtistSelected: (String) -> Unit,
    onAlbumSelected: () -> Unit,
    onGenreSelected: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.go_to_label)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(artistNames, key = { it }) { name ->
                        DialogListItemWithRadio(
                            title = name,
                            isSelected = false,
                            onClick = { onArtistSelected(name) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
                if (showAlbumRow || showGenreRow) {
                    HorizontalDivider()
                    if (showAlbumRow) {
                        DialogListItemWithRadio(
                            title = stringResource(R.string.album),
                            isSelected = false,
                            onClick = onAlbumSelected,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                    if (showGenreRow) {
                        DialogListItemWithRadio(
                            title = stringResource(R.string.genre),
                            isSelected = false,
                            onClick = onGenreSelected,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.close_action))
            }
        }
    )
}
