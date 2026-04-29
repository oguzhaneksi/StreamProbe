package com.streamprobe.android

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import kotlin.random.Random

/**
 * A [DataSource.Factory] wrapper that throws a synthetic HTTP 404
 * [HttpDataSource.InvalidResponseCodeException] for a random percentage of segment
 * (.ts / .m4s) requests, as controlled by [errorRate]. Manifest and key requests pass
 * through untouched.
 */
@UnstableApi
internal class DebugDataSourceFactory(
    private val delegate: HttpDataSource.Factory,
    private val errorRate: Float = 0.2f,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = DebugDataSource(
        delegate.createDataSource(),
        errorRate,
    )

    private class DebugDataSource(
        private val delegate: HttpDataSource,
        private val errorRate: Float,
    ) : DataSource by delegate {

        override fun open(dataSpec: DataSpec): Long {
            val path = dataSpec.uri.path.orEmpty()
            val isSegment = path.endsWith(".ts") || path.endsWith(".m4s")
            if (isSegment && Random.nextFloat() < errorRate) {
                throw HttpDataSource.InvalidResponseCodeException(
                    /* responseCode= */ 404,
                    /* responseMessage= */ "Not Found (injected)",
                    /* cause= */ null,
                    /* headerFields= */ emptyMap(),
                    /* dataSpec= */ dataSpec,
                    /* responseBody= */ byteArrayOf(),
                )
            }
            return delegate.open(dataSpec)
        }

        override fun getResponseHeaders(): Map<String, List<String>> {
            return delegate.responseHeaders
        }
    }
}
