# A Kotlin test on iOS that loads a model

An app gets the runtime from the Swift package, and Xcode does not build a Kotlin test binary. So a test links without the runtime, and a load in it fails with `LiteRtLmException.Internal`, saying to add the package. To load a model in a test, add the runtime to the test binary. Unzip Google's [`CLiteRTLM.xcframework.zip`](https://github.com/google-ai-edge/LiteRT-LM/releases/download/v0.19.0/CLiteRTLM.xcframework.zip) and add this inside `kotlin { }` in `shared/build.gradle.kts`:

```kotlin
iosSimulatorArm64 {
    val frameworks = "/path/to/CLiteRTLM.xcframework/ios-arm64-simulator"
    binaries.getTest("debug").linkerOpts("-F$frameworks", "-framework", "CLiteRTLM", "-rpath", frameworks)
}
```
