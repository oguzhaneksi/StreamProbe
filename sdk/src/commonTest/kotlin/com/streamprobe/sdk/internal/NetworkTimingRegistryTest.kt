package com.streamprobe.sdk.internal

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetworkTimingRegistryTest {
    private lateinit var registry: NetworkTimingRegistry

    @BeforeTest
    fun setUp() {
        registry = NetworkTimingRegistry()
    }

    @Test
    fun `record then consume returns the recorded value`() {
        registry.record("https://example.com/seg.ts", 0L, 42L)
        assertEquals(42L, registry.consume("https://example.com/seg.ts", 0L))
    }

    @Test
    fun `consume removes the entry so second consume returns null`() {
        registry.record("https://example.com/seg.ts", 0L, 42L)
        registry.consume("https://example.com/seg.ts", 0L)
        assertNull(registry.consume("https://example.com/seg.ts", 0L))
    }

    @Test
    fun `consume for absent key returns null`() {
        assertNull(registry.consume("https://example.com/nothere.ts", 0L))
    }

    @Test
    fun `same uri with different positions are distinct entries`() {
        registry.record("https://example.com/seg.ts", 0L, 10L)
        registry.record("https://example.com/seg.ts", 1024L, 20L)
        assertEquals(10L, registry.consume("https://example.com/seg.ts", 0L))
        assertEquals(20L, registry.consume("https://example.com/seg.ts", 1024L))
    }

    @Test
    fun `key collision guard - uri with pipe and position zero vs uri without pipe`() {
        // "a|1" pos=0  vs  "a" pos=1  must NOT alias
        registry.record("a|1", 0L, 11L)
        registry.record("a", 1L, 22L)
        assertEquals(11L, registry.consume("a|1", 0L))
        assertEquals(22L, registry.consume("a", 1L))
    }

    @Test
    fun `eviction drops oldest entry when MAX_ENTRIES exceeded`() {
        val max = NetworkTimingRegistry.MAX_ENTRIES
        for (i in 0..max) {
            registry.record("https://example.com/seg$i.ts", 0L, i.toLong())
        }
        // Entry 0 should have been evicted (oldest)
        assertNull(registry.consume("https://example.com/seg0.ts", 0L))
        // The last entry should still be present
        assertEquals(max.toLong(), registry.consume("https://example.com/seg$max.ts", 0L))
    }

    @Test
    fun `clear empties all entries`() {
        registry.record("https://example.com/seg.ts", 0L, 50L)
        registry.clear()
        assertNull(registry.consume("https://example.com/seg.ts", 0L))
    }

    @Test
    fun `re-recording an existing key refreshes insertion order so it survives subsequent evictions`() {
        val max = NetworkTimingRegistry.MAX_ENTRIES
        // Fill to capacity.
        for (i in 1..max) {
            registry.record("https://example.com/seg$i.ts", 0L, i.toLong())
        }
        // Re-record seg1 (currently eldest) with a new timing — this should move it to the tail.
        registry.record("https://example.com/seg1.ts", 0L, 999L)
        // Insert one more new entry, which triggers eviction of the new eldest (seg2).
        registry.record("https://example.com/seg${max + 1}.ts", 0L, 0L)
        // seg1 was re-recorded and moved to tail, so it must still be present.
        assertEquals(999L, registry.consume("https://example.com/seg1.ts", 0L))
        // seg2 should have been evicted as the new eldest.
        assertNull(registry.consume("https://example.com/seg2.ts", 0L))
    }
}
