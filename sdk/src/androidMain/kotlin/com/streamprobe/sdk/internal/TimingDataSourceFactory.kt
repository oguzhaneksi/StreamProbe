package com.streamprobe.sdk.internal

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * DataSource.Factory wrapper that measures open() duration as a best-effort TTFB proxy.
 *
 * For DefaultHttpDataSource, open() blocks until HTTP response headers arrive
 * (connection.getResponseCode()), making it a valid TTFB estimator. On warm keep-alive
 * connections it closely approximates pure server TTFB; on cold connections it includes
 * connection setup time.
 *
 * Only HTTP/HTTPS schemes are timed; file/asset/cache schemes are passed through unchanged.
 * If open() throws, no entry is written (exception propagates; no false TTFB recorded). Entries are
 * keyed by request URI + byte position, and timing uses [SystemClock.elapsedRealtimeNanos] to match
 * `loadDurationMs`' clock domain.
 *
 * **Invariant:** this wrapper MUST be the outermost `DataSource.Factory` wrapper, so inner adapters
 * that throw in `open()` (e.g. error injection) propagate BEFORE the timing record is written —
 * otherwise a false TTFB is recorded on injected errors.
 */
@UnstableApi
internal class TimingDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val registry: NetworkTimingRegistry,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = TimingDataSource(delegate.createDataSource(), registry)

    private class TimingDataSource(
        private val delegate: DataSource,
        private val registry: NetworkTimingRegistry,
    ) : DataSource by delegate {
        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return delegate.open(dataSpec)
            val startNs = SystemClock.elapsedRealtimeNanos()
            val bytesToRead = delegate.open(dataSpec)
            registry.record(
                dataSpec.uri.toString(),
                dataSpec.position,
                (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000,
            )
            return bytesToRead
        }

        // DataSource.getResponseHeaders() is a Java default method. Kotlin's `by delegate`
        // only generates forwarding stubs for abstract interface methods; default methods
        // fall through to their JVM default implementation (Collections.emptyMap). This
        // explicit override is required so callers (e.g. CdnHeaderParser) receive the
        // actual HTTP response headers from the wrapped source.
        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    }
}
