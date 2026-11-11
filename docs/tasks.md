# Tasks

Each task client works the same way: `getClient`, `runInference`, `close`. `Generation` gives you a `GenerativeModel`, which calls `generateContent` instead. Put this in `commonMain`, in a file named `Tasks.kt`:

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
- `getClient` takes an optional last argument, the [provider](who-answers.md).
- Common code passes images as bytes. On Android, `builder(bitmap)`, `ImagePart(bitmap)`, `ImagePart(uri)` and `content { image(bitmap) }` also work.
- Pass a photo at about 1536 pixels on its long side, as an upright JPEG or PNG. An iPhone 18 Pro with 1,906 MiB free before the load went down to 37 to 39 MiB on the CPU with a 48 MP photo at full size, close to the point where iOS kills the app (three runs, none killed), and to 281 MiB with the same photo at 1536 x 1152 (one run). `readPhoto` and `jpegForModel` in the sample do this on Android and iOS.
- The prompt API has no structured output, caches or tools. Tools are in the [lower level](lower-level.md).
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
