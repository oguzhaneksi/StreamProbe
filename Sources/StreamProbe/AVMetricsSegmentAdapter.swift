import AVFoundation
import StreamProbeCore

/// Extracts iOS 18 AVMetrics value objects into the plain parameters the Kotlin
/// `avMetricsSegmentMetric` mapper expects, then builds the common `SegmentMetric`.
///
/// Durations are `Date` deltas (clock-agnostic); the caller supplies the probe's monotonic
/// `nowMs()` for `requestTimestampMs` so the AVMetrics rows share one clock domain with the
/// access-log rows. On iOS 18+ the CDN response headers are always read from the segment's final
/// transaction; an absent/non-HTTP response leaves the header map empty so `cdnInfo` degrades to
/// UNKNOWN rather than guessing.
///
/// Transaction selection: a single segment fetch can produce several `URLSessionTaskTransactionMetrics`
/// (redirects, retries, cache hits). The adapter prefers the `.networkLoad` transactions and treats the
/// fetch as spanning the first's `fetchStart` to the last's `responseEnd`, summing body bytes across the
/// span so duration, size and timing describe the same work. When no `.networkLoad` transaction exists
/// (a fully cache-served segment) it falls back to the full set so the row still records.
enum AVMetricsSegmentAdapter {
    @available(iOS 18.0, *)
    static func segmentMetric(from event: AVMetricHLSMediaSegmentRequestEvent, nowMs: Int64) -> SegmentMetric {
        let resource = event.mediaResourceRequestEvent
        let allTransactions = resource?.networkTransactionMetrics?.transactionMetrics
        // Prefer real network loads; fall back to the full set for fully cache-served segments so the row
        // is not dropped to 0 / UNKNOWN.
        let networkLoads = allTransactions?.filter { $0.resourceFetchType == .networkLoad }
        let transactions = (networkLoads?.isEmpty == false ? networkLoads : allTransactions)
        let first = transactions?.first
        let last = transactions?.last

        // Total segment fetch span: first transaction's fetchStart -> last transaction's responseEnd.
        // The mediaResourceRequestEvent's own requestStartTime/responseEndTime arrive identical on
        // device (~0 duration), so the transaction-metric Dates are the source of truth here.
        let totalDurationMs: Int64 = {
            guard let start = first?.fetchStartDate, let end = last?.responseEndDate else { return 0 }
            return Int64(end.timeIntervalSince(start) * 1000)
        }()

        let sizeBytes: Int64 = {
            // Body bytes received across the whole fetch (redirect hops included).
            let received = transactions?.reduce(Int64(0)) { $0 + $1.countOfResponseBodyBytesReceived } ?? 0
            if received > 0 { return received }
            // byteRange is the fallback for full-segment requests; an unset NSRange reports NSNotFound, not 0.
            let len = event.byteRange.length
            return (len > 0 && len != NSNotFound) ? Int64(len) : 0
        }()

        let networkTiming = makeNetworkTiming(first: first, last: last)
        let headers = headerMap(last?.response)

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
    private static func makeNetworkTiming(
        first: URLSessionTaskTransactionMetrics?,
        last: URLSessionTaskTransactionMetrics?
    ) -> NetworkTiming? {
        guard let first, let last else { return nil }
        func deltaMs(_ start: Date?, _ end: Date?) -> KotlinLong? {
            guard let start, let end else { return nil }
            return KotlinLong(value: Int64(end.timeIntervalSince(start) * 1000))
        }
        // TTFB across the whole fetch: first request start -> first byte of the final response.
        let ttfbMs: Int64 = {
            guard let s = first.requestStartDate, let e = last.responseStartDate else { return 0 }
            return Int64(e.timeIntervalSince(s) * 1000)
        }()
        // DNS/connect/TLS happen on the transaction that establishes the connection (the first load);
        // they are nil on a reused connection.
        return NetworkTiming(
            ttfbMs: ttfbMs,
            transferDurationMs: deltaMs(last.responseStartDate, last.responseEndDate),
            dnsMs: deltaMs(first.domainLookupStartDate, first.domainLookupEndDate),
            connectMs: deltaMs(first.connectStartDate, first.connectEndDate),
            tlsMs: deltaMs(first.secureConnectionStartDate, first.secureConnectionEndDate),
            isEstimated: false
        )
    }

    private static func headerMap(_ response: URLResponse?) -> [String: String] {
        guard let http = response as? HTTPURLResponse else { return [:] }
        var out: [String: String] = [:]
        for (key, value) in http.allHeaderFields {
            if let key = key as? String { out[key] = (value as? String) ?? String(describing: value) }
        }
        return out
    }
}
