# Install

The one dependency line, `implementation("io.github.john-rocky.litertlm:litertlm-genai:0.1.1")`, is all of it, and it brings the lower level with it. For that level alone, use `io.github.john-rocky.litertlm:litertlm-kmp:0.1.1` instead. The Maven group has a hyphen (`john-rocky`) and the Kotlin package does not (`io.github.johnrocky.litertlm`).

In a project from the [KMP wizard](https://kmp.jetbrains.com), the line goes in `commonMain.dependencies` in `shared/build.gradle.kts`. `androidApp` then calls your functions in `shared` and does not see the library's types: importing one there fails with `Unresolved reference 'io'`. To use them in `androidApp`, write `api` in place of `implementation`.

| You need | Because |
|---|---|
| `google()` and `mavenCentral()` in the repositories | LiteRT-LM is on Google Maven. A project from the [KMP wizard](https://kmp.jetbrains.com) has both. |
| Kotlin 2.3.0 or newer | On iOS an older compiler cannot read these klibs. On Android it cannot read the Kotlin standard library that Google's runtime brings. Checked with 2.3.0, 2.3.10 and 2.4.21. |
| `minSdk` 24 on Android | The wizard's default. [`litertlm-genai-nano`](install.md#gemini-nano) and [Koog](lower-level.md#koog) need 26. The phones in [Tested on](../README.md#tested-on) run Android 16 and 17. |
| Java 21 for the JVM target | Google's `litertlm-jvm` is Java 21 bytecode. The wizard's Gradle already runs on 21. Elsewhere, add `kotlin { jvmToolchain(21) }`. |

What it adds to an app: on Android, native code for each ABI, about 23 MB for `arm64-v8a` and about 27 MB for `x86_64` (`abiFilters` or an App Bundle keeps one); on iOS, a 48 MB framework; on the JVM, a 134 MB jar. The sample app with R8 and `arm64-v8a` alone is 28 MB, or 26 MB without `litertlm-genai-nano`. Every Android phone in [Tested on](../README.md#tested-on) is `arm64-v8a`.

On Android there is nothing else to set up. The AARs bring their own R8 rules and manifest entries: `<uses-native-library>` for the GPU, the `INTERNET` permission for the model's download, and a provider that gives the library the application context.

### iOS

Maven brings the Kotlin code. Google's native runtime, `CLiteRTLM.xcframework`, comes from a Swift package that the app adds. In Xcode, choose **File > Add Package Dependencies...**, enter `https://github.com/john-rocky/litert-lm-kmp` and add the product `CLiteRTLM` to the app target.

The package has only Google's release and its checksum, so it needs no git-lfs. Xcode embeds and signs the framework. The Kotlin framework can be static or dynamic.

Without the package the app still builds and runs. `initialize()` throws `LiteRtLmException.Internal`, a task throws `GenAiException` before it downloads anything, and both say to add the package. So a Kotlin module can depend on this library and leave the package to the app.

On an iPhone 18 Pro, Gemma 4 E2B runs without extra entitlements. For a larger model, add `com.apple.developer.kernel.increased-memory-limit` and `com.apple.developer.kernel.extended-virtual-addressing`: on that phone they raise the memory available to the app from about 3.3 GiB to about 6 GiB. Other iPhones have not been tested.

The simulator runs the CPU backend. `Backend.GPU()` fails there with `BackendUnavailable`, and a task on the GPU with `NOT_SUPPORTED`.

The package declares iOS 15. It has run on iOS 27.2 on the phone and on iOS 27.0 in the simulator.

A Kotlin test on iOS links without the runtime, and a load in it fails the same way. To load a model in a test, add the runtime to the test binary, which Xcode does not build: [`docs/ios-tests.md`](ios-tests.md).

### Gemini Nano

To use Gemini Nano on Android phones that have it, add one dependency, in the same file as the first. The code stays as it is, and `Auto`, the default, then asks Gemini Nano first ([Who answers](who-answers.md)):

```kotlin
androidMain.dependencies {
    implementation("io.github.john-rocky.litertlm:litertlm-genai-nano:0.1.1")
}
```

This artifact uses ML Kit GenAI, which has its own [terms](https://developers.google.com/ml-kit/genai-terms).

It needs `minSdk` 26, which ML Kit's artifacts declare, and the same version as `litertlm-genai`. It brings the R8 rule that ML Kit needs, and ML Kit's AARs add what they declare for AICore to the manifest. An app without it has no ML Kit class in its APK and no AICore line in its manifest.
