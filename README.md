# litert-lm-kmp

Run LLMs on the phone from Kotlin Multiplatform: one API in `commonMain` for Android, iOS and the JVM, on Google's LiteRT-LM 0.19.0. On 2026-11-05, with the model already loaded, an iPhone 18 Pro summarized the 278-word Gettysburg Address in 0.9 s and a Pixel 8a in 5.2 s, with Gemma 4 E2B on the GPU. With the `litertlm-genai-nano` artifact, a Galaxy S26 ran the same call in 1.5 s on Gemini Nano.

```kotlin
implementation("io.github.john-rocky.litertlm:litertlm-genai:0.1.0") // in commonMain.dependencies
```
```kotlin
val summarizer = Summarization.getClient(SummarizerOptions.builder().build())
val summary = summarizer.runInference(SummarizationRequest.builder(article).build()).summary
```

That is the whole integration on Android and the JVM in a project from the KMP wizard. iOS adds one Swift package ([iOS](#ios)). There is no API key.

- **The same API on every phone.** The task APIs use the same class, method and constant names as ML Kit GenAI, so code written for ML Kit moves by changing the import and calling `suspend` functions where it waited for futures.
- **A LiteRT-LM model on every platform.** On Android, iOS and the JVM a LiteRT-LM model answers, and the first request downloads Gemma 4 E2B (2.6 GB, Apache-2.0). Keep the screen on until it ends: [Choose the model](#choose-the-model) says why.
- **Gemini Nano when you add one line.** With `litertlm-genai-nano` in `androidMain`, the same calls use Gemini Nano on phones that have it, for the features it has fetched ([Who answers](#who-answers)).
- **You choose the model.** A `.litertlm` file, from a public Hugging Face repository or from disk.
- **A lower level when you need it.** `Engine`, `Conversation` and `EmbeddingEngine`, with the names of Google's Kotlin API, for tool calls, images and embeddings.

This is an independent project, not a Google product.

## Install

The dependency line above is all of it, and it brings the lower level with it. For that level alone, use `io.github.john-rocky.litertlm:litertlm-kmp:0.1.0` instead. The Maven group has a hyphen (`john-rocky`) and the Kotlin package does not (`io.github.johnrocky.litertlm`).

In a project from the [KMP wizard](https://kmp.jetbrains.com), the line goes in `commonMain.dependencies` in `shared/build.gradle.kts`. `androidApp` then calls your functions in `shared` and does not see the library's types: importing one there fails with `Unresolved reference 'io'`. To use them in `androidApp`, write `api` in place of `implementation`.

| You need | Because |
|---|---|
| `google()` and `mavenCentral()` in the repositories | LiteRT-LM is on Google Maven. A project from the [KMP wizard](https://kmp.jetbrains.com) has both. |
| Kotlin 2.3.0 or newer | On iOS an older compiler cannot read these klibs. On Android it cannot read the Kotlin standard library that Google's runtime brings. Checked with 2.3.0, 2.3.10 and 2.4.21. |
| `minSdk` 24 on Android | The wizard's default. [`litertlm-genai-nano`](#gemini-nano) and [Koog](#koog) need 26. The phones in [Tested on](#tested-on) run Android 16 and 17. |
| Java 21 for the JVM target | Google's `litertlm-jvm` is Java 21 bytecode. The wizard's Gradle already runs on 21. Elsewhere, add `kotlin { jvmToolchain(21) }`. |

What it adds to an app: on Android, native code for each ABI, about 23 MB for `arm64-v8a` and about 27 MB for `x86_64` (`abiFilters` or an App Bundle keeps one); on iOS, a 48 MB framework; on the JVM, a 134 MB jar. The sample app with R8 and `arm64-v8a` alone is 28 MB, or 26 MB without `litertlm-genai-nano`. Every Android phone in [Tested on](#tested-on) is `arm64-v8a`.

On Android there is nothing else to set up. The AARs bring their own R8 rules and manifest entries: `<uses-native-library>` for the GPU, the `INTERNET` permission for the model's download, and a provider that gives the library the application context.

### iOS

Maven brings the Kotlin code. Google's native runtime, `CLiteRTLM.xcframework`, comes from a Swift package that the app adds. In Xcode, choose **File > Add Package Dependencies...**, enter `https://github.com/john-rocky/litert-lm-kmp` and add the product `CLiteRTLM` to the app target.

The package has only Google's release and its checksum, so it needs no git-lfs. Xcode embeds and signs the framework. The Kotlin framework can be static or dynamic.

Without the package the app still builds and runs. `initialize()` throws `LiteRtLmException.Internal`, a task throws `GenAiException` before it downloads anything, and both say to add the package. So a Kotlin module can depend on this library and leave the package to the app.

On an iPhone 18 Pro, Gemma 4 E2B runs without extra entitlements. For a larger model, add `com.apple.developer.kernel.increased-memory-limit` and `com.apple.developer.kernel.extended-virtual-addressing`: on that phone they raise the memory available to the app from about 3.3 GiB to about 6 GiB. Other iPhones have not been tested.

The simulator runs the CPU backend. `Backend.GPU()` fails there with `BackendUnavailable`, and a task on the GPU with `NOT_SUPPORTED`.

The package declares iOS 15. It has run on iOS 27.2 on the phone and on iOS 27.0 in the simulator.

A Kotlin test on iOS links without the runtime, and a load in it fails the same way. To load a model in a test, add the runtime to the test binary, which Xcode does not build: [`docs/ios-tests.md`](docs/ios-tests.md).

### Gemini Nano

To use Gemini Nano on Android phones that have it, add one dependency, in the same file as the first. The code stays as it is, and `Auto`, the default, then asks Gemini Nano first ([Who answers](#who-answers)):

```kotlin
androidMain.dependencies {
    implementation("io.github.john-rocky.litertlm:litertlm-genai-nano:0.1.0")
}
```

This artifact uses ML Kit GenAI, which has its own [terms](https://developers.google.com/ml-kit/genai-terms).

It needs `minSdk` 26, which ML Kit's artifacts declare, and the same version as `litertlm-genai`. It brings the R8 rule that ML Kit needs, and ML Kit's AARs add what they declare for AICore to the manifest. An app without it has no ML Kit class in its APK and no AICore line in its manifest.

## Tasks

Each client works the same way: `getClient`, `runInference`, `close`. Put this in `commonMain`, in a file named `Tasks.kt`:

```kotlin
import io.github.johnrocky.litertlm.genai.imagedescription.*
import io.github.johnrocky.litertlm.genai.proofreading.*
import io.github.johnrocky.litertlm.genai.prompt.*
import io.github.johnrocky.litertlm.genai.rewriting.*
import io.github.johnrocky.litertlm.genai.summarization.*

// At most three bullets, each line starting with "* ". An article has to be at least 400 characters.
suspend fun summarize(article: String): String {
    val options = SummarizerOptions.builder().setOutputType(SummarizerOptions.OutputType.THREE_BULLETS).build()
    return Summarization.getClient(options).use { it.runInference(SummarizationRequest.builder(article).build()).summary }
}

suspend fun proofread(text: String): String {
    val options = ProofreaderOptions.builder().setInputType(ProofreaderOptions.InputType.KEYBOARD).build()
    return Proofreading.getClient(options).use { it.runInference(ProofreadingRequest.builder(text).build()).results.first().text }
}

suspend fun rewrite(text: String): String {
    val options = RewriterOptions.builder().setOutputType(RewriterOptions.OutputType.FRIENDLY).build()
    return Rewriting.getClient(options).use { it.runInference(RewritingRequest.builder(text).build()).results.first().text }
}

// The bytes of a JPEG or PNG file. Android also has ImageDescriptionRequest.builder(bitmap).
suspend fun describe(image: ByteArray): String =
    ImageDescription.getClient(ImageDescriberOptions.builder().build()).use {
        it.runInference(ImageDescriptionRequest.builder(image).build()).description
    }

suspend fun ask(prompt: String): String =
    Generation.getClient().use { it.generateContent(prompt).candidates.first().text }
```

Then call them from each platform:

```kotlin
// Android, in an Activity. Without the handler, a failure such as a download with no network ends the app.
val onFailure = CoroutineExceptionHandler { _, e -> show(e.message ?: "The request failed.") }
lifecycleScope.launch(onFailure) { show(summarize(article)) }
```
```swift
// iOS, from Swift, on the main actor. "Shared" is the wizard's name for the Kotlin framework.
import Shared

let summary = try await TasksKt.summarize(article: article)
```
```kotlin
// The JVM
fun main() = runBlocking { println(summarize(File("article.txt").readText())) }
```

To get the text as it is generated, pass a callback. Each call is the next piece:

```kotlin
import io.github.johnrocky.litertlm.genai.summarization.*

suspend fun summarizeLive(summarizer: Summarizer, article: String, show: (String) -> Unit): String {
    var text = ""
    val result = summarizer.runInference(SummarizationRequest.builder(article).build()) { piece ->
        text += piece
        show(text)
    }
    show(result.summary)
    return result.summary
}
```

The pieces are for the screen, and the result has the whole text. On a Pixel 11 Pro with Gemini Nano, the pieces of a three-bullet summary lacked a word, an apostrophe or a line break in 8 of 75 runs, in debug and release builds. In the one run traced, ML Kit's callback had already lost the line break. The result was whole in all of them.

Keep a client open while its screen is. Clients with the same file and settings share one loaded model, and closing the last one releases it, so a client per request loads the model each time. `close()` does not block. What is running then fails with `CANCELLED`.

The task clients keep one model in memory between them, and their requests run one at a time. A client with another file or other settings waits for the request in progress, then the loaded model closes and its own loads. The other client stays open, and its next request loads its model again.

A few details of the API:

- Functions are `suspend` and return the result. `downloadFeature` returns when the download has finished or failed, and a failure goes to the callback.
- `getClient` takes an optional last argument, the [provider](#who-answers).
- Common code passes images as bytes. On Android, `builder(bitmap)`, `ImagePart(bitmap)`, `ImagePart(uri)` and `content { image(bitmap) }` also work.
- Pass a photo at about 1536 pixels on its long side, as an upright JPEG or PNG. An iPhone 18 Pro with 1,906 MiB free before the load went down to 37 to 39 MiB on the CPU with a 48 MP photo at full size, close to the point where iOS kills the app (three runs, none killed), and to 281 MiB with the same photo at 1536 x 1152 (one run). `readPhoto` and `jpegForModel` in the sample do this on Android and iOS.
- The prompt API has no structured output, caches or tools. Tools are in the [lower level](#the-lower-level).
- On a local model `countTokens` fails with `NOT_SUPPORTED`, and a proofreader or rewriter gives one suggestion.

## Chat

A request can carry the earlier turns, so a chat is a list that the app keeps:

```kotlin
import io.github.johnrocky.litertlm.genai.prompt.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class Chat(private val model: GenerativeModel = Generation.getClient()) : AutoCloseable by model {
    private val turns = mutableListOf<Content>()

    fun send(message: String): Flow<String> = flow {
        turns += content { text(message) }
        val reply = StringBuilder()
        try {
            model.generateContentStream(GenerateContentRequest.Builder(turns.toList()).build()).collect { response ->
                val piece = response.candidates.first().text
                reply.append(piece)
                emit(piece)
            }
        } finally {
            turns += content(Content.Role.MODEL) { text(reply.toString()) }
        }
    }
}
```

Cancelling the collector stops the model. Two turns of this ran on the local model on a Pixel 8a, an iPhone 18 Pro and the JVM. A chat with earlier turns has not run on Gemini Nano yet.

## Who answers

`getClient` takes the provider as its last argument:

```kotlin
import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.genai.common.*
import io.github.johnrocky.litertlm.genai.summarization.*

fun clients(options: SummarizerOptions, path: String) = listOf(
    // With litertlm-genai-nano, Gemini Nano where it can answer. Otherwise Gemma 4 E2B on the CPU. The default.
    Summarization.getClient(options),
    // The same, with your model on the GPU.
    Summarization.getClient(options, ModelProvider.Auto(LocalModel.file(path), Backend.GPU())),
    // Only Gemini Nano. Without litertlm-genai-nano, and on iOS and the JVM, it fails with NOT_AVAILABLE.
    Summarization.getClient(options, ModelProvider.GeminiNano),
    // Only the local model.
    Summarization.getClient(options, ModelProvider.LiteRtLm(LocalModel.file(path), Backend.GPU())),
)
```

`Auto` decides again on each request, by Gemini Nano's status for this feature on this device:

| Gemini Nano's status | Who answers |
|---|---|
| AVAILABLE | Gemini Nano |
| DOWNLOADABLE or DOWNLOADING, and the local model is on the device | The local model. `Auto` does not fetch what Nano is missing. |
| DOWNLOADABLE or DOWNLOADING, and no local model on the device | Gemini Nano, after its download |
| UNAVAILABLE, or the status check fails | The local model, downloaded first if needed |
| None: the app does not have `litertlm-genai-nano` | The local model, downloaded first if needed |

A client's `activeProvider` and `activeProviderReason` say who answered and why. To have Nano fetch a feature on a phone that has the local model, call `downloadFeature` on a client made with `ModelProvider.GeminiNano`.

Once Nano has a request, `Auto` moves to the local model, if that is on the device, when Nano answers `BACKGROUND_USE_BLOCKED`, `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`, `NOT_AVAILABLE`, `NOT_SUPPORTED`, `AICORE_INCOMPATIBLE`, `NEEDS_SYSTEM_UPDATE` or `NOT_ENOUGH_DISK_SPACE`, or `BUSY` after three retries. It never starts a download for this, and never moves once text has reached a streaming callback.

What the phones did on 2026-11-05, in an app with `litertlm-genai-nano`:

| Phone | Gemini Nano | `Auto` |
|---|---|---|
| Galaxy S26 (Android 16) | `nano-v3`, every task | Gemini Nano (summarization and rewriting ran) |
| Pixel 11 Pro (Android 17) | `nano-v4-full`. Only summarization ran: the phone had no network to fetch the other features. | Summarization: Gemini Nano. Rewriting: LiteRT-LM. |
| Pixel 8a (Android 16) | None: the status check fails with error code 606 | LiteRT-LM |

Gemini Nano has limits of its own. [`docs/tested-devices.md`](docs/tested-devices.md#who-answered) has what the phones did.

- **Foreground only.** In the background, an explicit `GeminiNano` fails with `BACKGROUND_USE_BLOCKED`, and `Auto` uses the local model if it is on the device. The local model can stop there too: on a Pixel 8a with another app on top, Android froze the app 10 to 70 s later in 6 of 6 runs, and the request went on only after the app came back.
- **A feature not yet fetched.** An explicit `GeminiNano` waits for the download. On a Pixel 11 Pro without a network, one request failed after 260 s.
- **Error code 11.** Nano can stop a streamed text with `[ErrorCode 11]`, from a policy check. `Auto` does not switch on this error, so handle it as a failure.
- **No API key.** Requests go through AICore on the phone. ML Kit's documentation says the API does not work with an unlocked bootloader. Its [device list](https://developers.google.com/ml-kit/genai) says which phones have Nano.

## Choose the model

```kotlin
import io.github.johnrocky.litertlm.genai.common.*

val default = LocalModel.Gemma4E2B
val fromTheHub = LocalModel.huggingFace("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm")
val onDisk = LocalModel.file("/path/to/model.litertlm")
```

`LocalModel.huggingFace` takes public repositories only. For a gated one, get the file yourself and use `LocalModel.file`. The download resumes after a failure, and the file appears only after its size and SHA-256 match what the Hub lists. Gemma 4 E2B (2,588,147,712 bytes) took 143 s over Wi-Fi on a Pixel 8a.

By default the file goes to a directory of this library's that backups leave out: `noBackupFilesDir/litertlm-genai/models` on Android, Application Support on iOS, `~/.cache/litertlm-genai/models` on the JVM, each with a folder per repository. To know the path, pass `directory`: the file is then `directory/file`. That is also how to delete it or hand it to `Engine`.

A request downloads a missing model first, without telling you. To show progress, download before the first request:

```kotlin
import io.github.johnrocky.litertlm.genai.common.*
import io.github.johnrocky.litertlm.genai.summarization.*

suspend fun fetch(summarizer: Summarizer, show: (String) -> Unit) {
    if (summarizer.checkFeatureStatus() != FeatureStatus.DOWNLOADABLE) return
    var total = 0L
    summarizer.downloadFeature(object : DownloadCallback {
        override fun onDownloadStarted(bytesToDownload: Long) { total = bytesToDownload }
        override fun onDownloadProgress(totalBytesDownloaded: Long) { show("$totalBytesDownloaded of $total bytes") }
        override fun onDownloadCompleted() { show("Done") }
        override fun onDownloadFailed(e: GenAiException) { show(e.message ?: "The download failed.") }
    })
}
```

Keep the screen on until the download ends. On a Pixel 8a with Android 16, the download failed 14 s after the screen went off, because Android stopped the app's network. The failure says so, and the next request goes on from where it stopped. On Android, add `FLAG_KEEP_SCREEN_ON` to the Activity's window. On iOS, set `UIApplication.shared.isIdleTimerDisabled`. The sample does both. What a download does on iOS with the screen off has not been measured.

The backend is the CPU unless you say otherwise. The GPU is Metal on iOS, OpenCL on Android and WebGPU on the JVM on a Mac. A three-bullet summary of the same text, with the model loaded:

| Phone | GPU | CPU |
|---|---|---|
| Pixel 11 Pro | 4.2 s | 10.9 s |
| Pixel 8a | 5.2 s | 9.3 s |
| iPhone 18 Pro | 0.9 s | 2.7 s |

The first load on the GPU has no cache yet: 20.6 s on the Pixel 11 Pro and 30.6 s on the Pixel 8a, against 1.9 s and 6.3 s on the CPU.

That first load also writes a cache, so plan for more disk than the file. On an iPhone 18 Pro, Gemma 4 E2B added about 0.8 GB for text and about 1 GB with images. On the GPU the cache is per `maxNumTokens` (4608 by default): 2048 and 4608 in the same directory took 1,616 MB. A second load with the same settings added nothing.

## The lower level

`Engine`, `Conversation` and `EmbeddingEngine` take a `.litertlm` file by path. A `Conversation` keeps its turns.

```kotlin
import io.github.johnrocky.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun chatOnEngine(path: String, show: (String) -> Unit) {
    val engine = Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = 4096))
    try {
        engine.initialize()
        val conversation = engine.createConversation()
        conversation.stream("What is the capital of France?").collect { show(it) }
        conversation.stream("And of Japan?").collect { show(it) }
    } finally {
        // close() waits for a running reply to stop, so it stays off the main thread.
        withContext(Dispatchers.Default) { engine.close() }
    }
}
```

Tools come back to the caller. The library does not run them:

```kotlin
import io.github.johnrocky.litertlm.*

object GetWeather : OpenApiTool {
    override fun getToolDescriptionJsonString() =
        """{"name":"get_weather","description":"Get the current weather for a city.",""" +
            """"parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}"""
}

suspend fun askWithTool(engine: Engine): String {
    val conversation = engine.createConversation(ConversationConfig(tools = listOf(tool(GetWeather))))
    val reply = conversation.sendMessage("What is the weather in Tokyo right now? Use the tool.")
    val call = reply.toolCalls.firstOrNull() ?: return reply.toString()
    val result = Content.ToolResponse(call.name, mapOf("temperature_c" to 21, "condition" to "sunny"))
    return conversation.sendMessage(Message.tool(Contents.of(result))).toString()
}
```

An image needs `visionBackend`, and an embedding its own model:

```kotlin
import io.github.johnrocky.litertlm.*

suspend fun describeOnEngine(path: String, jpeg: ByteArray): String {
    val engine = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), visionBackend = Backend.CPU()))
    return engine.use {
        it.initialize()
        val question = Contents.of(Content.ImageBytes(jpeg), Content.Text("What animal is in this picture?"))
        it.createConversation().sendMessage(question).toString()
    }
}

// For example litert-community/embeddinggemma-2-text-270m-litert-lm, 165 MB.
suspend fun embed(path: String, text: String): FloatArray =
    EmbeddingEngine(EmbeddingEngineConfig(modelPath = path)).use {
        it.initialize()
        it.computeEmbedding(listOf(InputData.Text(text))).embedding
    }
```

`initialize()` first checks the file and the memory that is free. By default it wants half the file's size free on Android and iOS, or three quarters with a `visionBackend`, and it checks nothing on the JVM. Below that it throws `InsufficientMemory`, because on iOS the system kills a process that runs out, and nothing can catch that. `minAvailableMemoryBytes` changes the figure, and 0 turns the check off.

`Engine` is outside the one-model rule of the task clients, and on iOS the address space can run out before the memory does. So before you load a second model with `Engine`, close the first, and close any task client that has a model loaded. A load that does not fit reports a missing part of the model, or ends the process. `initialize()` throws `InsufficientMemory` instead when less address space is free than 1.4 times the file's size, or 2 times with a `visionBackend`, unless `minAvailableMemoryBytes` is 0. To keep two models loaded, sign the app with `com.apple.developer.kernel.extended-virtual-addressing`. The memory check still applies then. [`docs/tested-devices.md`](docs/tested-devices.md) has the figures.

## Compose Multiplatform

One composable for Android, iOS and the desktop:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.summarization.*
import kotlinx.coroutines.launch

@Composable
fun SummaryButton(article: String) {
    val summarizer = remember { Summarization.getClient(SummarizerOptions.builder().build()) }
    DisposableEffect(Unit) { onDispose { summarizer.close() } }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column {
        Button(enabled = !running, onClick = {
            scope.launch {
                running = true
                text = ""
                try {
                    text = summarizer.runInference(SummarizationRequest.builder(article).build()) { piece -> text += piece }.summary
                } catch (e: GenAiException) {
                    text = e.message ?: "Error ${e.errorCode}"
                } finally {
                    running = false
                }
            }
        }) { Text("Summarize") }
        Text(text)
    }
}
```

## Koog

[Koog](https://github.com/JetBrains/koog) agents can run on the lower level through a small `LLMClient`. This needs `ai.koog:agents-core:1.3.0` as well, and `minSdk` 26 on Android: below that, the build fails when it dexes the Jackson library that Koog brings. [`docs/koog.md`](docs/koog.md) has the code.

## Failures

The lower level throws one sealed class, with the same types on every platform:

| `LiteRtLmException` | When | What to do |
|---|---|---|
| `ModelNotFound` | No file at the path | Download it, or fix the path |
| `InvalidModel` | A directory, not a `.litertlm` file, or a file cut short | Download it again |
| `InsufficientMemory` | Less memory free than `minAvailableMemoryBytes`, or on iOS too little address space | Close other apps and your other engines, or use a smaller model |
| `InputTooLong` | The input does not fit in `maxNumTokens`, counting earlier turns | Shorten it, or start a new conversation |
| `Cancelled` | `cancelProcess()` or a close stopped the turn | The next turn works |
| `BackendUnavailable` | The backend cannot run this model here, or an image came without `visionBackend` | Use the CPU, or set `visionBackend` |
| `InvalidImage` | The bytes are not an image the runtime can decode | Send a JPEG or PNG |
| `ClosedUse`, `NotInitialized`, `AlreadyInitialized` | Calls out of order | Fix the order |
| `Internal` | On iOS, the app does not have the Swift package ([iOS](#ios)), with `code` 9. Otherwise anything else the runtime reported, with its status code in `code`. | Read the message |

The task API throws `GenAiException`, with the numbers ML Kit uses. A provider's own failure is the `cause`:

| `errorCode` | When |
|---|---|
| `NOT_AVAILABLE` (8) | The model file is missing or is not a model, or too little memory is free. Gemini Nano on iOS or the JVM, or in an Android app without `litertlm-genai-nano`: the message gives the line to add. A download that fails reports this to `onDownloadFailed`, with the host or the Hub's status in the message. |
| `REQUEST_TOO_LARGE` (12) | The input does not fit. `setLongInputAutoTruncationEnabled(true)` on a summarizer keeps the start instead. |
| `REQUEST_TOO_SMALL` (-100) | An article under 400 characters, or blank text |
| `NOT_SUPPORTED` (16) | The backend cannot run this model here, an image on a model without a vision part, or `countTokens` on a local model |
| `INVALID_INPUT_IMAGE` (-102) | The bytes are not an image the runtime can decode |
| `CANCELLED` (7) | `close()` stopped the request |
| `BACKGROUND_USE_BLOCKED` (30), `BUSY` (9) | From Gemini Nano. See [Who answers](#who-answers). |
| `NOT_ENOUGH_DISK_SPACE` (501) | A download ran out of disk |

`BUSY` and `NOT_ENOUGH_DISK_SPACE` have not come up on a phone, and `Internal` only for a missing Swift package. The other cases have, and [`docs/tested-devices.md`](docs/tested-devices.md) has the list.

## What is not here

- Audio input.
- The NPU.
- The web.
- Automatic tool calling: `automaticToolCalling` must stay `false`.
- Apple's Foundation Models API. That is [swift-litert-lm](https://github.com/john-rocky/swift-litert-lm), for Swift apps.

## Tested on

Each row ran the checks in `sample-shared` with LiteRT-LM 0.19.0, on 2026-11-05. No check failed in those runs. One can fail when it is repeated on the Pixel 11 Pro, the check of streamed pieces in [Tasks](#tasks). [`docs/tested-devices.md`](docs/tested-devices.md) has the details and the commands to run them on your own phone.

| Device | OS | Runs |
|---|---|---|
| Galaxy S26 | Android 16 | Gemini Nano, every task |
| Pixel 11 Pro | Android 17 | LiteRT-LM on GPU and CPU, Gemini Nano summarization |
| Pixel 8a | Android 16 | LiteRT-LM on GPU and CPU, `Auto` without Nano, an app without `litertlm-genai-nano` |
| iPhone 18 Pro | iOS 27.2 | LiteRT-LM on Metal and CPU |
| Mac, Apple M4 Max | macOS 27.0, JVM | LiteRT-LM on CPU |

Numbers here belong to these devices and this date. Other phones will differ.

## Samples

`sample-android` and `sample-ios` share one Compose screen in `sample-shared`: a chat and every task, with the provider and the backend to choose. `sample-shared` has `litertlm-genai-nano` in its `androidMain`. `AGENTS.md` is for coding agents.

## License

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Gemma 4 E2B is Apache-2.0 too.
