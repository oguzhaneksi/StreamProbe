package com.streamprobe.sdk.model

/**
 * Per-segment download metric capturing timing, size, throughput, and CDN header information.
 */
data class SegmentMetric(
    /** The start time of the segment request (System.currentTimeMillis). */
    val requestTimestampMs: Long,
    /** Total download duration (ms). */
    val totalDurationMs: Long,
    /** Downloaded segment size (bytes). */
    val sizeBytes: Long,
    /** Calculated throughput (bytes/sec). */
    val throughputBytesPerSec: Long,
    /** Segment URI. */
    val uri: String,
    /** CDN response header information captured from the same response. */
    val cdnInfo: CdnHeaderInfo,
)
