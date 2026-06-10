package com.streamprobe.sdk.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Bounded, thread-safe handoff between the I/O-thread writer (TimingDataSource.open) and the
 * playback-thread reader (PlayerInterceptor.onLoadCompleted).
 *
 * Entries are keyed by "uri|position". FIFO eviction (insertion-ordered [LinkedHashMap] + manual
 * eldest removal) prevents un-consumed entries (manifest/key/cancelled loads) from leaking.
 */
internal class NetworkTimingRegistry {
    private val lock = SynchronizedObject()

    // Insertion-ordered. The common LinkedHashMap has no removeEldestEntry hook, so eviction of the
    // eldest entry is performed manually after each insert (see record()).
    // Re-recording an existing key removes it first so it moves to the tail (most-recent), preventing
    // a re-timed retry from being evicted before onLoadCompleted can consume it.
    private val entries = LinkedHashMap<String, Long>()

    fun record(
        uri: String,
        position: Long,
        ttfbMs: Long,
    ) {
        synchronized(lock) {
            val k = key(uri, position)
            entries.remove(k) // refresh insertion order on re-record (e.g. ExoPlayer segment retry)
            entries[k] = ttfbMs
            if (entries.size > MAX_ENTRIES) {
                val eldest = entries.keys.firstOrNull()
                if (eldest != null) entries.remove(eldest)
            }
        }
    }

    fun consume(
        uri: String,
        position: Long,
    ): Long? = synchronized(lock) { entries.remove(key(uri, position)) }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun key(
        uri: String,
        position: Long,
    ) = "$uri|$position"

    companion object {
        internal const val MAX_ENTRIES = 128
    }
}
