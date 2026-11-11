# AGENTS.md: for coding agents putting an LLM into a Kotlin Multiplatform app

Read this first if someone asked you to summarize, rewrite, describe an image or chat on the device from Kotlin, on Android, iOS or the JVM. This file routes you to the right API and the file that shows it. The pages in `docs/` have the code.

**What this is.** A Kotlin Multiplatform library over Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) runtime, in three artifacts:

- `io.github.john-rocky.litertlm:litertlm-genai:0.1.1`: the tasks, with the class and method names of ML Kit GenAI, on a LiteRT-LM model. Start here.
- `io.github.john-rocky.litertlm:litertlm-genai-nano:0.1.1`: Android only, and only if the app wants Gemini Nano. With it in `androidMain`, the same calls use Gemini Nano through ML Kit GenAI on phones that have it. No code changes.
- `io.github.john-rocky.litertlm:litertlm-kmp:0.1.1`: `Engine`, `Conversation`, `EmbeddingEngine`, with the names of Google's Kotlin API. `litertlm-genai` brings it along.

Independent project, not a Google product. No API key anywhere.

## Route

| Task | API | Where |
|---|---|---|
| Summarize, proofread, rewrite or describe an image, on any phone | `Summarization.getClient(options)`, `Proofreading`, `Rewriting`, `ImageDescription`, then `runInference(request)` | `docs/tasks.md`, [`summarization/Summarization.kt`](litertlm-genai/src/commonMain/kotlin/io/github/johnrocky/litertlm/genai/summarization/Summarization.kt) |
| A chat that keeps its turns | `Generation.getClient()`, `generateContentStream(GenerateContentRequest.Builder(turns).build())` | `docs/tasks.md` "Chat", [`prompt/Generation.kt`](litertlm-genai/src/commonMain/kotlin/io/github/johnrocky/litertlm/genai/prompt/Generation.kt) |
| Text as it is generated | `runInference(request) { piece -> }`, `generateContentStream`, `Conversation.stream` | `docs/tasks.md`, `docs/lower-level.md` |
| Use Gemini Nano on Android | `androidMain.dependencies { implementation("io.github.john-rocky.litertlm:litertlm-genai-nano:0.1.1") }`, and nothing else | README "Gemini Nano" |
| Choose Gemini Nano, the local model, or the automatic rule | `ModelProvider.Auto()`, `ModelProvider.GeminiNano`, `ModelProvider.LiteRtLm(model)`, the last argument of `getClient` | `docs/who-answers.md`, [`common/ModelProvider.kt`](litertlm-genai/src/commonMain/kotlin/io/github/johnrocky/litertlm/genai/common/ModelProvider.kt) |
| Choose the model | `LocalModel.huggingFace(repo, file)`, `LocalModel.file(path)` | `docs/choose-the-model.md` |
| A download with progress | `checkFeatureStatus()`, `downloadFeature(callback)` | `docs/choose-the-model.md`, [`internal/Download.kt`](litertlm-genai/src/commonMain/kotlin/io/github/johnrocky/litertlm/genai/internal/Download.kt) |
| Tool calls | `ConversationConfig(tools = listOf(tool(...)))`, `reply.toolCalls`, `Message.tool(...)` | `docs/lower-level.md`, [`Tool.kt`](litertlm-kmp/src/commonMain/kotlin/io/github/johnrocky/litertlm/Tool.kt) |
| An image at the lower level | `EngineConfig(visionBackend = ...)`, `Contents.of(Content.ImageBytes(bytes), Content.Text(question))` | `docs/lower-level.md`, [`Message.kt`](litertlm-kmp/src/commonMain/kotlin/io/github/johnrocky/litertlm/Message.kt) |
| Embeddings | `EmbeddingEngine(EmbeddingEngineConfig(path))`, `computeEmbedding` | `docs/lower-level.md`, [`EmbeddingEngine.kt`](litertlm-kmp/src/commonMain/kotlin/io/github/johnrocky/litertlm/EmbeddingEngine.kt) |
| A Compose Multiplatform screen | `SummaryButton` | `docs/compose-multiplatform.md` |
| A Koog agent on the device | A small `LLMClient` over `Engine` | [`docs/koog.md`](docs/koog.md) |
| Handle a failure | `LiteRtLmException`, `GenAiException.errorCode` | `docs/failures.md`, [`LiteRtLmException.kt`](litertlm-kmp/src/commonMain/kotlin/io/github/johnrocky/litertlm/LiteRtLmException.kt), [`common/GenAiException.kt`](litertlm-genai/src/commonMain/kotlin/io/github/johnrocky/litertlm/genai/common/GenAiException.kt) |
| A complete app to copy | A chat and every task, with the provider and backend to choose | [`sample-shared`](sample-shared), [`sample-android`](sample-android), [`sample-ios`](sample-ios) |
| Run the library's checks on a phone | `--deviceCheck` | [`docs/tested-devices.md`](docs/tested-devices.md) |

## Rules that fail on a real device when broken

1. **iOS needs the Swift package.** Add `https://github.com/john-rocky/litert-lm-kmp`, product `CLiteRTLM`, to the app target in Xcode. Maven brings only the Kotlin code. Without it the app still builds and runs, and the first load fails with `LiteRtLmException.Internal`, or a task with `GenAiException`, saying to add it. The Kotlin framework can be static or dynamic.
2. **A Kotlin test on iOS that loads a model links the runtime itself,** because Xcode does not build the test binary. Without the two lines in [`docs/ios-tests.md`](docs/ios-tests.md) the test links, and the load fails with `Internal`.
3. **`minSdk` 24, or 26 with `litertlm-genai-nano` or Koog.** ML Kit's artifacts declare 26, so at 24, which the KMP wizard writes, that artifact fails the manifest merger. Koog brings Jackson, which fails when it is dexed below 26. The phones here run Android 16 and 17.
4. **Kotlin 2.3.0 or newer.** Kotlin/Native 2.2 refuses these klibs (ABI 2.3.0), and on Android it cannot read the Kotlin standard library that Google's runtime brings.
5. **Java 21 for the JVM target.** `litertlm-jvm` is class file version 65.
6. **Each Android ABI adds its own native code:** about 23 MB for `arm64-v8a` and about 27 MB for `x86_64`. `abiFilters` or an App Bundle keeps one. Every Android phone in [`docs/tested-devices.md`](docs/tested-devices.md) is `arm64-v8a`.
7. **The iOS simulator runs the CPU backend.** `Backend.GPU()` fails there with `BackendUnavailable`, and a task on the GPU with `NOT_SUPPORTED` (16).
8. **Do not bundle or commit the model.** Gemma 4 E2B is 2,588,147,712 bytes. The first request downloads it, and `downloadFeature` shows progress. The default directory is left out of backups. Keep the screen on until it ends: on a Pixel 8a with Android 16 the download failed 14 s after the screen went off, when Android stopped the app's network. The next request goes on from where it stopped.
9. **Gemini Nano needs `litertlm-genai-nano` in the app, at the same version as `litertlm-genai`.** Without it, `Auto` uses the local model and says why in `activeProviderReason`, and an explicit `ModelProvider.GeminiNano` is `UNAVAILABLE` and fails with `NOT_AVAILABLE` (8), with the dependency line in the message. The package `genai.spi` is between those two artifacts and is not for apps.
10. **Gemini Nano is foreground only.** An explicit `ModelProvider.GeminiNano` fails with `BACKGROUND_USE_BLOCKED` (30) in the background. `Auto` moves to the local model if it is on the device, and does not start a download for it. The local model can stop there too: on a Pixel 8a with another app on top, Android froze the app 10 to 70 s later in 6 of 6 runs, and the request went on only after the app came back.
11. **A feature Nano has not fetched blocks.** An explicit `GeminiNano` waits for the download: on a phone without a network, one request failed after 260 s. `Auto` uses a local model that is on the device instead.
12. **`Auto` does not switch on error code 11.** On a Galaxy S26, `nano-v3` stopped one text with `[ErrorCode 11]` when it was streamed. Handle it as a failure.
13. **Keep one client per screen, and close it.** Clients with the same file and settings share one loaded model. A client per request loads the model each time: on a Pixel 8a the first GPU load took 30.6 s.
14. **One model in memory.** The task clients keep one loaded model between them and take turns: a client with other settings replaces it, and the open client's next request loads it again. An `Engine` you make is outside that. On an iPhone 18 Pro without entitlements a process has about 7,800 MiB of address space and one load of Gemma 4 E2B maps up to 5,305 MiB, so close what is loaded before you load a second model. `initialize()` throws `InsufficientMemory` under 1.4 times the file's size free, or 2 times with a `visionBackend`. To keep two loaded, sign with `com.apple.developer.kernel.extended-virtual-addressing`: about 67,000 MiB on that phone.
15. **`Engine.close()` and `Conversation.close()` block until a running reply has stopped.** Call them off the main thread if a reply may be running. A task client's `close()` does not block.
16. **Leave the memory check on, on iOS.** `initialize()` throws `InsufficientMemory` below half the file's size free, or three quarters with a `visionBackend`, and also under the address space in rule 14. Without the check an iPhone 18 Pro killed the app during the load at 298 MiB free on the GPU, and a load past the address space ended the process with `std::bad_alloc`. Nothing can catch either.
17. **Scale a photo before passing it.** About 1536 pixels on the long side, upright, as JPEG or PNG. A 48 MP photo at full size took an iPhone 18 Pro with 1,906 MiB free down to 37 to 39 MiB on the CPU. See `readPhoto` and `jpegForModel` in the sample.
18. **Count the cache as well as the model on disk.** On an iPhone 18 Pro the first load of Gemma 4 E2B added about 0.8 GB for text and about 1 GB with images, per `maxNumTokens` on the GPU.
19. **Summaries of an article need at least 400 characters**, or `REQUEST_TOO_SMALL` (-100).
20. **The Maven group has a hyphen and the Kotlin package does not:** `io.github.john-rocky.litertlm`, `io.github.johnrocky.litertlm`.
21. **In a project from the KMP wizard, the dependencies go in `shared/build.gradle.kts`, and `androidApp` does not see the library's types.** Importing one there fails with `Unresolved reference 'io'`. Call your functions in `shared`, or write `api` in place of `implementation`.
22. **Take the text from the result, not from the streamed pieces.** On a Pixel 11 Pro with `nano-v4-full`, the pieces of a three-bullet summary lacked a word, an apostrophe or a line break in 8 of 75 runs. In the one run traced, ML Kit's callback had already lost the line break. The result was whole in all of them.
23. **Numbers stay with their device and date.** Do not extrapolate from [`docs/tested-devices.md`](docs/tested-devices.md) to other phones, and do not report simulator speed as device speed.

## Not this package

- Swift apps, Apple's Foundation Models API → [swift-litert-lm](https://github.com/john-rocky/swift-litert-lm).
- An Android-only app with models from Hugging Face → [hfmodels-android](https://github.com/john-rocky/hfmodels-android).
- Converting a model to `.litertlm` → [hf-to-litertlm](https://github.com/john-rocky/hf-to-litertlm).

Maintainer: john-rocky (GitHub). Issues: https://github.com/john-rocky/litert-lm-kmp/issues
