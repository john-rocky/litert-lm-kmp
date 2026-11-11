// swift-tools-version: 5.9
// The iOS runtime for apps that use litertlm-kmp: Google's release of CLiteRTLM.xcframework, nothing else.
// A binary target resolves over HTTPS, so adding this package does not need git-lfs.
// The same URL and checksum are in gradle.properties; change them together.

import PackageDescription

let package = Package(
  name: "litert-lm-kmp",
  platforms: [.iOS(.v15)],
  products: [
    .library(name: "CLiteRTLM", targets: ["CLiteRTLM"])
  ],
  targets: [
    .binaryTarget(
      name: "CLiteRTLM",
      url: "https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.19.1/CLiteRTLM.xcframework.zip",
      checksum: "21cf294b3dfdd9c8166b0f7934ed392d5c69c8bfa57cd1a20e341a7d16874017"
    )
  ]
)
