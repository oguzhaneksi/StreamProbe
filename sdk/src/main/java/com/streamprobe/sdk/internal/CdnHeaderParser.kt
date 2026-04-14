package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import java.util.Locale

/**
 * Parses CDN-related HTTP response headers from a Media3 header map
 * ([Map<String, List<String>>]) into a [CdnHeaderInfo].
 *
 * Header key lookups are performed case-insensitively. When the map is
 * empty (local cache, certain Cronet configurations, etc.) all fields are
 * null and [CacheStatus.UNKNOWN] is returned.
 */
internal object CdnHeaderParser {

    private val CDN_SPECIFIC_HEADERS = setOf(
        "cf-cache-status",
        "x-amz-cf-pop",
        "x-amz-cf-id",
        "x-served-by",
        "x-cache-hits",
        "x-akamai-request-id",
    )

    fun parse(headers: Map<String, List<String>>): CdnHeaderInfo {
        // Build a lowercase-keyed map for case-insensitive lookup.
        val lower = headers.entries.associate { (k, v) -> k.lowercase(Locale.ROOT) to v }

        val cacheControl = lower["cache-control"]?.firstOrNull()
        val xCache = (lower["x-cache"] ?: lower["x-cache-status"])?.firstOrNull()
        val via = lower["via"]?.firstOrNull()

        val cdnSpecificHeaders = CDN_SPECIFIC_HEADERS
            .mapNotNull { key -> lower[key]?.firstOrNull()?.let { key to it } }
            .toMap()

        val cacheStatus = determineCacheStatus(xCache, cdnSpecificHeaders)

        return CdnHeaderInfo(
            cacheControl = cacheControl,
            xCache = xCache,
            via = via,
            cdnSpecificHeaders = cdnSpecificHeaders,
            cacheStatus = cacheStatus,
        )
    }

    fun determineCacheStatus(
        xCache: String?,
        cdnHeaders: Map<String, String>,
    ): CacheStatus {
        // Check x-cache / x-cache-status first.
        xCache?.uppercase(Locale.ROOT)?.let { v ->
            return when {
                v.startsWith("HIT") -> CacheStatus.HIT
                v.startsWith("MISS") -> CacheStatus.MISS
                else -> CacheStatus.UNKNOWN
            }
        }

        // Fall back to CDN-specific cache headers (e.g. CF-Cache-Status).
        val cfCacheStatus = cdnHeaders["cf-cache-status"]?.uppercase(Locale.ROOT)
        if (cfCacheStatus != null) {
            return when {
                cfCacheStatus.startsWith("HIT") -> CacheStatus.HIT
                cfCacheStatus.startsWith("MISS") || cfCacheStatus == "EXPIRED" || cfCacheStatus == "STALE" -> CacheStatus.MISS
                else -> CacheStatus.UNKNOWN
            }
        }

        return CacheStatus.UNKNOWN
    }
}
