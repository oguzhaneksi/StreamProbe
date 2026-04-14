package com.streamprobe.sdk.model

/**
 * CDN response header data captured from a segment download response.
 * Embedded in [SegmentMetric]; uri and requestTimestampMs live on the parent.
 */
data class CdnHeaderInfo(
    /** Cache-Control header value. */
    val cacheControl: String?,
    /** X-Cache or X-Cache-Status value. */
    val xCache: String?,
    /** Via header value. */
    val via: String?,
    /** CDN-specific headers (CF-Cache-Status, X-Amz-Cf-Pop, X-Served-By, etc). */
    val cdnSpecificHeaders: Map<String, String>,
    /** Cache status calculated by the SDK. */
    val cacheStatus: CacheStatus,
)

enum class CacheStatus {
    HIT, MISS, UNKNOWN
}
