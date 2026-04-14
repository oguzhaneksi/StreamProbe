package com.streamprobe.sdk.internal

import com.streamprobe.sdk.model.CacheStatus
import org.junit.Assert.assertEquals
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
}
