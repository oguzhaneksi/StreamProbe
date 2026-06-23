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
        path: "sdk/build/XCFrameworks/release/StreamProbeCore.xcframework"
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
        .library(name: "StreamProbe", targets: ["StreamProbeIOS"]),
    ],
    targets: [
        coreBinaryTarget,
        .target(
            name: "StreamProbeIOS",
            dependencies: ["StreamProbeCore"],
            path: "Sources/StreamProbeIOS"
        ),
    ]
)
