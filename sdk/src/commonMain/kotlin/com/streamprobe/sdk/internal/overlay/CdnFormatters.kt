package com.streamprobe.sdk.internal.overlay

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnHeaderInfo
import com.streamprobe.sdk.model.CdnProvider

/**
 * Formatting functions for CDN and cache-status data displayed in the overlay.
 * Split out of the former monolithic `OverlayFormatters` to group the CDN-specific
 * logic here and keep each formatter object under the detekt `TooManyFunctions` threshold.
 */
internal object CdnFormatters {
    fun formatCdnStatus(cdnInfo: CdnHeaderInfo?): String {
        if (cdnInfo == null) return "—"
        val providerPrefix =
            when (cdnInfo.cdnProvider) {
                CdnProvider.CLOUDFLARE -> "[CLOUDFLARE]"
                CdnProvider.CLOUDFRONT -> "[CLOUDFRONT]"
                CdnProvider.FASTLY -> "[FASTLY]"
                CdnProvider.AKAMAI -> "[AKAMAI]"
                CdnProvider.UNKNOWN, null -> null
            }
        val indicator = formatCacheIndicator(cdnInfo.cacheStatus)
        val headerSnippet =
            when {
                cdnInfo.xCache != null -> "X-Cache: ${cdnInfo.xCache}"
                cdnInfo.cdnSpecificHeaders.isNotEmpty() -> {
                    val (k, v) = cdnInfo.cdnSpecificHeaders.entries.first()
                    "${k.uppercase()}: $v"
                }
                cdnInfo.cacheControl != null -> "Cache-Control: ${cdnInfo.cacheControl}"
                else -> null
            }
        val statusPart = if (headerSnippet != null) "$indicator  ·  $headerSnippet" else indicator
        return if (providerPrefix != null) "$providerPrefix  $statusPart" else statusPart
    }

    private fun formatCacheIndicator(status: CacheStatus): String =
        when (status) {
            CacheStatus.HIT -> "● HIT"
            CacheStatus.MISS -> "○ MISS"
            CacheStatus.STALE -> "◔ STALE"
            CacheStatus.BYPASS -> "□ BYPASS"
            CacheStatus.UNKNOWN -> "◌ UNKNOWN"
        }
}
