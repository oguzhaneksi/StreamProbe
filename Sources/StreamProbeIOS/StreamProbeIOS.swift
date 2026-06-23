@_exported import StreamProbeCore

/// Umbrella for the StreamProbe iOS layer. Re-exports the Kotlin Core (`StreamProbeCore`) so
/// consumers `import StreamProbe` and get the Core types directly. The Swift entry point
/// (`attach`/`show`/`hide`), the AVFoundation probe, and the overlay UI are added in Plan 2b.
public enum StreamProbeKit {
    /// Constructs a fresh Core holder. Temporary smoke surface that forces this source target to
    /// compile against the SKIE-bridged binary API; replaced by the real entry point in Plan 2b.
    public static func makeCore() -> ProbeCore { ProbeCore() }
}
