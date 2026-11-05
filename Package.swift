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
      url: "https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.19.0/CLiteRTLM.xcframework.zip",
      checksum: "b7b2450fc084058adc5185498f8b7810a9297e054a10cf55818b62e1d5f8d65d"
    )
  ]
)
