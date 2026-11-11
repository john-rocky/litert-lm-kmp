# Who answers

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

Gemini Nano has limits of its own. [`docs/tested-devices.md`](tested-devices.md#who-answered) has what the phones did.

- **Foreground only.** In the background, an explicit `GeminiNano` fails with `BACKGROUND_USE_BLOCKED`, and `Auto` uses the local model if it is on the device. The local model can stop there too: on a Pixel 8a with another app on top, Android froze the app 10 to 70 s later in 6 of 6 runs, and the request went on only after the app came back.
- **A feature not yet fetched.** An explicit `GeminiNano` waits for the download. On a Pixel 11 Pro without a network, one request failed after 260 s.
- **Error code 11.** Nano can stop a streamed text with `[ErrorCode 11]`, from a policy check. `Auto` does not switch on this error, so handle it as a failure.
- **No API key.** Requests go through AICore on the phone. ML Kit's documentation says the API does not work with an unlocked bootloader. Its [device list](https://developers.google.com/ml-kit/genai) says which phones have Nano.
