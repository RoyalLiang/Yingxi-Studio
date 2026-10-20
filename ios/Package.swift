// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "YingxiCore", platforms: [.macOS(.v13), .iOS(.v17)], products: [.library(name: "YingxiCore", targets: ["YingxiCore"])], targets: [.target(name: "YingxiCore", path: "Sources/Core"), .testTarget(name: "YingxiCoreTests", dependencies: ["YingxiCore"], path: "Tests/YingxiCoreTests")])
