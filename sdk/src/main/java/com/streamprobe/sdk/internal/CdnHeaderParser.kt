package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.CdnProvider
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
        "cf-ray",
        "x-amz-cf-pop",
        "x-amz-cf-id",
        "x-served-by",
        "x-cache-hits",
        "x-akamai-request-id",
        "x-cdn",
        "akamai-grn",
    )

    fun parse(headers: Map<String, List<String>>): CdnHeaderInfo {
        // Build a lowercase-keyed map for case-insensitive lookup.
        val lower = headers.entries.associate { (k, v) -> k.lowercase(Locale.ROOT) to v }

        val cacheControl = lower["cache-control"]?.firstOrNull()
        val xCache = (lower["x-cache"] ?: lower["x-cached"] ?: lower["x-cache-status"])?.firstOrNull()
        val viaValues = lower["via"] ?: emptyList()
        val via = viaValues.firstOrNull()

        val cdnSpecificHeaders = CDN_SPECIFIC_HEADERS
            .mapNotNull { key -> lower[key]?.firstOrNull()?.let { key to it } }
            .toMap()

        val cacheStatus = determineCacheStatus(xCache, cdnSpecificHeaders)
        val cdnProvider = determineCdnProvider(cdnSpecificHeaders, viaValues)

        return CdnHeaderInfo(
            cacheControl = cacheControl,
            xCache = xCache,
            via = via,
            cdnSpecificHeaders = cdnSpecificHeaders,
            cacheStatus = cacheStatus,
            cdnProvider = cdnProvider,
        )
    }

    fun determineCacheStatus(
        xCache: String?,
        cdnHeaders: Map<String, String>,
    ): CacheStatus {
        // Check x-cache / x-cache-status first.
        // STALE/REVALIDATED/BYPASS are checked before contains("HIT"/"MISS") so that
        // Akamai tokens like TCP_HIT, TCP_MEM_HIT, TCP_REFRESH_HIT → HIT and
        // TCP_MISS, TCP_REFRESH_MISS → MISS are correctly classified.
        xCache?.uppercase(Locale.ROOT)?.let { v ->
            return when {
                v == "STALE" || v == "REVALIDATED" -> CacheStatus.STALE
                v == "BYPASS" -> CacheStatus.BYPASS
                v.contains("HIT") -> CacheStatus.HIT
                v.contains("MISS") -> CacheStatus.MISS
                else -> CacheStatus.UNKNOWN
            }
        }

        // Fall back to CDN-specific cache headers (e.g. CF-Cache-Status).
        val cfCacheStatus = cdnHeaders["cf-cache-status"]?.uppercase(Locale.ROOT)
        if (cfCacheStatus != null) {
            return when {
                cfCacheStatus.startsWith("HIT") -> CacheStatus.HIT
                cfCacheStatus.startsWith("MISS") || cfCacheStatus == "EXPIRED" -> CacheStatus.MISS
                cfCacheStatus == "STALE" || cfCacheStatus == "REVALIDATED" || cfCacheStatus == "UPDATING" -> CacheStatus.STALE
                cfCacheStatus == "BYPASS" || cfCacheStatus == "DYNAMIC" -> CacheStatus.BYPASS
                else -> CacheStatus.UNKNOWN
            }
        }

        // x-cache-hits: "0" means not served from cache (MISS), >0 means HIT.
        // Use toLongOrNull to avoid Int overflow on very large hit counts.
        val cacheHits = cdnHeaders["x-cache-hits"]
        if (cacheHits != null) {
            return if ((cacheHits.toLongOrNull() ?: 0L) > 0L) CacheStatus.HIT else CacheStatus.MISS
        }

        return CacheStatus.UNKNOWN
    }

    private fun determineCdnProvider(
        cdnHeaders: Map<String, String>,
        viaValues: List<String>,
    ): CdnProvider {
        // Cloudflare: CF-Cache-Status or CF-Ray
        if ("cf-cache-status" in cdnHeaders || "cf-ray" in cdnHeaders) {
            return CdnProvider.CLOUDFLARE
        }
        // CloudFront: X-Amz-Cf-Id or X-Amz-Cf-Pop
        if ("x-amz-cf-id" in cdnHeaders || "x-amz-cf-pop" in cdnHeaders) {
            return CdnProvider.CLOUDFRONT
        }
        // Fastly: X-Served-By + at least one Via value containing "varnish"
        if ("x-served-by" in cdnHeaders &&
            viaValues.any { it.contains("varnish", ignoreCase = true) }
        ) {
            return CdnProvider.FASTLY
        }
        // Akamai: X-Akamai-Request-Id, Akamai-GRN, or X-CDN: Akamai
        if ("x-akamai-request-id" in cdnHeaders || "akamai-grn" in cdnHeaders ||
            cdnHeaders["x-cdn"]?.equals("akamai", ignoreCase = true) == true
        ) {
            return CdnProvider.AKAMAI
        }
        // Fallback: scan all via values (each entry may itself be comma-separated).
        // Do not infer Fastly from generic "Varnish" tokens alone.
        val viaTokens = viaValues
            .flatMap { it.split(",") }
            .map { it.trim().uppercase(Locale.ROOT) }
        return when {
            viaTokens.any { it.contains("CLOUDFRONT") } -> CdnProvider.CLOUDFRONT
            else -> CdnProvider.UNKNOWN
        }
    }
}
