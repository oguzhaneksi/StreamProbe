package com.streamprobe.android.player

/**
 * Player misconfiguration mode used by the fault-deck QA harness
 * (tools/fault-deck/). It deliberately mis-tunes the ExoPlayer track selector so
 * a tester can practice diagnosing "video stuck at low quality" causes from the
 * StreamProbe overlay alone.
 *
 * This is a debug-only test affordance. It is only ever selected via the
 * `sp_fault_mode` intent extra, which [com.streamprobe.android.MainActivity]
 * reads only when `BuildConfig.DEBUG` is true. Release builds always run as
 * [NORMAL].
 */
enum class FaultMode {
    /** Healthy control: default track selector, no constraints. */
    NORMAL,

    /** Caps the player to 480p via `setMaxVideoSize(854, 480)`. */
    CONSTRAINED,

    /** Misconfigured ABR: a tiny bandwidth fraction so the player stays overly cautious. */
    BW_MISCONFIG,

    ;

    companion object {
        /** Parses the `sp_fault_mode` intent extra. Unknown or null falls back to [NORMAL]. */
        fun fromKey(key: String?): FaultMode =
            when (key?.lowercase()) {
                "constrained" -> CONSTRAINED
                "bw_misconfig" -> BW_MISCONFIG
                else -> NORMAL
            }
    }
}
