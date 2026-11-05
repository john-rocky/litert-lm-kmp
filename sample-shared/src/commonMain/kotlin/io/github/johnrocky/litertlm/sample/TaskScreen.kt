@file:OptIn(ExperimentalLayoutApi::class)

package io.github.johnrocky.litertlm.sample

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.FeatureStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.LocalModel
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriberOptions
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescription
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionRequest
import io.github.johnrocky.litertlm.genai.prompt.Generation
import io.github.johnrocky.litertlm.genai.prompt.TextPart
import io.github.johnrocky.litertlm.genai.prompt.generateContentRequest
import io.github.johnrocky.litertlm.genai.proofreading.Proofreading
import io.github.johnrocky.litertlm.genai.proofreading.ProofreaderOptions
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingRequest
import io.github.johnrocky.litertlm.genai.rewriting.Rewriting
import io.github.johnrocky.litertlm.genai.rewriting.RewriterOptions
import io.github.johnrocky.litertlm.genai.rewriting.RewritingRequest
import io.github.johnrocky.litertlm.genai.summarization.Summarization
import io.github.johnrocky.litertlm.genai.summarization.SummarizationRequest
import io.github.johnrocky.litertlm.genai.summarization.SummarizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class Task(val title: String, val sample: String) {
    SUMMARIZE("Summarize", ARTICLE_TEXT),
    PROOFREAD("Proofread", PROOFREAD_TEXT),
    REWRITE("Rewrite", STYLE_TEXT),
    DESCRIBE("Describe image", ""),
    PROMPT("Prompt", "Name three uses for a paperclip."),
}

private enum class ProviderChoice(val title: String) {
    AUTO("Auto"),
    GEMINI_NANO("Gemini Nano"),
    LITERT_LM("LiteRT-LM"),
}

private val LANGUAGES = listOf(
    "English" to ProofreaderOptions.Language.ENGLISH,
    "Japanese" to ProofreaderOptions.Language.JAPANESE,
    "Korean" to ProofreaderOptions.Language.KOREAN,
    "German" to ProofreaderOptions.Language.GERMAN,
    "French" to ProofreaderOptions.Language.FRENCH,
    "Italian" to ProofreaderOptions.Language.ITALIAN,
    "Spanish" to ProofreaderOptions.Language.SPANISH,
)

private val STYLES = listOf(
    "Elaborate" to RewriterOptions.OutputType.ELABORATE,
    "Emojify" to RewriterOptions.OutputType.EMOJIFY,
    "Shorten" to RewriterOptions.OutputType.SHORTEN,
    "Friendly" to RewriterOptions.OutputType.FRIENDLY,
    "Professional" to RewriterOptions.OutputType.PROFESSIONAL,
    "Rephrase" to RewriterOptions.OutputType.REPHRASE,
)

/**
 * What the launch arguments ask the screen to run by itself.
 *
 * @property task `summarize`, `proofread`, `rewrite`, `describe` or `prompt`.
 * @property modelPath The model, or `null` for the screen's default.
 * @property provider `auto`, `nano` or `litertlm`.
 * @property stopAfterChars Presses Stop once the answer has this many characters.
 * @property useGpu The backend of the local model.
 */
class TaskDemo(
    val task: String,
    val modelPath: String?,
    val provider: String,
    val stopAfterChars: Int? = null,
    val useGpu: Boolean = gpuByDefault,
)

/** What the five clients have in common, so the screen needs one way of running them. */
private class TaskCall(
    val client: AutoCloseable,
    val status: suspend () -> Int,
    val download: suspend (DownloadCallback) -> Unit,
    val baseName: suspend () -> String,
    val reason: () -> String?,
    val run: suspend (StreamingCallback) -> Pair<String, ActiveProvider>,
)

/** The settings on the screen. */
private class Settings(
    val inputType: Int,
    val bullets: Int,
    val voice: Boolean,
    val style: Int,
    val language: Int,
)

private fun callFor(task: Task, settings: Settings, input: String, image: ByteArray, provider: ModelProvider): TaskCall =
    when (task) {
        Task.SUMMARIZE -> {
            val client = Summarization.getClient(
                SummarizerOptions.builder()
                    .setInputType(settings.inputType)
                    .setOutputType(settings.bullets)
                    .setLanguage(settings.language.coerceAtMost(SummarizerOptions.Language.KOREAN))
                    .setLongInputAutoTruncationEnabled(true)
                    .build(),
                provider,
            )
            TaskCall(client, client::checkFeatureStatus, client::downloadFeature, client::getBaseModelName, { client.activeProviderReason }) {
                val result = client.runInference(SummarizationRequest.builder(input).build(), it)
                result.summary to result.activeProvider
            }
        }
        Task.PROOFREAD -> {
            val client = Proofreading.getClient(
                ProofreaderOptions.builder()
                    .setInputType(if (settings.voice) ProofreaderOptions.InputType.VOICE else ProofreaderOptions.InputType.KEYBOARD)
                    .setLanguage(settings.language)
                    .build(),
                provider,
            )
            TaskCall(client, client::checkFeatureStatus, client::downloadFeature, client::getBaseModelName, { client.activeProviderReason }) {
                val result = client.runInference(ProofreadingRequest.builder(input).build(), it)
                result.results.joinToString("\n") { suggestion -> suggestion.text } to result.activeProvider
            }
        }
        Task.REWRITE -> {
            val client = Rewriting.getClient(
                RewriterOptions.builder().setOutputType(settings.style).setLanguage(settings.language).build(),
                provider,
            )
            TaskCall(client, client::checkFeatureStatus, client::downloadFeature, client::getBaseModelName, { client.activeProviderReason }) {
                val result = client.runInference(RewritingRequest.builder(input).build(), it)
                result.results.joinToString("\n") { suggestion -> suggestion.text } to result.activeProvider
            }
        }
        Task.DESCRIBE -> {
            val client = ImageDescription.getClient(ImageDescriberOptions.builder().build(), provider)
            TaskCall(client, client::checkFeatureStatus, client::downloadFeature, client::getBaseModelName, { client.activeProviderReason }) {
                val result = client.runInference(ImageDescriptionRequest.builder(image).build(), it)
                result.description to result.activeProvider
            }
        }
        Task.PROMPT -> {
            val client = Generation.getClient(provider)
            val download: suspend (DownloadCallback) -> Unit = { callback ->
                client.download().collect { step ->
                    when (step) {
                        is io.github.johnrocky.litertlm.genai.common.DownloadStatus.DownloadStarted ->
                            callback.onDownloadStarted(step.bytesToDownload)
                        is io.github.johnrocky.litertlm.genai.common.DownloadStatus.DownloadProgress ->
                            callback.onDownloadProgress(step.totalBytesDownloaded)
                        is io.github.johnrocky.litertlm.genai.common.DownloadStatus.DownloadFailed ->
                            callback.onDownloadFailed(step.e)
                        io.github.johnrocky.litertlm.genai.common.DownloadStatus.DownloadCompleted ->
                            callback.onDownloadCompleted()
                    }
                }
            }
            TaskCall(client, client::checkStatus, download, client::getBaseModelName, { client.activeProviderReason }) {
                val response = client.generateContent(generateContentRequest(TextPart(input)) { temperature = 0.7f }, it)
                response.candidates.first().text to response.activeProvider
            }
        }
    }

private const val STOPPED = "Stopped."

/** What the screen says about a failure. */
private fun describe(error: GenAiException): String = when (error.errorCode) {
    ErrorCode.REQUEST_TOO_LARGE -> "That text does not fit in this model's context window. Shorten it."
    ErrorCode.CANCELLED -> STOPPED
    ErrorCode.BACKGROUND_USE_BLOCKED -> "Gemini Nano runs only while this app is in the foreground."
    ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "This app used up its Gemini Nano quota for now. Try again later."
    ErrorCode.BUSY -> "Gemini Nano is busy. Try again in a moment."
    else -> error.message ?: "It failed with error code ${error.errorCode}."
}

private fun statusName(status: Int?): String = when (status) {
    FeatureStatus.AVAILABLE -> "ready"
    FeatureStatus.DOWNLOADABLE -> "needs a download"
    FeatureStatus.DOWNLOADING -> "downloading"
    FeatureStatus.UNAVAILABLE -> "not available"
    else -> "checking"
}

/** A row of chips that picks one of [choices]. */
@Composable
private fun <T> Chips(choices: List<Pair<String, T>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (title, value) ->
            FilterChip(selected = value == selected, enabled = enabled, onClick = { onSelect(value) }, label = { Text(title) })
        }
    }
}

/**
 * The tasks of `:litertlm-genai`, on whichever provider is chosen. A client is made for each run and closed when the next
 * one is there, so the model stays loaded between runs. Leaving the screen closes the last one.
 */
@Composable
fun TaskScreen(modelsDir: String, cacheDir: String, downloadEndpoint: String = HUGGING_FACE, demo: TaskDemo? = null) {
    val scope = rememberCoroutineScope()
    val defaultPath = "$modelsDir/$GEMMA_FILE_NAME"
    var task by remember { mutableStateOf(Task.entries.firstOrNull { it.name.equals(demo?.task, ignoreCase = true) } ?: Task.SUMMARIZE) }
    var providerChoice by remember {
        mutableStateOf(
            when (demo?.provider) {
                null, "auto" -> ProviderChoice.AUTO
                "nano" -> ProviderChoice.GEMINI_NANO
                else -> ProviderChoice.LITERT_LM
            },
        )
    }
    var modelPath by remember { mutableStateOf(demo?.modelPath ?: defaultPath) }
    var useGpu by remember { mutableStateOf(demo?.useGpu ?: gpuByDefault) }
    var inputType by remember { mutableStateOf(SummarizerOptions.InputType.ARTICLE) }
    var bullets by remember { mutableStateOf(SummarizerOptions.OutputType.THREE_BULLETS) }
    var voice by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(RewriterOptions.OutputType.FRIENDLY) }
    var language by remember { mutableStateOf(ProofreaderOptions.Language.ENGLISH) }
    val inputs = remember { mutableStateMapOf<Task, String>() }
    var image by remember { mutableStateOf(catJpeg()) }
    var status by remember { mutableStateOf<Int?>(null) }
    var answer by remember { mutableStateOf("") }
    var answeredBy by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var kept by remember { mutableStateOf<AutoCloseable?>(null) }
    val running = job != null

    val scroll = rememberScrollState()
    val input = inputs[task] ?: task.sample
    val picker = rememberImagePicker { image = it }

    fun newCall(): TaskCall {
        val path = modelPath.trim()
        val model = if (path == defaultPath) {
            LocalModel.huggingFace(GEMMA_REPO, GEMMA_FILE_NAME, directory = modelsDir, endpoint = downloadEndpoint)
        } else {
            LocalModel.file(path)
        }
        val backend = if (useGpu) Backend.GPU() else Backend.CPU()
        val provider = when (providerChoice) {
            ProviderChoice.AUTO -> ModelProvider.Auto(model, backend, cacheDir = cacheDir)
            ProviderChoice.GEMINI_NANO -> ModelProvider.GeminiNano
            ProviderChoice.LITERT_LM -> ModelProvider.LiteRtLm(model, backend, cacheDir = cacheDir)
        }
        val call = callFor(task, Settings(inputType, bullets, voice, style, language), input, image, provider)
        kept.let {
            kept = call.client
            it?.close()
        }
        return call
    }

    /** Runs the download with its progress on the screen. Returns whether the model is there now. */
    suspend fun download(call: TaskCall): Boolean {
        var total = 0L
        var failed = false
        progress = 0f
        status = FeatureStatus.DOWNLOADING
        call.download(
            object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {
                    total = bytesToDownload
                }

                override fun onDownloadProgress(totalBytesDownloaded: Long) {
                    if (total > 0) progress = totalBytesDownloaded.toFloat() / total
                }

                override fun onDownloadFailed(e: GenAiException) {
                    failed = true
                    failure = describe(e)
                }
            },
        )
        progress = null
        status = call.status()
        return !failed
    }

    fun start(block: suspend () -> Unit) {
        failure = null
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                failure = STOPPED
                throw e
            } catch (e: GenAiException) {
                failure = describe(e)
            } finally {
                progress = null
                job = null
            }
        }
    }

    fun run() = start {
        answer = ""
        answeredBy = null
        val call = newCall()
        status = call.status()
        // Without this the first request would download too, but without progress on the screen.
        if ((status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) && !download(call)) return@start
        val (text, provider) = call.run(StreamingCallback { answer += it })
        answer = text
        // Before the status: on Auto, asking for it chooses again, and the reason would no longer be this answer's.
        val name = if (provider == ActiveProvider.GEMINI_NANO) "Gemini Nano" else "LiteRT-LM"
        answeredBy = "Answered by $name (${call.baseName()}). ${call.reason().orEmpty()}"
        status = call.status()
    }

    DisposableEffect(Unit) {
        onDispose { kept?.close() }
    }
    LaunchedEffect(task, providerChoice, modelPath, useGpu) {
        if (!running) status = runCatching { newCall().status() }.getOrNull()
    }
    LaunchedEffect(Unit) {
        if (demo != null) run()
    }
    LaunchedEffect(answer) {
        demo?.stopAfterChars?.let { if (answer.length >= it) job?.cancel() }
    }
    // The answer comes under the input, which on a phone is below the fold.
    LaunchedEffect(answer, answeredBy, failure) {
        if (answer.isEmpty() && failure == null) return@LaunchedEffect
        withFrameNanos { } // The layout of the new text comes first.
        scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chips(Task.entries.map { it.title to it }, task, !running) {
            task = it
            answer = ""
            answeredBy = null
            failure = null
        }
        Chips(ProviderChoice.entries.map { it.title to it }, providerChoice, !running) { providerChoice = it }
        if (providerChoice != ProviderChoice.GEMINI_NANO) {
            OutlinedTextField(
                value = modelPath,
                onValueChange = { modelPath = it },
                enabled = !running,
                label = { Text("Local model file") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Chips(listOf("CPU" to false, "GPU" to true), useGpu, !running) { useGpu = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val percent = progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
            Text("Model: ${statusName(status)}$percent", style = MaterialTheme.typography.bodyMedium)
            if (status == FeatureStatus.DOWNLOADABLE) {
                OutlinedButton(onClick = { start { download(newCall()) } }, enabled = !running) { Text("Download") }
            }
        }
        progress?.let { fraction ->
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }

        when (task) {
            Task.SUMMARIZE -> {
                Chips(
                    listOf("Article" to SummarizerOptions.InputType.ARTICLE, "Conversation" to SummarizerOptions.InputType.CONVERSATION),
                    inputType,
                    !running,
                ) {
                    if (it != inputType) {
                        inputs[Task.SUMMARIZE] = if (it == SummarizerOptions.InputType.CONVERSATION) CONVERSATION_TEXT else ARTICLE_TEXT
                    }
                    inputType = it
                }
                Chips(
                    listOf(
                        "1 bullet" to SummarizerOptions.OutputType.ONE_BULLET,
                        "2 bullets" to SummarizerOptions.OutputType.TWO_BULLETS,
                        "3 bullets" to SummarizerOptions.OutputType.THREE_BULLETS,
                    ),
                    bullets,
                    !running,
                ) { bullets = it }
                Chips(LANGUAGES.take(3), language.coerceAtMost(SummarizerOptions.Language.KOREAN), !running) { language = it }
            }
            Task.PROOFREAD -> {
                Chips(listOf("Typed" to false, "Spoken" to true), voice, !running) { voice = it }
                Chips(LANGUAGES, language, !running) { language = it }
            }
            Task.REWRITE -> {
                Chips(STYLES, style, !running) { style = it }
                Chips(LANGUAGES, language, !running) { language = it }
            }
            Task.DESCRIBE -> {
                remember(image) { decodeImage(image) }?.let { Image(it, "The image to describe", Modifier.heightIn(max = 180.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    picker?.let { OutlinedButton(onClick = it, enabled = !running) { Text("Choose a photo") } }
                    OutlinedButton(onClick = { image = catJpeg() }, enabled = !running) { Text("Use the cat") }
                }
            }
            Task.PROMPT -> Unit
        }

        if (task != Task.DESCRIBE) {
            OutlinedTextField(
                value = input,
                onValueChange = { inputs[task] = it },
                enabled = !running,
                label = { Text("Input") },
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
            )
        }
        if (running) {
            Button(onClick = { job?.cancel() }) { Text("Stop") }
        } else {
            Button(onClick = { run() }) { Text("Run") }
        }
        failure?.let {
            // Stop is what the person asked for, so it does not read as an error.
            Text(it, color = if (it == STOPPED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        if (answer.isNotEmpty()) Text(answer)
        answeredBy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}
