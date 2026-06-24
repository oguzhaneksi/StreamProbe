package com.streamprobe.sdk.model

/**
 * Network timing breakdown for a single segment request.
 *
 * [ttfbMs] is the time from request start to first byte received (response headers complete).
 * For the baseline open()-proxy estimator, this includes connection setup time on cold
 * connections; on warm keep-alive connections it closely approximates pure server TTFB.
 *
 * [isEstimated] is true for the baseline DataSource.Factory wrapper (open()-duration proxy)
 * and false for future per-phase adapters that measure DNS/connect/TLS separately.
 */
data class NetworkTiming(
    val ttfbMs: Long,
    val transferDurationMs: Long?,
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val isEstimated: Boolean,
)
