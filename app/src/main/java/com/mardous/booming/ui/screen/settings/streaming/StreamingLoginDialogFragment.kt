package com.mardous.booming.ui.screen.settings.streaming

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import com.mardous.booming.extensions.extraNotNull
import com.mardous.booming.extensions.withArgs
import com.mardous.booming.online.ui.SpotifyManualTokenBottomSheet
import com.mardous.booming.online.ui.SpotifySignInScreen
import com.mardous.booming.online.ui.YouTubeManualCookieBottomSheet
import com.mardous.booming.online.ui.YoutubeSignInScreen
import com.mardous.booming.online.viewmodel.StreamingAuthViewModel
import com.mardous.booming.ui.theme.BoomingMusicTheme
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.Serializable

enum class StreamingService : Serializable { Spotify, YouTube }

enum class StreamingLoginMode : Serializable { WebView, Token }

class StreamingLoginDialogFragment : DialogFragment() {

    private val viewModel: StreamingAuthViewModel by activityViewModel()
    private val service: StreamingService by extraNotNull("service")
    private val mode: StreamingLoginMode by extraNotNull("mode")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                BoomingMusicTheme {
                    StreamingLoginContent(
                        service = service,
                        mode = mode,
                        viewModel = viewModel,
                        onDone = { dismiss() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        (parentFragment as? com.mardous.booming.ui.screen.settings.PreferenceScreenFragment)
            ?.preparePreferences()
    }

    companion object {
        fun create(service: StreamingService, mode: StreamingLoginMode) =
            StreamingLoginDialogFragment().withArgs {
                putSerializable("service", service)
                putSerializable("mode", mode)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingLoginContent(
    service: StreamingService,
    mode: StreamingLoginMode,
    viewModel: StreamingAuthViewModel,
    onDone: () -> Unit,
) {
    when (mode) {
        StreamingLoginMode.Token -> {
            Box(modifier = Modifier.fillMaxSize()) {
                when (service) {
                    StreamingService.Spotify -> SpotifyManualTokenBottomSheet(
                        viewModel = viewModel,
                        onDismissRequest = { onDone() },
                        onLoginSuccess = { onDone() }
                    )

                    StreamingService.YouTube -> YouTubeManualCookieBottomSheet(
                        viewModel = viewModel,
                        onDismissRequest = { onDone() },
                        onLoginSuccess = { onDone() }
                    )
                }
            }
        }

        StreamingLoginMode.WebView -> {
            when (service) {
                StreamingService.Spotify -> SpotifySignInScreen(
                    viewModel = viewModel,
                    onSignInSuccess = { onDone() },
                    onNavigateUp = { onDone() }
                )

                StreamingService.YouTube -> YoutubeSignInScreen(
                    viewModel = viewModel,
                    onSignInSuccess = { onDone() },
                    onNavigateUp = { onDone() }
                )
            }
        }
    }
}
