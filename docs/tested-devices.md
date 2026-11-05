# Tested devices

What ran on real devices, and what came back. Numbers belong to the device, its OS build, the runtime version and the date, and other phones will differ.

Every row used LiteRT-LM 0.19.0, Gemma 4 E2B (`gemma-4-E2B-it.litertlm`, 2,588,147,712 bytes) and EmbeddingGemma (`embeddinggemma-2-text-270m.litertlm`, 164,626,432 bytes). The runs of Gemini Nano also used `litertlm-genai-nano`, with ML Kit `genai-summarization`, `genai-proofreading`, `genai-rewriting` and `genai-image-description` 1.0.0-beta1, and `genai-prompt` and `genai-common` 1.0.0-beta5. The Android phones' bootloaders were locked, and no API key was set anywhere.

## Results

Counts are of the checks in [`DeviceCheck`](../sample-shared/src/commonMain/kotlin/io/github/johnrocky/litertlm/sample/DeviceCheck.kt), on 2026-11-05, on the code of this release.

| Device | SoC, GPU | OS | Provider, backend | Build | Passed | Failed | Skipped |
|---|---|---|---|---|---|---|---|
| Pixel 11 Pro | Tensor G6, PowerVR | Android 17, CD1A.260905.001.B1 | LiteRT-LM, GPU | debug, three launches | 51 | 0 | 5 |
| Pixel 11 Pro | | | LiteRT-LM, CPU | debug, two launches | 51 | 0 | 5 |
| Pixel 11 Pro | | | Gemini Nano `nano-v4-full`, summarization | debug | 13 | 0 | 0 |
| Pixel 11 Pro | | | LiteRT-LM, GPU | release, R8 | 10 | 0 | 0 |
| Pixel 11 Pro | | | Gemini Nano | release, R8 | 3 | 0 | 0 |
| Pixel 8a | Tensor G3, Mali | Android 16, CP1A.260505.005 | LiteRT-LM, GPU | debug | 57 | 0 | 0 |
| Pixel 8a | | | LiteRT-LM, CPU | debug | 8 | 0 | 0 |
| Pixel 8a | | | `Auto`, no Gemini Nano on this phone | debug | 27 | 0 | 4 |
| Pixel 8a | | | `Auto`, in an app without `litertlm-genai-nano` | debug | 11 | 0 | 0 |
| Pixel 8a | | | `Auto`, in an app without `litertlm-genai-nano` | release, R8 | 11 | 0 | 0 |
| Galaxy S26 (SM-S942Q) | SM8850, Adreno | Android 16, BP4A.251205.006 | Gemini Nano `nano-v3`, every task | debug | 25 | 0 | 0 |
| Galaxy S26 | | | Gemini Nano | release, R8 | 7 | 0 | 0 |
| iPhone 18 Pro (iPhone19,2) | | iOS 27.2, 24B5099f | LiteRT-LM, GPU (Metal) | debug | 56 | 0 | 1 |
| iPhone 18 Pro | | | LiteRT-LM, CPU | debug | 56 | 0 | 1 |
| iPhone 18 Pro | | | LiteRT-LM, GPU (Metal) | release | 56 | 0 | 1 |
| iPhone 18 Pro | | | LiteRT-LM, CPU | release | 56 | 0 | 1 |
| Mac | Apple M4 Max | macOS 27.0, JVM | LiteRT-LM, CPU | | 55 | 0 | 2 |

The iPhone skips the one check that goes to the background, because iOS has no Gemini Nano. The Pixel 11 Pro had no network, so the download checks were skipped there, and Gemini Nano could not fetch its other features: proofreading, rewriting, image description and prompts on `nano-v4-full` have not run. The same checks ran on `nano-v3` on the Galaxy S26. The JVM skips the checks that fill the memory and that go to the background.

On the Pixel 11 Pro, the check that fills the memory ran in a launch of its own on each backend, and on the GPU so did the check with a text-only model. Android 17 killed a process that stayed over 8 GiB, and closing that model left almost no memory for the next check. So on that phone, one launch of the command below can be killed before it ends. `--es cases a,b,c` runs the checks you name.

One check can fail on the Pixel 11 Pro when it is repeated. `t_sum_stream_3` asks that the streamed pieces of a three-bullet summary add up to the result. On `nano-v4-full` the pieces lacked a word, an apostrophe or a line break in 8 of 75 runs, in debug and release builds. In the one run traced, ML Kit's callback had already lost the line break. The result was whole in all of them. The one-bullet `t_sum_stream_1` did not fail in 71 runs. Apps should take the text from the result.

## What the checks cover

| Group | Checks |
|---|---|
| Chat | Load, three turns in one conversation, a load after a close, a double close |
| Lower-level failures | `ModelNotFound`, `InvalidModel` (directory, garbage, wrong magic, cut short), `InsufficientMemory` (by setting and with the memory really full), `InputTooLong`, `Cancelled`, `BackendUnavailable`, `InvalidImage`, `ClosedUse` |
| Tools, images, embeddings | A tool call and its result, a photo of a cat, one embedding, a GPU load |
| Downloads | An unreachable host, a real file from the Hugging Face Hub, a stop and a resume, a wrong status |
| Tasks | Summaries of one, two and three bullets, of an article and a conversation, with and without streaming. Proofreading, six rewriting styles, prompts, image description |
| Task failures | A missing file, too much text, too little text, too little memory, a close during a request, a bad image, an image on a model without a vision part, a failed download |
| Providers | Two clients sharing one model, a client with other settings next to an open one, `Auto` on each kind of phone, `Auto` and an explicit Gemini Nano in the background, every call of an explicit Gemini Nano in an app without `litertlm-genai-nano` |

An app without the Swift package is a separate check, because the sample has the package. On an iPhone 18 Pro on 2026-11-05, in Debug and Release with static and dynamic Kotlin frameworks, it linked and ran, and loads and tasks failed with `Internal` or `GenAiException`, saying to add the package. An app with the library in its dependencies that never calls it linked and ran too.

## Numbers

A three-bullet summary of the 278-word Gettysburg Address, with the model loaded:

| Device | Provider, backend | Seconds |
|---|---|---|
| Pixel 11 Pro | Gemini Nano | 1.0 |
| Pixel 11 Pro | LiteRT-LM, GPU | 4.2 |
| Pixel 11 Pro | LiteRT-LM, CPU | 10.9 |
| Pixel 8a | LiteRT-LM, GPU | 5.2 |
| Pixel 8a | LiteRT-LM, CPU | 9.3 |
| Galaxy S26 | Gemini Nano | 1.5 |
| iPhone 18 Pro | LiteRT-LM, GPU (Metal) | 0.9 |
| iPhone 18 Pro | LiteRT-LM, CPU | 2.7 |
| Mac, JVM | LiteRT-LM, CPU | 3.5 |

These come from the debug runs in the table above. On the iPhone 18 Pro, in four launches of each, taken in turn, a release and a debug build both took 0.9 s on the GPU. A release build started right after the two debug runs took 1.4 s on the GPU and 3.8 s on the CPU.

The first load, with no cache yet:

| Device | GPU | CPU |
|---|---|---|
| Pixel 11 Pro | 20.6 s | 1.9 s |
| Pixel 8a | 30.6 s | 6.3 s |

Gemma 4 E2B downloaded over Wi-Fi in 143 s on the Pixel 8a, and its SHA-256 matched the Hub's.

With the screen off, the same phone on Android 16 stopped the app's network: `dumpsys netpolicy` showed `blocked=APP_BACKGROUND`, with the app's process state `TPSL`, Android's state for the top app on a sleeping device. In one run on USB power, the download failed with `the host was not found` 14 s after the screen went off, at 605 MB, and did not go on by itself when the screen came back. On iOS this has not been measured.

## Who answered

| Device | Feature | Gemini Nano's status | `Auto` used |
|---|---|---|---|
| Pixel 11 Pro | Summarization | AVAILABLE | Gemini Nano |
| Pixel 11 Pro | Rewriting | DOWNLOADABLE | LiteRT-LM |
| Pixel 8a | Summarization, rewriting | The check fails with error code 606 | LiteRT-LM |
| Galaxy S26 | Summarization, rewriting | AVAILABLE | Gemini Nano |

In the background, an explicit Gemini Nano failed with `[ErrorCode 30] Background usage is blocked` on the Pixel 11 Pro and the Galaxy S26, and `Auto` answered with LiteRT-LM.

On the Pixel 8a, a request to the local model in the background ended in 14 of 14 runs with the phone locked and the model's cache in place. With another app on top, Android froze the app in 6 of 6 runs, about 10 s later in five and 70 s later in one, and the request went on only after the app came back. In the 3 runs that brought it back, it ended without an error 5 to 6 s later. With no cache yet and the phone locked, the same request took 280 s, seen once. In the foreground the first load alone took 30.6 s.

On the Pixel 11 Pro without a network, an explicit Gemini Nano on a feature it had not fetched failed after 260.5 s, seen once. On the Galaxy S26, `nano-v3` stopped one text with `[ErrorCode 11] Generated response doesn't pass certain policy check` each time it was streamed, and passed it without streaming. Other texts streamed. The Pixel 11 Pro's `nano-v4-full` streamed the same text.

## Disk on an iPhone 18 Pro

The cache that the first load of Gemma 4 E2B writes, in MB. The model file is 2,588 more. A second load with the same settings adds nothing.

| What ran | Cache |
|---|---|
| CPU, text | 787 |
| CPU, with an image | 1,010 |
| GPU, text, `maxNumTokens` 2048 | 808 |
| GPU, text, `maxNumTokens` 4608 | 808 |
| GPU, with an image | 979 |
| GPU, text, 2048 and 4608 in the same directory | 1,616 |
| EmbeddingGemma | 71 |

## Memory on an iPhone 18 Pro

| | Available at launch | Lowest during three chat turns | Lowest during one image |
|---|---|---|---|
| With both entitlements | 6,139 MiB | | |
| Without | 3,371 MiB | 2,848 MiB on the CPU, 2,579 MiB on the GPU | 1,839 MiB on the CPU, 2,059 MiB on the GPU |

With the memory check turned off and the memory filled first, the model still loaded at 298 MiB free on the CPU and at 448 MiB free on the GPU. At 148 MiB on the CPU and at 298 MiB on the GPU the system killed the app during the load.

With an image, the check asks for three quarters of the file: 1,851 MiB for Gemma 4 E2B. These runs filled the memory to just above that, with an empty cache. Each one answered, and none was killed:

| Image | API | Backend | Runs | Free before the load, MiB | Lowest free, MiB |
|---|---|---|---|---|---|
| A cat, 384 x 256 | Lower level | GPU | 3 | 1,906 to 1,907 | 626 to 783 |
| A cat, 384 x 256 | Lower level | CPU | 3 | 1,893 to 1,906 | 400 to 403 |
| A cat, 384 x 256 | Task | GPU | 2 | 1,894 to 1,906 | 517 to 675 |
| A cat, 384 x 256 | Task | CPU | 4 | 1,893 to 1,906 | 286 to 289 |
| A 48 MP photo at full size | Task | GPU | 2 | 1,906 to 1,907 | 244 to 295 |
| A 48 MP photo at full size | Task | CPU | 3 | 1,906 | 37 to 39 |
| The same photo at 1536 x 1152 | Task | CPU | 1 | 1,906 | 281 |

Just under the check, at 1,786 to 1,798 MiB free, each of the four cat rows refused the load: `InsufficientMemory` at the lower level, `NOT_AVAILABLE` with the message "Not enough memory to load the model" in a task.

## Address space on an iPhone 18 Pro

A process had about 7,800 MiB of address space free at launch without entitlements, and about 67,000 MiB with `extended-virtual-addressing`. Only blocks of 64 MiB or more were counted. The most that one load of Gemma 4 E2B mapped, from an empty cache:

| Load | MiB |
|---|---|
| CPU, text | 3,640 |
| GPU, text | 4,250 |
| CPU, with an image | 5,305 |
| GPU, with an image | 5,222 |

Fourteen loads and closes in a row did not use it up: on the GPU with an image, 5,824 to 6,592 MiB stayed free after each close, and on the GPU with text, 6,784 to 6,976 MiB.

Two loads did not fit. With an image-description client left open on the CPU, a second load failed with `NOT_FOUND`, `InvalidModel` or `BackendUnavailable` in the task and lower-level APIs, and the console had `Cannot allocate memory`. Each failed load left part of the file mapped, and in one run a later load ended the process with `std::bad_alloc`. So the task clients keep one model in memory, and `initialize()` refuses a load with less address space free than 1.4 times the file's size, or 2 times with a `visionBackend`. That line is under what the loads above mapped, so it refuses no load that fits, and a load just above it can still fail.

## Run the checks on your own device

Get the model files first, from [Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) and [EmbeddingGemma](https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm) on the Hugging Face Hub. Pass the second with `embedding`.

The JVM:

```sh
./gradlew :sample-shared:runDeviceCheckJvm -PdeviceCheckArgs="--model /path/to/gemma-4-E2B-it.litertlm --workdir /tmp/check"
```

Android. The results go to logcat, tag `DEVICECHECK`:

```sh
./gradlew :sample-android:installDebug
adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/io.github.johnrocky.litertlm.sample/files/
adb shell am start -n io.github.johnrocky.litertlm.sample/.MainActivity \
  --es deviceCheck true --es model gemma-4-E2B-it.litertlm --es backend gpu --es suite all
```

iOS. Open `sample-ios/Sample.xcodeproj`, set your team and run it once on the phone, copy the model into the app's Documents, then:

```sh
xcrun devicectl device process launch --console --device <UDID> io.github.johnrocky.litertlm.sample \
  --deviceCheck --model gemma-4-E2B-it.litertlm --backend gpu --suite all
```

The last line of the output is the count of passed, failed and skipped checks.
