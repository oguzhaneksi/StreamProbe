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
    /** Identified CDN provider. Null when constructed manually without detection. */
    val cdnProvider: CdnProvider?,
)

enum class CacheStatus {
    /** Content served directly from CDN cache. */
    HIT,
    /** CDN fetched content from origin. */
    MISS,
    /** CDN served stale (expired) content without revalidating. */
    STALE,
    /** Cache was intentionally bypassed (e.g. Nginx BYPASS). */
    BYPASS,
    /** Cache status could not be determined from available headers. */
    UNKNOWN,
}

enum class CdnProvider {
    CLOUDFLARE,
    CLOUDFRONT,
    FASTLY,
    AKAMAI,
    UNKNOWN,
}
