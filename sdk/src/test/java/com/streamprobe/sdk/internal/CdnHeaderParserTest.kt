package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.CacheStatus
import com.streamprobe.sdk.model.CdnProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CdnHeaderParserTest {

    private fun headers(vararg pairs: Pair<String, String>): Map<String, List<String>> =
        pairs.associate { (k, v) -> k to listOf(v) }

    @Test
    fun `parse extracts Cache-Control header`() {
        val result = CdnHeaderParser.parse(headers("Cache-Control" to "max-age=3600"))
        assertEquals("max-age=3600", result.cacheControl)
    }

    @Test
    fun `parse extracts X-Cache header`() {
        val result = CdnHeaderParser.parse(headers("X-Cache" to "HIT"))
        assertEquals("HIT", result.xCache)
    }

    @Test
    fun `parse extracts X-Cache-Status header into xCache`() {
        val result = CdnHeaderParser.parse(headers("X-Cache-Status" to "HIT"))
        assertEquals("HIT", result.xCache)
    }

    @Test
    fun `parse extracts Via header`() {
        val result = CdnHeaderParser.parse(headers("Via" to "1.1 varnish"))
        assertEquals("1.1 varnish", result.via)
    }

    @Test
    fun `parse captures Cloudflare CF-Cache-Status`() {
        val result = CdnHeaderParser.parse(headers("CF-Cache-Status" to "HIT"))
        assertEquals("HIT", result.cdnSpecificHeaders["cf-cache-status"])
    }

    @Test
    fun `parse captures CloudFront X-Amz-Cf-Pop`() {
        val result = CdnHeaderParser.parse(headers("X-Amz-Cf-Pop" to "IAD55-C1"))
        assertEquals("IAD55-C1", result.cdnSpecificHeaders["x-amz-cf-pop"])
    }

    @Test
    fun `parse captures Fastly X-Served-By`() {
        val result = CdnHeaderParser.parse(headers("X-Served-By" to "cache-iad-1"))
        assertEquals("cache-iad-1", result.cdnSpecificHeaders["x-served-by"])
    }

    @Test
    fun `determineCacheStatus returns HIT for X-Cache HIT`() {
        val status = CdnHeaderParser.determineCacheStatus("HIT", emptyMap())
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `determineCacheStatus returns HIT for CF-Cache-Status HIT`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("cf-cache-status" to "HIT"))
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `determineCacheStatus returns MISS for X-Cache MISS`() {
        val status = CdnHeaderParser.determineCacheStatus("MISS", emptyMap())
        assertEquals(CacheStatus.MISS, status)
    }

    @Test
    fun `determineCacheStatus returns UNKNOWN when no cache headers`() {
        val status = CdnHeaderParser.determineCacheStatus(null, emptyMap())
        assertEquals(CacheStatus.UNKNOWN, status)
    }

    @Test
    fun `determineCacheStatus is case insensitive`() {
        val status = CdnHeaderParser.determineCacheStatus("hit", emptyMap())
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `parse with empty headers returns UNKNOWN status`() {
        val result = CdnHeaderParser.parse(emptyMap())
        assertNull(result.cacheControl)
        assertNull(result.xCache)
        assertNull(result.via)
        assertTrue(result.cdnSpecificHeaders.isEmpty())
        assertEquals(CacheStatus.UNKNOWN, result.cacheStatus)
    }

    // Akamai live-stream headers: no X-Cache, no CF-Cache-Status, no x-cache-hits → UNKNOWN
    @Test
    fun `parse returns UNKNOWN for Akamai live stream with no explicit cache signal`() {
        val result = CdnHeaderParser.parse(
            headers(
                "X-CDN" to "Akamai",
                "Akamai-GRN" to "0.1e2c1002.1777018113.170fcc53",
                "Cache-Control" to "max-age=0, no-cache",
            )
        )
        assertEquals(CacheStatus.UNKNOWN, result.cacheStatus)
    }

    @Test
    fun `parse captures X-CDN header in cdnSpecificHeaders`() {
        val result = CdnHeaderParser.parse(headers("X-CDN" to "Akamai"))
        assertEquals("Akamai", result.cdnSpecificHeaders["x-cdn"])
    }

    @Test
    fun `parse captures Akamai-GRN header in cdnSpecificHeaders`() {
        val result = CdnHeaderParser.parse(headers("Akamai-GRN" to "0.1e2c1002.1777018113.170fcc53"))
        assertEquals("0.1e2c1002.1777018113.170fcc53", result.cdnSpecificHeaders["akamai-grn"])
    }

    @Test
    fun `determineCacheStatus returns UNKNOWN when no actionable cache headers`() {
        val status = CdnHeaderParser.determineCacheStatus(null, emptyMap())
        assertEquals(CacheStatus.UNKNOWN, status)
    }

    @Test
    fun `determineCacheStatus X-Cache HIT takes precedence over no-cache Cache-Control`() {
        val status = CdnHeaderParser.determineCacheStatus("HIT", emptyMap())
        assertEquals(CacheStatus.HIT, status)
    }

    // STALE
    @Test
    fun `determineCacheStatus returns STALE for CF-Cache-Status STALE`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("cf-cache-status" to "STALE"))
        assertEquals(CacheStatus.STALE, status)
    }

    @Test
    fun `determineCacheStatus returns STALE for X-Cache STALE`() {
        val status = CdnHeaderParser.determineCacheStatus("STALE", emptyMap())
        assertEquals(CacheStatus.STALE, status)
    }

    @Test
    fun `determineCacheStatus returns STALE for X-Cache REVALIDATED`() {
        val status = CdnHeaderParser.determineCacheStatus("REVALIDATED", emptyMap())
        assertEquals(CacheStatus.STALE, status)
    }

    // BYPASS
    @Test
    fun `determineCacheStatus returns BYPASS for X-Cache BYPASS`() {
        val status = CdnHeaderParser.determineCacheStatus("BYPASS", emptyMap())
        assertEquals(CacheStatus.BYPASS, status)
    }

    @Test
    fun `determineCacheStatus returns BYPASS for CF-Cache-Status BYPASS`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("cf-cache-status" to "BYPASS"))
        assertEquals(CacheStatus.BYPASS, status)
    }

    @Test
    fun `determineCacheStatus returns BYPASS for CF-Cache-Status DYNAMIC`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("cf-cache-status" to "DYNAMIC"))
        assertEquals(CacheStatus.BYPASS, status)
    }

    // x-cache-hits
    @Test
    fun `determineCacheStatus returns HIT for x-cache-hits greater than zero`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("x-cache-hits" to "3"))
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `determineCacheStatus returns MISS for x-cache-hits zero`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("x-cache-hits" to "0"))
        assertEquals(CacheStatus.MISS, status)
    }
}

// ── Akamai TCP_* format ───────────────────────────────────────────────────────

class AkamaiCacheStatusTest {

    @Test
    fun `determineCacheStatus returns HIT for Akamai TCP_HIT`() {
        val status = CdnHeaderParser.determineCacheStatus("TCP_HIT from a88-1-2-3.deploy.static.akamaitechnologies.com", emptyMap())
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `determineCacheStatus returns HIT for Akamai TCP_MEM_HIT`() {
        val status = CdnHeaderParser.determineCacheStatus("TCP_MEM_HIT", emptyMap())
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `determineCacheStatus returns HIT for Akamai TCP_IMS_HIT`() {
        val status = CdnHeaderParser.determineCacheStatus("TCP_IMS_HIT", emptyMap())
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `determineCacheStatus returns HIT for Akamai TCP_REFRESH_HIT`() {
        val status = CdnHeaderParser.determineCacheStatus("TCP_REFRESH_HIT", emptyMap())
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `determineCacheStatus returns MISS for Akamai TCP_MISS`() {
        val status = CdnHeaderParser.determineCacheStatus("TCP_MISS", emptyMap())
        assertEquals(CacheStatus.MISS, status)
    }

    @Test
    fun `determineCacheStatus returns MISS for Akamai TCP_REFRESH_MISS`() {
        val status = CdnHeaderParser.determineCacheStatus("TCP_REFRESH_MISS", emptyMap())
        assertEquals(CacheStatus.MISS, status)
    }
}

// ── Precedence ────────────────────────────────────────────────────────────────

class CacheStatusPrecedenceTest {

    @Test
    fun `x-cache HIT takes precedence over CF-Cache-Status MISS`() {
        val status = CdnHeaderParser.determineCacheStatus("HIT", mapOf("cf-cache-status" to "MISS"))
        assertEquals(CacheStatus.HIT, status)
    }

    @Test
    fun `x-cache MISS takes precedence over CF-Cache-Status HIT`() {
        val status = CdnHeaderParser.determineCacheStatus("MISS", mapOf("cf-cache-status" to "HIT"))
        assertEquals(CacheStatus.MISS, status)
    }
}

// ── Malformed x-cache-hits ────────────────────────────────────────────────────

class XCacheHitsMalformedTest {

    @Test
    fun `determineCacheStatus returns MISS for non-numeric x-cache-hits`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("x-cache-hits" to "abc"))
        assertEquals(CacheStatus.MISS, status)
    }

    @Test
    fun `determineCacheStatus returns MISS for empty x-cache-hits`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("x-cache-hits" to ""))
        assertEquals(CacheStatus.MISS, status)
    }

    @Test
    fun `determineCacheStatus returns MISS for negative x-cache-hits`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("x-cache-hits" to "-1"))
        assertEquals(CacheStatus.MISS, status)
    }

    @Test
    fun `determineCacheStatus returns HIT for large x-cache-hits without overflow`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("x-cache-hits" to "999999999999999999"))
        assertEquals(CacheStatus.HIT, status)
    }
}

// ── Case sensitivity ──────────────────────────────────────────────────────────

class HeaderCaseSensitivityTest {

    private fun headers(vararg pairs: Pair<String, String>): Map<String, List<String>> =
        pairs.associate { (k, v) -> k to listOf(v) }

    @Test
    fun `parse is case insensitive for Cache-Control key`() {
        listOf("Cache-Control", "CACHE-CONTROL", "cache-control").forEach { key ->
            val result = CdnHeaderParser.parse(headers(key to "max-age=3600"))
            assertEquals("max-age=3600", result.cacheControl)
        }
    }

    @Test
    fun `parse is case insensitive for X-Cache key`() {
        listOf("X-Cache", "X-CACHE", "x-cache").forEach { key ->
            val result = CdnHeaderParser.parse(headers(key to "HIT"))
            assertEquals("HIT", result.xCache)
        }
    }
}

// ── Edge value handling ───────────────────────────────────────────────────────

class EdgeValueHandlingTest {

    @Test
    fun `parse handles header present with empty value list`() {
        val result = CdnHeaderParser.parse(mapOf("x-cache" to emptyList()))
        assertNull(result.xCache)
        assertEquals(CacheStatus.UNKNOWN, result.cacheStatus)
    }

    @Test
    fun `parse stores first via value when multiple are present`() {
        val result = CdnHeaderParser.parse(mapOf("via" to listOf("1.1 varnish", "1.1 CloudFront")))
        assertEquals("1.1 varnish", result.via)
    }
}

// ── Cloudflare edge tokens ────────────────────────────────────────────────────

class CloudflareEdgeTokenTest {

    @Test
    fun `determineCacheStatus returns STALE for CF-Cache-Status REVALIDATED`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("cf-cache-status" to "REVALIDATED"))
        assertEquals(CacheStatus.STALE, status)
    }

    @Test
    fun `determineCacheStatus returns STALE for CF-Cache-Status UPDATING`() {
        val status = CdnHeaderParser.determineCacheStatus(null, mapOf("cf-cache-status" to "UPDATING"))
        assertEquals(CacheStatus.STALE, status)
    }
}

// ── CdnProvider detection ─────────────────────────────────────────────────────

class CdnProviderDetectionTest {

    private fun headers(vararg pairs: Pair<String, String>): Map<String, List<String>> =
        pairs.associate { (k, v) -> k to listOf(v) }

    @Test
    fun `parse detects Cloudflare from CF-Cache-Status`() {
        val result = CdnHeaderParser.parse(headers("CF-Cache-Status" to "HIT"))
        assertEquals(CdnProvider.CLOUDFLARE, result.cdnProvider)
    }

    @Test
    fun `parse detects Cloudflare from CF-Ray`() {
        val result = CdnHeaderParser.parse(headers("CF-Ray" to "abc123-IAD"))
        assertEquals(CdnProvider.CLOUDFLARE, result.cdnProvider)
    }

    @Test
    fun `parse detects CloudFront from X-Amz-Cf-Pop`() {
        val result = CdnHeaderParser.parse(headers("X-Amz-Cf-Pop" to "IAD55-C1"))
        assertEquals(CdnProvider.CLOUDFRONT, result.cdnProvider)
    }

    @Test
    fun `parse detects CloudFront from X-Amz-Cf-Id`() {
        val result = CdnHeaderParser.parse(headers("X-Amz-Cf-Id" to "abc123=="))
        assertEquals(CdnProvider.CLOUDFRONT, result.cdnProvider)
    }

    @Test
    fun `parse detects Fastly from X-Served-By with varnish via`() {
        val result = CdnHeaderParser.parse(
            mapOf(
                "X-Served-By" to listOf("cache-iad-1"),
                "Via" to listOf("1.1 varnish"),
            )
        )
        assertEquals(CdnProvider.FASTLY, result.cdnProvider)
    }

    @Test
    fun `parse does not detect Fastly when via lacks varnish`() {
        val result = CdnHeaderParser.parse(headers("X-Served-By" to "cache-iad-1"))
        // X-Served-By alone without varnish via is not enough to confirm Fastly
        assertNotEquals(CdnProvider.FASTLY, result.cdnProvider)
    }

    @Test
    fun `parse detects Akamai from Akamai-GRN`() {
        val result = CdnHeaderParser.parse(headers("Akamai-GRN" to "0.1e2c1002.1777018113.170fcc53"))
        assertEquals(CdnProvider.AKAMAI, result.cdnProvider)
    }

    @Test
    fun `parse detects Akamai from X-Akamai-Request-Id`() {
        val result = CdnHeaderParser.parse(headers("X-Akamai-Request-Id" to "abc123"))
        assertEquals(CdnProvider.AKAMAI, result.cdnProvider)
    }

    @Test
    fun `parse detects Akamai from X-CDN Akamai value`() {
        val result = CdnHeaderParser.parse(headers("X-CDN" to "Akamai"))
        assertEquals(CdnProvider.AKAMAI, result.cdnProvider)
    }

    @Test
    fun `parse detects CloudFront from via header`() {
        val result = CdnHeaderParser.parse(mapOf("Via" to listOf("1.1 CloudFront (CloudFront)")))
        assertEquals(CdnProvider.CLOUDFRONT, result.cdnProvider)
    }

    @Test
    fun `parse detects CloudFront from comma-separated via`() {
        val result = CdnHeaderParser.parse(mapOf("Via" to listOf("1.1 varnish, 1.1 CloudFront")))
        assertEquals(CdnProvider.CLOUDFRONT, result.cdnProvider)
    }

    @Test
    fun `parse returns UNKNOWN provider when no CDN signals present`() {
        val result = CdnHeaderParser.parse(emptyMap())
        assertEquals(CdnProvider.UNKNOWN, result.cdnProvider)
    }
}
