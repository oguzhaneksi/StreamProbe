package com.streamprobe.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.streamprobe.android.ui.PlayerScreen
import com.streamprobe.android.ui.SettingsScreen
import com.streamprobe.android.ui.StreamSelectionScreen
import com.streamprobe.android.ui.theme.StreamProbeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StreamProbeTheme {
                val app = application as StreamProbeApplication
                val playerVm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(app))
                val nav = rememberNavController()

                NavHost(navController = nav, startDestination = StreamSelect) {
                    composable<StreamSelect> {
                        StreamSelectionScreen(
                            onStreamSelected = { playerVm.selectStream(it); nav.navigate(Player) },
                            onSettingsClick = { nav.navigate(Settings) },
                        )
                    }
                    composable<Settings> {
                        val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
                        val checked by vm.injectErrors.collectAsStateWithLifecycle()
                        SettingsScreen(
                            injectErrors = checked,
                            onToggle = vm::setInjectErrors,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable<Player> {
                        BackHandler {
                            playerVm.clearStream()
                            nav.popBackStack()
                        }
                        PlayerScreen(viewModel = playerVm)
                    }
                }
            }
        }
    }
}
