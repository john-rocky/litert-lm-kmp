# Choose the model

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

The backend is the CPU unless you say otherwise. The GPU is Metal on iOS, OpenCL on Android and WebGPU on the JVM on a Mac. A three-bullet summary of the Gettysburg Address, with the model loaded:

| Phone | GPU | CPU |
|---|---|---|
| Pixel 11 Pro | 4.2 s | 10.9 s |
| Pixel 8a | 5.2 s | 9.3 s |
| iPhone 18 Pro | 0.9 s | 2.7 s |

The first load on the GPU has no cache yet: 20.6 s on the Pixel 11 Pro and 30.6 s on the Pixel 8a, against 1.9 s and 6.3 s on the CPU.

That first load also writes a cache, so plan for more disk than the file. On an iPhone 18 Pro, Gemma 4 E2B added about 0.8 GB for text and about 1 GB with images. On the GPU the cache is per `maxNumTokens` (4608 by default): 2048 and 4608 in the same directory took 1,616 MB. A second load with the same settings added nothing.
