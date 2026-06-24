import AVFoundation
import StreamProbeCore

/// Extracts iOS 18 AVMetrics value objects into the plain parameters the Kotlin
/// `avMetricsSegmentMetric` mapper expects, then builds the common `SegmentMetric`.
///
/// Durations are `Date` deltas (clock-agnostic); the caller supplies the probe's monotonic
/// `nowMs()` for `requestTimestampMs` so the AVMetrics rows share one clock domain with the
/// access-log rows. `cdnHeadersAvailable` is the device-PoC gate (Task 1): when false the header
/// map is left empty so `cdnInfo` degrades to UNKNOWN rather than guessing.
enum AVMetricsSegmentAdapter {
    /// Whether AVFoundation's HLS stack populates `URLSessionTaskTransactionMetrics.response` with
    /// usable headers for media-segment transactions. Set from the Task 1 device PoC (see spec).
    static var cdnHeadersAvailable = true

    @available(iOS 18.0, *)
    static func segmentMetric(from event: AVMetricHLSMediaSegmentRequestEvent, nowMs: Int64) -> SegmentMetric {
        let resource = event.mediaResourceRequestEvent
        let transactions = resource?.networkTransactionMetrics?.transactionMetrics
        let txn = transactions?.last

        // Total segment fetch span: first transaction's fetchStart -> last transaction's responseEnd.
        // The mediaResourceRequestEvent's own requestStartTime/responseEndTime arrive identical on
        // device (~0 duration), so the transaction-metric Dates are the source of truth here.
        let totalDurationMs: Int64 = {
            guard let start = transactions?.first?.fetchStartDate, let end = txn?.responseEndDate else { return 0 }
            return Int64(end.timeIntervalSince(start) * 1000)
        }()

        let sizeBytes: Int64 = {
            if let received = txn?.countOfResponseBodyBytesReceived, received > 0 { return received }
            let len = event.byteRange.length
            return len > 0 ? Int64(len) : 0
        }()

        let networkTiming = txn.map(makeNetworkTiming)
        let headers = cdnHeadersAvailable ? headerMap(txn?.response) : [:]

        return AVMetricsSegmentMapperKt.avMetricsSegmentMetric(
            requestTimestampMs: nowMs,
            totalDurationMs: totalDurationMs,
            sizeBytes: sizeBytes,
            uri: event.url?.absoluteString ?? "",
            responseHeaders: headers,
            networkTiming: networkTiming
        )
    }

    @available(iOS 18.0, *)
    private static func makeNetworkTiming(_ m: URLSessionTaskTransactionMetrics) -> NetworkTiming {
        func deltaMs(_ start: Date?, _ end: Date?) -> KotlinLong? {
            guard let start, let end else { return nil }
            return KotlinLong(value: Int64(end.timeIntervalSince(start) * 1000))
        }
        let ttfbMs: Int64 = {
            guard let s = m.requestStartDate, let e = m.responseStartDate else { return 0 }
            return Int64(e.timeIntervalSince(s) * 1000)
        }()
        return NetworkTiming(
            ttfbMs: ttfbMs,
            transferDurationMs: deltaMs(m.responseStartDate, m.responseEndDate),
            dnsMs: deltaMs(m.domainLookupStartDate, m.domainLookupEndDate),
            connectMs: deltaMs(m.connectStartDate, m.connectEndDate),
            tlsMs: deltaMs(m.secureConnectionStartDate, m.secureConnectionEndDate),
            isEstimated: false
        )
    }

    private static func headerMap(_ response: URLResponse?) -> [String: String] {
        guard let http = response as? HTTPURLResponse else { return [:] }
        var out: [String: String] = [:]
        for (key, value) in http.allHeaderFields {
            if let key = key as? String { out[key] = String(describing: value) }
        }
        return out
    }
}
