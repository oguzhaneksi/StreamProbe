package com.streamprobe.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MimeTypes
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.streamprobe.android.player.FaultMode
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

                // Fault-deck QA entry point: if launched (debug only) with an
                // sp_fault_url extra, jump straight into the player with the
                // requested stream + misconfiguration mode. See tools/fault-deck/.
                // The stream is set synchronously during first composition so it
                // is ready before PlayerScreen's lifecycle effect initializes the player.
                val faultLaunch =
                    remember {
                        parseFaultLaunch(intent)?.also {
                            playerVm.selectStream(it.stream, it.mode, it.showOverlay, it.enableEventLogger)
                        }
                    }

                NavHost(navController = nav, startDestination = if (faultLaunch != null) Player else StreamSelect) {
                    composable<StreamSelect> {
                        StreamSelectionScreen(
                            onStreamSelected = {
                                playerVm.selectStream(it)
                                nav.navigate(Player)
                            },
                            onSettingsClick = { nav.navigate(Settings) },
                        )
                    }
                    composable<Settings> {
                        val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
                        val checkedState = vm.injectErrors.collectAsStateWithLifecycle()
                        SettingsScreen(
                            injectErrors = checkedState.value,
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

/** A fault-deck launch request parsed from intent extras. */
private data class FaultLaunch(
    val stream: Stream,
    val mode: FaultMode,
    val showOverlay: Boolean,
    val enableEventLogger: Boolean,
)

/**
 * Parses the fault-deck intent extras (`sp_fault_url`, `sp_fault_mode`,
 * `sp_fault_overlay`, `sp_fault_eventlogger`, optional `sp_fault_title`). Returns
 * null unless this is a debug build AND a URL was provided, so release builds and
 * normal launches are unaffected.
 *
 * `sp_fault_overlay` is the benchmark arm: "on" (default) shows the StreamProbe
 * overlay; "off" is the control arm where the overlay is hidden so observability
 * with vs without the overlay can be timed.
 *
 * `sp_fault_eventlogger` ("on" to enable, default off) attaches Media3's
 * `EventLogger` to the player for the case-study capture runs — it logs the
 * runtime ladder, bandwidth estimate, and per-segment timing to logcat. It is a
 * capture-only debug affordance read here only in `BuildConfig.DEBUG`.
 */
private fun parseFaultLaunch(intent: Intent?): FaultLaunch? {
    val url =
        intent
            ?.takeIf { BuildConfig.DEBUG }
            ?.getStringExtra("sp_fault_url")
            ?.takeIf { it.isNotBlank() }
            ?: return null
    val title = intent.getStringExtra("sp_fault_title") ?: "Fault Deck"
    val stream =
        Stream(
            title = title,
            url = url,
            type = StreamType.HLS,
            mimeType = MimeTypes.APPLICATION_M3U8,
        )
    val showOverlay = intent.getStringExtra("sp_fault_overlay")?.lowercase() != "off"
    val enableEventLogger = intent.getStringExtra("sp_fault_eventlogger")?.lowercase() == "on"
    return FaultLaunch(stream, FaultMode.fromKey(intent.getStringExtra("sp_fault_mode")), showOverlay, enableEventLogger)
}
