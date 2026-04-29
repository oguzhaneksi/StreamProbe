package com.streamprobe.sdk.model

/**
 * A single immutable record for every non-fatal error captured during playback.
 * Category-specific data is consolidated into a sealed [ErrorDetail] so unused fields
 * don't leak across categories.
 */
// TODO a stable UUID to be added for preventing possible issues caused by duplicated timestampMs
data class PlaybackErrorEvent(
    /** Wall-clock timestamp of the *first* burst (System.currentTimeMillis). Stable for DiffUtil. */
    val timestampMs: Long,
    /** Broad error category for color-coding and filtering. */
    val category: ErrorCategory,
    /** Human-readable one-line summary, e.g. "HTTP 404: .../seg_42.ts". */
    val message: String,
    /** Optional: full exception text / additional context shown when row is expanded. */
    val detail: String? = null,
    /** Category-specific structured data; null for categories that don't carry extra fields. */
    val categoryDetail: ErrorDetail? = null,
)

sealed interface ErrorDetail {
    /** Aggregated dropped-frames burst data. Only attached to DROPPED_FRAMES events. */
    data class DroppedFrames(
        val totalFrames: Int,
        val burstCount: Int,
        /** Wall-clock time of the most recent burst that was merged into this entry. */
        val lastUpdateMs: Long,
    ) : ErrorDetail
}
