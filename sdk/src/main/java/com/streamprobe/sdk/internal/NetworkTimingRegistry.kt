package com.streamprobe.sdk.internal

import androidx.annotation.VisibleForTesting

/**
 * Bounded, thread-safe handoff between the I/O-thread writer (TimingDataSource.open) and the
 * playback-thread reader (PlayerInterceptor.onLoadCompleted).
 *
 * Entries are keyed by "uri@position". FIFO eviction via LinkedHashMap prevents un-consumed
 * entries (manifest/key/cancelled loads) from leaking.
 */
internal class NetworkTimingRegistry {
    private val lock = Any()
    private val entries =
        object : LinkedHashMap<String, Long>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > MAX_ENTRIES
        }

    fun record(
        uri: String,
        position: Long,
        ttfbMs: Long,
    ) {
        synchronized(lock) { entries[key(uri, position)] = ttfbMs }
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
        @VisibleForTesting
        internal const val MAX_ENTRIES = 128
    }
}
