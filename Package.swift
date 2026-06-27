// swift-tools-version:5.9
import PackageDescription
import Foundation

// Dev/CI iteration uses the locally-built XCFramework (set STREAMPROBE_LOCAL=1 in the
// environment); external consumers resolve the released zip. The remote `checksum` is a
// placeholder filled by `.github/workflows/publish-spm.yml` at the first release (Plan 3).
let useLocalBinary = ProcessInfo.processInfo.environment["STREAMPROBE_LOCAL"] != nil

let coreBinaryTarget: Target = useLocalBinary
    ? .binaryTarget(
        name: "StreamProbeCore",
        // Local dev consumes the *debug* XCFramework: Kotlin/Native debug links far faster than
        // release, which matters because the iosApp scheme rebuilds it on every build. The
        // released zip (remote branch below) is always built Release by publish-spm.yml.
        path: "sdk/build/XCFrameworks/debug/StreamProbeCore.xcframework"
    )
    : .binaryTarget(
        name: "StreamProbeCore",
        url: "https://github.com/oguzhaneksi/StreamProbe/releases/download/v0.1.0/StreamProbeCore.xcframework.zip",
        checksum: "0000000000000000000000000000000000000000000000000000000000000000"
    )

let package = Package(
    name: "StreamProbe",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "StreamProbe", targets: ["StreamProbe"]),
    ],
    targets: [
        coreBinaryTarget,
        .target(
            name: "StreamProbe",
            dependencies: ["StreamProbeCore"],
            path: "Sources/StreamProbe"
        ),
    ]
)
