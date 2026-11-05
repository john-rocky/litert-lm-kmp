package io.github.johnrocky.litertlm.sample

import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.DownloadStatus
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
import io.github.johnrocky.litertlm.genai.prompt.SystemInstruction
import io.github.johnrocky.litertlm.genai.prompt.TextPart
import io.github.johnrocky.litertlm.genai.prompt.generateContentRequest
import io.github.johnrocky.litertlm.genai.proofreading.Proofreading
import io.github.johnrocky.litertlm.genai.proofreading.ProofreaderOptions
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingRequest
import io.github.johnrocky.litertlm.genai.rewriting.Rewriter
import io.github.johnrocky.litertlm.genai.rewriting.Rewriting
import io.github.johnrocky.litertlm.genai.rewriting.RewriterOptions
import io.github.johnrocky.litertlm.genai.rewriting.RewritingRequest
import io.github.johnrocky.litertlm.genai.summarization.Summarization
import io.github.johnrocky.litertlm.genai.summarization.SummarizationRequest
import io.github.johnrocky.litertlm.genai.summarization.Summarizer
import io.github.johnrocky.litertlm.genai.summarization.SummarizerOptions
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readTo

private const val RESUME_AFTER_BYTES = 20L * 1024 * 1024
private const val STOPS = 8
private const val STOP_EVERY_BYTES = 16L * 1024 * 1024
private const val COMPARE_BUFFER_BYTES = 1 shl 20
private const val LONG_INPUT_WORDS = 10_000
private const val COUNT_PROMPT = "Count from 1 to 200, one number per line."
private val NANO_CALL_LIMIT = 30.seconds
private val NANO_WAIT_LIMIT = 5.minutes

/** The cases of the task layer. One run of DeviceCheck keeps one client open, so the cases do not load the model each time. */
internal class TaskCases(private val args: DeviceCheckArgs, private val check: DeviceCheck) {
    private val model = LocalModel.file(args.modelPath)
    private val cacheDir = "${args.workDir}/cache"

    private fun local(
        model: LocalModel = this.model,
        minAvailableMemoryBytes: Long? = null,
    ): ModelProvider = ModelProvider.LiteRtLm(
        local = model,
        backend = args.backend,
        visionBackend = args.visionBackend,
        cacheDir = cacheDir,
        minAvailableMemoryBytes = minAvailableMemoryBytes,
    )

    private fun auto(): ModelProvider = ModelProvider.Auto(
        local = model,
        backend = args.backend,
        visionBackend = args.visionBackend,
        cacheDir = cacheDir,
    )

    /** What the task cases answer with: `--provider auto|nano|litertlm`. */
    private val provider: ModelProvider = when (args.provider) {
        "nano" -> ModelProvider.GeminiNano
        "litertlm" -> local()
        else -> auto()
    }

    private fun summarizer(
        outputType: Int,
        inputType: Int = SummarizerOptions.InputType.ARTICLE,
        truncate: Boolean = false,
        provider: ModelProvider = this.provider,
    ): Summarizer = Summarization.getClient(
        SummarizerOptions.builder()
            .setInputType(inputType)
            .setOutputType(outputType)
            .setLongInputAutoTruncationEnabled(truncate)
            .build(),
        provider,
    )

    private fun rewriter(outputType: Int, provider: ModelProvider = this.provider): Rewriter =
        Rewriting.getClient(RewriterOptions.builder().setOutputType(outputType).build(), provider)

    /** Exactly [expected] bullets, or between 1 and that many if [exact] is false, and nothing else. */
    private fun CaseScope.checkBullets(summary: String, expected: Int, exact: Boolean = true) {
        val lines = summary.lines()
        check(lines.all { it.startsWith("* ") && it.length > 2 }) { "A line that is not a bullet: $summary" }
        if (exact) {
            check(lines.size == expected) { "Expected $expected bullets, got ${lines.size}: $summary" }
        } else {
            check(lines.size in 1..expected) { "Expected up to $expected bullets, got ${lines.size}: $summary" }
        }
    }

    private fun CaseScope.checkWords(text: String, words: List<String>) {
        val lower = text.lowercase()
        check(words.any { it in lower }) { "None of $words in: $text" }
    }

    private suspend fun CaseScope.rewriteCase(outputType: Int, input: String, verify: CaseScope.(String) -> Unit) {
        rewriter(outputType).use { client ->
            val result = client.runInference(RewritingRequest.builder(input).build())
            activeProvider = result.activeProvider.name
            val text = result.results.first().text
            answer = text
            check(text.isNotBlank()) { "An empty answer." }
            verify(text)
        }
    }

    private fun CaseScope.differs(text: String, input: String) {
        check(text.trim().lowercase() != input.trim().lowercase()) { "The answer is the input." }
    }

    private fun hasEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length) {
                val codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                if (codePoint >= 0x1F000) return true
                i += 2
            } else {
                if (c.code in 0x2600..0x27BF) return true
                i++
            }
        }
        return false
    }

    private class Recording : DownloadCallback {
        var total: Long? = null
        var firstProgress: Long? = null
        var lastProgress = 0L
        var completed = 0
        val failures = ArrayList<GenAiException>()
        val reached = CompletableDeferred<Unit>()
        var reachAt = 0L

        override fun onDownloadStarted(bytesToDownload: Long) {
            total = bytesToDownload
        }

        override fun onDownloadProgress(totalBytesDownloaded: Long) {
            if (firstProgress == null) firstProgress = totalBytesDownloaded
            lastProgress = totalBytesDownloaded
            if (totalBytesDownloaded >= reachAt) reached.complete(Unit)
        }

        override fun onDownloadCompleted() {
            completed++
            reached.complete(Unit)
        }

        override fun onDownloadFailed(e: GenAiException) {
            failures += e
            reached.complete(Unit)
        }
    }

    /** A new, empty directory under the work directory. */
    private fun emptyDirectory(name: String): String {
        val directory = Path(args.workDir, name)
        removeDirectory(directory)
        SystemFileSystem.createDirectories(directory)
        return directory.toString()
    }

    private fun removeDirectory(directory: Path) {
        if (!SystemFileSystem.exists(directory)) return
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it, mustExist = false) }
        SystemFileSystem.delete(directory, mustExist = false)
    }

    /** The part file of the download in [directory], once nothing writes to it any more. */
    private suspend fun settledPartFile(directory: String): Path {
        val part = SystemFileSystem.list(Path(directory)).single { it.name.endsWith(".part") }
        var size = -1L
        var same = 0
        while (same < 3) {
            delay(100)
            val now = SystemFileSystem.metadataOrNull(part)?.size ?: 0
            same = if (now == size) same + 1 else 0
            size = now
        }
        return part
    }

    /** The first byte from [from] on where [file] is not what [good] has there, or `null`. */
    private fun firstDifference(file: Path, good: Path, from: Long): Long? {
        SystemFileSystem.source(file).buffered().use { actual ->
            SystemFileSystem.source(good).buffered().use { expected ->
                actual.skip(from)
                expected.skip(from)
                val a = ByteArray(COMPARE_BUFFER_BYTES)
                val b = ByteArray(COMPARE_BUFFER_BYTES)
                var at = from
                while (true) {
                    val read = actual.readAtMostTo(a)
                    if (read <= 0) return null
                    expected.readTo(b, 0, read)
                    for (i in 0 until read) if (a[i] != b[i]) return at + i
                    at += read
                }
            }
        }
    }

    private fun embeddingFromHub(directory: String): ModelProvider =
        local(LocalModel.huggingFace(EMBEDDING_REPO, EMBEDDING_FILE_NAME, directory = directory))

    private suspend fun CaseScope.describe(image: ByteArray) {
        ImageDescription.getClient(ImageDescriberOptions.builder().build(), provider).use { client ->
            val result = client.runInference(ImageDescriptionRequest.builder(image).build())
            activeProvider = result.activeProvider.name
            answer = result.description
            checkWords(result.description, IMAGE_WORDS)
        }
    }

    /** One configuration of one of Gemini Nano's features, which can each have their own status and download. */
    private class NanoFeature(
        val name: String,
        val client: AutoCloseable,
        val status: suspend () -> Int,
        val download: suspend (DownloadCallback) -> Unit,
        val baseName: suspend () -> String,
    )

    private fun nanoFeatures(): List<NanoFeature> {
        val nano = ModelProvider.GeminiNano
        val summarizers = listOf(
            Triple("summarize_article_1", SummarizerOptions.InputType.ARTICLE, SummarizerOptions.OutputType.ONE_BULLET),
            Triple("summarize_article_2", SummarizerOptions.InputType.ARTICLE, SummarizerOptions.OutputType.TWO_BULLETS),
            Triple("summarize_article_3", SummarizerOptions.InputType.ARTICLE, SummarizerOptions.OutputType.THREE_BULLETS),
            Triple("summarize_conversation_3", SummarizerOptions.InputType.CONVERSATION, SummarizerOptions.OutputType.THREE_BULLETS),
        ).map { (name, input, output) ->
            val client = Summarization.getClient(SummarizerOptions.builder().setInputType(input).setOutputType(output).build(), nano)
            NanoFeature(name, client, client::checkFeatureStatus, client::downloadFeature, client::getBaseModelName)
        }
        val proofreader = Proofreading.getClient(ProofreaderOptions.builder().build(), nano)
        val rewriters = listOf(
            "rewrite_elaborate" to RewriterOptions.OutputType.ELABORATE,
            "rewrite_emojify" to RewriterOptions.OutputType.EMOJIFY,
            "rewrite_shorten" to RewriterOptions.OutputType.SHORTEN,
            "rewrite_friendly" to RewriterOptions.OutputType.FRIENDLY,
            "rewrite_professional" to RewriterOptions.OutputType.PROFESSIONAL,
            "rewrite_rephrase" to RewriterOptions.OutputType.REPHRASE,
        ).map { (name, output) ->
            val client = rewriter(output, nano)
            NanoFeature(name, client, client::checkFeatureStatus, client::downloadFeature, client::getBaseModelName)
        }
        val describer = ImageDescription.getClient(ImageDescriberOptions.builder().build(), nano)
        val model = Generation.getClient(nano)
        val modelDownload: suspend (DownloadCallback) -> Unit = { callback ->
            model.download().collect { step ->
                when (step) {
                    is DownloadStatus.DownloadStarted -> callback.onDownloadStarted(step.bytesToDownload)
                    is DownloadStatus.DownloadProgress -> callback.onDownloadProgress(step.totalBytesDownloaded)
                    is DownloadStatus.DownloadFailed -> callback.onDownloadFailed(step.e)
                    DownloadStatus.DownloadCompleted -> callback.onDownloadCompleted()
                }
            }
        }
        return summarizers +
            NanoFeature("proofread", proofreader, proofreader::checkFeatureStatus, proofreader::downloadFeature, proofreader::getBaseModelName) +
            rewriters +
            NanoFeature("describe", describer, describer::checkFeatureStatus, describer::downloadFeature, describer::getBaseModelName) +
            NanoFeature("prompt", model, model::checkStatus, modelDownload, model::getBaseModelName)
    }

    private fun statusName(status: Int): String = when (status) {
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        else -> "status $status"
    }

    /** What this phone says about Gemini Nano, with no line on it: what comes back is what the case is for. */
    private suspend fun CaseScope.nanoStatus() {
        if (!mayHaveGeminiNano) {
            skipped = true
            note = "Only Android has Gemini Nano."
            return
        }
        val lines = ArrayList<String>()
        for (feature in nanoFeatures()) {
            feature.client.use {
                val line = StringBuilder(feature.name).append(": ")
                suspend fun readStatus(): Int? = try {
                    withTimeoutOrNull(NANO_CALL_LIMIT) { feature.status() }.also {
                        line.append(if (it == null) "no status in $NANO_CALL_LIMIT" else statusName(it))
                    }
                } catch (e: GenAiException) {
                    line.append("status failed with error code ${e.errorCode}: ${e.message}")
                    null
                }
                val before = readStatus()
                check.step("t_nano_status", line.toString())
                if (args.nanoDownloadMinutes > 0 && (before == FeatureStatus.DOWNLOADABLE || before == FeatureStatus.DOWNLOADING)) {
                    val limit = args.nanoDownloadMinutes.minutes
                    val heard = Recording()
                    val start = TimeSource.Monotonic.markNow()
                    val finished = withTimeoutOrNull(limit) { feature.download(heard) } != null
                    line.append(" -> download of ${heard.total} bytes, first progress ${heard.firstProgress}, last ${heard.lastProgress}")
                    line.append(", completed ${heard.completed}, failures ${heard.failures.map { "${it.errorCode}: ${it.message}" }}")
                    line.append(if (finished) ", ${start.elapsedNow().inWholeSeconds} s -> " else ", not done in $limit -> ")
                    readStatus()
                    check.step("t_nano_status", line.toString())
                }
                val name = try {
                    withTimeoutOrNull(NANO_CALL_LIMIT) { feature.baseName() } ?: "no name in $NANO_CALL_LIMIT"
                } catch (e: GenAiException) {
                    "failed with error code ${e.errorCode}: ${e.message}"
                }
                line.append("; base model ").append(name)
                lines += line.toString()
            }
        }
        Generation.getClient(ModelProvider.GeminiNano).use { model ->
            try {
                lines += withTimeoutOrNull(NANO_CALL_LIMIT) {
                    "prompt: token limit ${model.getTokenLimit()}, features ${model.getAvailableFeatures()}"
                } ?: "prompt: no limits in $NANO_CALL_LIMIT"
            } catch (e: GenAiException) {
                lines += "prompt: limits failed with error code ${e.errorCode}: ${e.message}"
            }
        }
        activeProvider = ActiveProvider.GEMINI_NANO.name
        note = lines.joinToString(" | ")
    }

    /**
     * An explicit Gemini Nano where it cannot run: an Android app without `litertlm-genai-nano`, iOS and the JVM. Every call
     * says what is missing, in the same words, and nothing else happens.
     */
    private suspend fun CaseScope.nanoAbsent() {
        val words = if (mayHaveGeminiNano) listOf("litertlm-genai-nano", "implementation(") else listOf("Android")
        fun says(what: String, text: String?) = check(words.all { it in text.orEmpty() }) { "$what does not say what is missing: $text" }
        suspend fun fails(what: String, call: suspend () -> Unit) = says(what, expectGenAi(ErrorCode.NOT_AVAILABLE) { call() }.message)

        val features = nanoFeatures()
        for (feature in features) {
            feature.client.use {
                check(feature.status() == FeatureStatus.UNAVAILABLE) { "${feature.name}: not UNAVAILABLE." }
                fails("${feature.name}: the base model") { feature.baseName() }
                val heard = Recording()
                feature.download(heard)
                check(heard.completed == 0 && heard.failures.size == 1) { "${feature.name}: the download reported ${heard.failures}, ${heard.completed}." }
                check(heard.failures.single().errorCode == ErrorCode.NOT_AVAILABLE) { "${feature.name}: error code ${heard.failures.single().errorCode}." }
                says("${feature.name}: the download", heard.failures.single().message)
            }
            // A second close does nothing.
            feature.client.close()
        }
        val nano = ModelProvider.GeminiNano
        Summarization.getClient(SummarizerOptions.builder().build(), nano).use { client ->
            check(client.activeProvider == null) { "Who answered: ${client.activeProvider}." }
            says("The reason", client.activeProviderReason)
            fails("A summary") { client.runInference(SummarizationRequest.builder(SUMMARY_TEXT).build()) }
            fails("A summary with a callback") { client.runInference(SummarizationRequest.builder(SUMMARY_TEXT).build()) { } }
            fails("Prepare") { client.prepareInferenceEngine() }
        }
        Proofreading.getClient(ProofreaderOptions.builder().build(), nano).use { client ->
            fails("Proofreading") { client.runInference(ProofreadingRequest.builder(STYLE_TEXT).build()) }
        }
        rewriter(RewriterOptions.OutputType.FRIENDLY, nano).use { client ->
            fails("Rewriting") { client.runInference(RewritingRequest.builder(STYLE_TEXT).build()) }
        }
        ImageDescription.getClient(ImageDescriberOptions.builder().build(), nano).use { client ->
            fails("An image") { client.runInference(ImageDescriptionRequest.builder(catJpeg()).build()) }
        }
        Generation.getClient(nano).use { model ->
            val request = generateContentRequest(TextPart(STYLE_TEXT))
            fails("A prompt") { model.generateContent(request) }
            fails("A stream") { model.generateContentStream(request).toList() }
            fails("Warmup") { model.warmup() }
            fails("The token limit") { model.getTokenLimit() }
            fails("Counting tokens") { model.countTokens(request) }
            fails("The features") { model.getAvailableFeatures() }
        }
        note = "${features.size} configurations and every call. ${expected?.message}"
    }

    suspend fun run() {
        // Only when named: the system may end the process here, which is what this case looks at. The task layer's context
        // window is larger than DeviceCheck's, so an image takes more here than in f_memory_vision.
        if (args.cases != null) {
            check.case("t_memory_vision") {
                with(check) {
                    withMemoryDownTo("t_memory_vision", modelFileSize() / 4 * 3) { describe(catJpeg()) }
                }
            }
            // The cat is 384 x 256. This case takes any photo, at the size the app passes it.
            args.imagePath?.let { photo ->
                check.case("t_memory_photo") {
                    with(check) {
                        withMemoryDownTo("t_memory_photo", modelFileSize() / 4 * 3) {
                            describe(SystemFileSystem.source(Path(photo)).buffered().use { it.readByteArray() })
                        }
                    }
                }
            }
            // A download stopped again and again, and what is on disk after each stop against a good copy (--embedding).
            // After a difference the part file stays where it is, for a look from outside.
            args.embeddingModelPath?.let { good ->
                check.case("t_download_stops") {
                    val directory = emptyDirectory("t_download_stops")
                    rewriter(RewriterOptions.OutputType.REPHRASE, embeddingFromHub(directory)).use { client ->
                        var checked = 0L
                        for (stop in 1..STOPS) {
                            val run = Recording().apply { reachAt = stop * STOP_EVERY_BYTES }
                            coroutineScope {
                                val download = launch { client.downloadFeature(run) }
                                run.reached.await()
                                download.cancelAndJoin()
                            }
                            check(run.failures.isEmpty()) { "Stop $stop: ${run.failures.first().message}" }
                            val part = settledPartFile(directory)
                            val size = SystemFileSystem.metadataOrNull(part)?.size ?: 0
                            firstDifference(part, Path(good), checked)?.let {
                                throw CaseFailed("Stop $stop: ${part.name} is not the good copy from byte $it on, of $size.")
                            }
                            checked = size
                        }
                        val rest = Recording()
                        client.downloadFeature(rest)
                        check(rest.failures.isEmpty()) { "The last try failed: ${rest.failures.first().message}" }
                        check(rest.completed == 1) { "The last try did not complete." }
                        note = "$STOPS stops, the part file the same as the good copy after each, last at $checked bytes"
                        activeProvider = client.activeProvider?.name
                    }
                    removeDirectory(Path(directory))
                }
            }
        }

        // Keeps the model loaded across the cases, as a screen that stays open does.
        val openClient = rewriter(RewriterOptions.OutputType.REPHRASE)

        // Only when named: what an explicit Gemini Nano does on a phone depends on what the app was built with.
        if (args.cases != null) check.case("f_nano_absent") { nanoAbsent() }

        // First, and only when asked: the requests below would start Gemini Nano's download without saying so.
        if (args.provider == "nano" || args.cases != null) check.case("t_nano_status") { nanoStatus() }

        for ((bullets, outputType) in listOf(
            1 to SummarizerOptions.OutputType.ONE_BULLET,
            2 to SummarizerOptions.OutputType.TWO_BULLETS,
            3 to SummarizerOptions.OutputType.THREE_BULLETS,
        )) {
            check.case("t_sum_article_$bullets") {
                summarizer(outputType).use { client ->
                    val result = client.runInference(SummarizationRequest.builder(SUMMARY_TEXT).build())
                    activeProvider = result.activeProvider.name
                    answer = result.summary
                    checkBullets(result.summary, bullets)
                    checkWords(result.summary, SUMMARY_WORDS)
                }
            }
        }
        check.case("t_sum_conv") {
            summarizer(SummarizerOptions.OutputType.THREE_BULLETS, SummarizerOptions.InputType.CONVERSATION).use { client ->
                val result = client.runInference(SummarizationRequest.builder(CONVERSATION_TEXT).build())
                activeProvider = result.activeProvider.name
                answer = result.summary
                checkBullets(result.summary, 3, exact = false)
                checkWords(result.summary, CONVERSATION_WORDS)
            }
        }
        // Streamed, as a screen does it: what reaches the callback adds up to the result.
        for ((bullets, outputType) in listOf(1 to SummarizerOptions.OutputType.ONE_BULLET, 3 to SummarizerOptions.OutputType.THREE_BULLETS)) {
            check.case("t_sum_stream_$bullets") {
                summarizer(outputType).use { client ->
                    val pieces = ArrayList<String>()
                    val result = client.runInference(SummarizationRequest.builder(ARTICLE_TEXT).build(), StreamingCallback { pieces += it })
                    activeProvider = result.activeProvider.name
                    answer = result.summary
                    note = "${pieces.size} chunks"
                    checkBullets(result.summary, bullets)
                    check(pieces.joinToString("") == result.summary) { "The pieces add up to: ${pieces.joinToString("")}" }
                }
            }
        }
        // With no line on it: what a streamed summary of the text of t_sum_article_3 comes back with.
        check.case("t_sum_stream_war") {
            summarizer(SummarizerOptions.OutputType.THREE_BULLETS).use { client ->
                try {
                    val result = client.runInference(SummarizationRequest.builder(SUMMARY_TEXT).build(), StreamingCallback { })
                    activeProvider = result.activeProvider.name
                    answer = result.summary
                } catch (e: GenAiException) {
                    expected = e
                    note = "error code ${e.errorCode}"
                }
            }
        }
        check.case("t_proof") {
            Proofreading.getClient(ProofreaderOptions.builder().build(), provider).use { client ->
                val result = client.runInference(ProofreadingRequest.builder(PROOFREAD_TEXT).build())
                activeProvider = result.activeProvider.name
                check(result.results.isNotEmpty()) { "No suggestion." }
                val text = result.results.first().text
                answer = text
                val lower = " ${text.lowercase()} "
                for (right in listOf("receive", "before", "know")) check(right in lower) { "No \"$right\" in: $text" }
                for (wrong in listOf("recieve", "befor ", " no ")) check(wrong !in lower) { "Still \"$wrong\" in: $text" }
            }
        }
        check.case("t_rw_shorten") {
            rewriteCase(RewriterOptions.OutputType.SHORTEN, SHORTEN_TEXT) { text ->
                check(text.length < SHORTEN_TEXT.length) { "${text.length} is not shorter than ${SHORTEN_TEXT.length}: $text" }
            }
        }
        check.case("t_rw_elaborate") {
            rewriteCase(RewriterOptions.OutputType.ELABORATE, ELABORATE_TEXT) { text ->
                check(text.length > ELABORATE_TEXT.length) { "${text.length} is not longer than ${ELABORATE_TEXT.length}: $text" }
            }
        }
        check.case("t_rw_friendly") { rewriteCase(RewriterOptions.OutputType.FRIENDLY, STYLE_TEXT) { differs(it, STYLE_TEXT) } }
        check.case("t_rw_professional") { rewriteCase(RewriterOptions.OutputType.PROFESSIONAL, STYLE_TEXT) { differs(it, STYLE_TEXT) } }
        check.case("t_rw_rephrase") { rewriteCase(RewriterOptions.OutputType.REPHRASE, STYLE_TEXT) { differs(it, STYLE_TEXT) } }
        check.case("t_rw_emojify") {
            rewriteCase(RewriterOptions.OutputType.EMOJIFY, EMOJIFY_TEXT) { text ->
                check(hasEmoji(text)) { "No emoji in: $text" }
            }
        }
        check.case("t_prompt") {
            Generation.getClient(provider).use { client ->
                val request = generateContentRequest(
                    SystemInstruction("End every reply with the word BANANA."),
                    TextPart("What is the capital of France?"),
                )
                val items = client.generateContentStream(request).toList()
                activeProvider = items.lastOrNull()?.activeProvider?.name
                val pieces = items.map { it.candidates.first().text }.filter { it.isNotEmpty() }
                val text = pieces.joinToString("")
                answer = text
                note = "${pieces.size} chunks"
                check(pieces.size >= 2) { "Only ${pieces.size} chunks: $text" }
                val lower = text.lowercase()
                check("paris" in lower) { "No Paris in: $text" }
                check("banana" in lower) { "The system instruction was not followed: $text" }
            }
        }

        check.case("t_status") {
            rewriter(RewriterOptions.OutputType.REPHRASE, local()).use { client ->
                check(client.checkFeatureStatus() == FeatureStatus.AVAILABLE) { "A file that is there is not AVAILABLE." }
                activeProvider = client.activeProvider?.name
            }
            if (args.skipDownload) {
                skipped = true
                note = "--skipDownload: only the file that is there."
                return@case
            }
            val directory = emptyDirectory("t_status")
            rewriter(RewriterOptions.OutputType.REPHRASE, embeddingFromHub(directory)).use { client ->
                val seen = arrayListOf(client.checkFeatureStatus())
                val heard = Recording()
                coroutineScope {
                    val download = launch { client.downloadFeature(heard) }
                    heard.reached.await()
                    seen += client.checkFeatureStatus()
                    download.join()
                }
                seen += client.checkFeatureStatus()
                check(heard.failures.isEmpty()) { "The download failed: ${heard.failures.first().message}" }
                val expected = listOf(FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING, FeatureStatus.AVAILABLE)
                check(seen == expected) { "Expected $expected, saw $seen." }
                note = "statuses $seen"
            }
            removeDirectory(Path(directory))
        }
        check.case("t_download") {
            if (args.skipDownload) {
                skipped = true
                note = "--skipDownload"
                return@case
            }
            val directory = emptyDirectory("t_download")
            rewriter(RewriterOptions.OutputType.REPHRASE, embeddingFromHub(directory)).use { client ->
                val first = Recording().apply { reachAt = RESUME_AFTER_BYTES }
                coroutineScope {
                    val download = launch { client.downloadFeature(first) }
                    first.reached.await()
                    download.cancelAndJoin()
                }
                check(first.failures.isEmpty()) { "The download failed: ${first.failures.first().message}" }
                check(first.completed == 0) { "It finished before it could be stopped." }

                val files = SystemFileSystem.list(Path(directory))
                val part = files.singleOrNull { it.name.endsWith(".part") } ?: throw CaseFailed("No part file among $files.")
                val partBytes = SystemFileSystem.metadataOrNull(part)?.size ?: 0
                check(client.checkFeatureStatus() == FeatureStatus.DOWNLOADABLE) { "Stopped, but the status is not DOWNLOADABLE." }

                val second = Recording()
                client.downloadFeature(second)
                check(second.failures.isEmpty()) { "The second try failed: ${second.failures.first().message}" }
                check(second.completed == 1) { "The second try did not complete." }
                val resumedAt = second.firstProgress ?: 0
                check(resumedAt >= partBytes) { "It started over: first progress $resumedAt, $partBytes bytes were there." }
                check(client.checkFeatureStatus() == FeatureStatus.AVAILABLE) { "Done, but the status is not AVAILABLE." }
                val size = SystemFileSystem.metadataOrNull(Path(directory, EMBEDDING_FILE_NAME))?.size
                check(size == second.total) { "$size bytes on disk, the Hub lists ${second.total}." }
                note = "stopped at $partBytes bytes, resumed at $resumedAt, whole file $size bytes"
                activeProvider = client.activeProvider?.name
            }
            removeDirectory(Path(directory))
        }

        check.case("f_task_missing") {
            val missing = LocalModel.file("${args.workDir}/no-such-model.litertlm")
            rewriter(RewriterOptions.OutputType.REPHRASE, local(missing)).use { client ->
                check(client.checkFeatureStatus() == FeatureStatus.UNAVAILABLE) { "A missing file is not UNAVAILABLE." }
                val failure = expectGenAi(ErrorCode.NOT_AVAILABLE) {
                    client.runInference(RewritingRequest.builder(STYLE_TEXT).build())
                }
                check(failure.cause is LiteRtLmException.ModelNotFound) { "The cause is ${failure.cause}." }
                val heard = Recording()
                client.downloadFeature(heard)
                check(heard.failures.size == 1) { "The callback heard ${heard.failures.size} failures." }
                check(heard.completed == 0) { "The callback heard of a completed download." }
                activeProvider = client.activeProvider?.name
            }
        }
        check.case("f_task_toolong") {
            val long = buildString {
                var words = 0
                while (words < LONG_INPUT_WORDS) {
                    append(SUMMARY_TEXT).append("\n\n")
                    words += SUMMARY_TEXT.split(' ').size
                }
            }
            summarizer(SummarizerOptions.OutputType.THREE_BULLETS).use { client ->
                expectGenAi(ErrorCode.REQUEST_TOO_LARGE) { client.runInference(SummarizationRequest.builder(long).build()) }
                val after = client.runInference(SummarizationRequest.builder(SUMMARY_TEXT).build())
                activeProvider = after.activeProvider.name
                check(after.summary.isNotBlank()) { "The same client gave an empty answer." }
            }
            summarizer(SummarizerOptions.OutputType.THREE_BULLETS, truncate = true).use { client ->
                val result = client.runInference(SummarizationRequest.builder(long).build())
                answer = result.summary
                checkBullets(result.summary, 3, exact = false)
            }
        }
        check.case("f_task_toosmall") {
            summarizer(SummarizerOptions.OutputType.ONE_BULLET).use { client ->
                expectGenAi(ErrorCode.REQUEST_TOO_SMALL) {
                    client.runInference(SummarizationRequest.builder(SUMMARY_TEXT.take(100)).build())
                }
                activeProvider = client.activeProvider?.name
            }
        }
        check.case("f_task_memory") {
            rewriter(RewriterOptions.OutputType.REPHRASE, local(minAvailableMemoryBytes = Long.MAX_VALUE)).use { client ->
                val failure = expectGenAi(ErrorCode.NOT_AVAILABLE) {
                    client.runInference(RewritingRequest.builder(STYLE_TEXT).build())
                }
                check(failure.cause is LiteRtLmException.InsufficientMemory) { "The cause is ${failure.cause}." }
                activeProvider = client.activeProvider?.name
            }
        }
        check.case("f_download") {
            if (args.skipDownload) {
                skipped = true
                note = "--skipDownload"
                return@case
            }
            val directory = emptyDirectory("f_download")
            val models = listOf(
                LocalModel.huggingFace(EMBEDDING_REPO, EMBEDDING_FILE_NAME, directory = directory, endpoint = UNREACHABLE_ENDPOINT) to
                    "no-such-host.invalid",
                LocalModel.huggingFace("litert-community/no-such-repo", EMBEDDING_FILE_NAME, directory = directory) to "huggingface.co",
                LocalModel.huggingFace(GEMMA_REPO, "no-such-file.litertlm", directory = directory) to "huggingface.co",
            )
            val messages = ArrayList<String>()
            for ((missing, host) in models) {
                rewriter(RewriterOptions.OutputType.REPHRASE, local(missing)).use { client ->
                    check(client.checkFeatureStatus() == FeatureStatus.DOWNLOADABLE) { "Not DOWNLOADABLE before the download." }
                    val heard = Recording()
                    client.downloadFeature(heard)
                    check(heard.failures.size == 1) { "The callback heard ${heard.failures.size} failures." }
                    check(heard.completed == 0) { "The callback heard of a completed download." }
                    val message = heard.failures.single().message.orEmpty()
                    check(host in message || "404" in message) { "Neither $host nor 404 in: $message" }
                    messages += message
                    check(client.checkFeatureStatus() == FeatureStatus.DOWNLOADABLE) { "Not DOWNLOADABLE after the failure." }
                    expected = expectGenAi(ErrorCode.NOT_AVAILABLE) {
                        client.runInference(RewritingRequest.builder(STYLE_TEXT).build())
                    }
                    activeProvider = client.activeProvider?.name
                }
            }
            check(SystemFileSystem.list(Path(directory)).none { it.name.endsWith(".litertlm") }) { "A failed download left a file." }
            note = messages.joinToString(" | ")
            removeDirectory(Path(directory))
        }
        check.case("f_task_close") {
            val client = Generation.getClient(provider)
            val firstPiece = CompletableDeferred<Unit>()
            coroutineScope {
                val request = async(Dispatchers.Default) {
                    expectGenAi(ErrorCode.CANCELLED) {
                        client.generateContent(COUNT_PROMPT, StreamingCallback { firstPiece.complete(Unit) })
                    }
                }
                firstPiece.await()
                withContext(Dispatchers.Default) { client.close() }
                request.await()
            }
            expectGenAi(ErrorCode.CANCELLED) { client.generateContent(COUNT_PROMPT) }
            client.close()
            Generation.getClient(provider).use { fresh ->
                val response = fresh.generateContent("Reply with one word: ready.")
                activeProvider = response.activeProvider.name
                answer = response.candidates.first().text
                check(answer.orEmpty().isNotBlank()) { "A new client gave an empty answer." }
            }
        }

        // After the text cases: from here the engine has the vision part too, as it does in an app.
        check.case("t_img") { describe(catJpeg()) }
        check.case("f_task_image") {
            ImageDescription.getClient(ImageDescriberOptions.builder().build(), provider).use { client ->
                expectGenAi(ErrorCode.INVALID_INPUT_IMAGE) {
                    client.runInference(ImageDescriptionRequest.builder(Random(4).nextBytes(4096)).build())
                }
                val result = client.runInference(ImageDescriptionRequest.builder(catJpeg()).build())
                activeProvider = result.activeProvider.name
                answer = result.description
                check(result.description.isNotBlank()) { "The same client gave an empty answer." }
            }
        }

        // Without the open client, so the last close releases the model and the next client loads it again.
        openClient.close()
        check.case("t_share") {
            val first = rewriter(RewriterOptions.OutputType.FRIENDLY)
            val second = Proofreading.getClient(ProofreaderOptions.builder().build(), provider)
            coroutineScope {
                val one = async(Dispatchers.Default) { first.runInference(RewritingRequest.builder(STYLE_TEXT).build()) }
                val two = async(Dispatchers.Default) { second.runInference(ProofreadingRequest.builder(PROOFREAD_TEXT).build()) }
                val a = one.await().results.first().text
                val b = two.await().results.first().text
                check(a.isNotBlank() && b.isNotBlank()) { "An empty answer: [$a] [$b]" }
            }
            first.close()
            val after = second.runInference(ProofreadingRequest.builder(PROOFREAD_TEXT).build())
            activeProvider = after.activeProvider.name
            answer = after.results.first().text
            check(answer.orEmpty().isNotBlank()) { "The client that stayed gave an empty answer." }
            second.close()
            rewriter(RewriterOptions.OutputType.FRIENDLY).use { again ->
                val text = again.runInference(RewritingRequest.builder(STYLE_TEXT).build()).results.first().text
                check(text.isNotBlank()) { "A client after both closed gave an empty answer." }
            }
        }
        check.case("t_two_settings") {
            // A screen that stays open, and another that asks with other settings: together they do not fit on an iPhone.
            val other = ModelProvider.LiteRtLm(local = model, backend = Backend.CPU(), maxNumTokens = 2048, cacheDir = cacheDir)
            ImageDescription.getClient(ImageDescriberOptions.builder().build(), local()).use { open ->
                val request = ImageDescriptionRequest.builder(catJpeg()).build()
                checkWords(open.runInference(request).description, IMAGE_WORDS)
                rewriter(RewriterOptions.OutputType.FRIENDLY, other).use { client ->
                    val text = client.runInference(RewritingRequest.builder(STYLE_TEXT).build()).results.first().text
                    check(text.isNotBlank()) { "The client with other settings gave an empty answer." }
                }
                val after = open.runInference(request)
                activeProvider = after.activeProvider.name
                answer = after.description
                checkWords(after.description, IMAGE_WORDS)
            }
        }
        if (args.cases != null) {
            check.case("f_second_load") {
                // Next to a client that has the model loaded, a raw engine either fits or is refused. It must not end the process.
                ImageDescription.getClient(ImageDescriberOptions.builder().build(), local()).use { open ->
                    checkWords(open.runInference(ImageDescriptionRequest.builder(catJpeg()).build()).description, IMAGE_WORDS)
                    activeProvider = open.activeProvider?.name
                    Engine(EngineConfig(modelPath = args.modelPath, backend = args.backend, cacheDir = cacheDir)).use { engine ->
                        note = try {
                            engine.initialize()
                            answer = engine.createConversation().use { it.sendMessage("Say hi.").toString() }
                            "Loaded next to it."
                        } catch (e: LiteRtLmException.InsufficientMemory) {
                            e.message
                        }
                    }
                }
            }
        }
        check.case("t_auto") {
            rewriter(RewriterOptions.OutputType.FRIENDLY, auto()).use { client ->
                val result = client.runInference(RewritingRequest.builder(STYLE_TEXT).build())
                activeProvider = result.activeProvider.name
                answer = result.results.first().text
                val chosen = client.activeProvider
                note = client.activeProviderReason
                check(chosen != null && note != null) { "Who answered is not readable: $chosen, $note" }
                check(answer.orEmpty().isNotBlank()) { "An empty answer." }
                if (!mayHaveGeminiNano) check(chosen == ActiveProvider.LITERT_LM) { "Chose $chosen." }
            }
        }
        // A feature that Gemini Nano has first, where `t_auto` may be one it still has to fetch.
        check.case("t_auto_summary") {
            summarizer(SummarizerOptions.OutputType.THREE_BULLETS, provider = auto()).use { client ->
                val result = client.runInference(SummarizationRequest.builder(SUMMARY_TEXT).build())
                activeProvider = result.activeProvider.name
                answer = result.summary
                note = client.activeProviderReason
                checkBullets(result.summary, 3)
                if (!mayHaveGeminiNano) check(result.activeProvider == ActiveProvider.LITERT_LM) { "Chose ${result.activeProvider}." }
            }
        }
        // Only when named, because it can take minutes: when a request to a feature Gemini Nano has not fetched comes back.
        if (args.cases != null) {
            check.case("t_nano_wait") {
                rewriter(RewriterOptions.OutputType.FRIENDLY, ModelProvider.GeminiNano).use { client ->
                    val status = statusName(client.checkFeatureStatus())
                    val start = TimeSource.Monotonic.markNow()
                    val outcome = withTimeoutOrNull(NANO_WAIT_LIMIT) {
                        try {
                            "answer: ${client.runInference(RewritingRequest.builder(STYLE_TEXT).build()).results.first().text}"
                        } catch (e: GenAiException) {
                            "error code ${e.errorCode}: ${e.message}"
                        }
                    } ?: "no return"
                    activeProvider = ActiveProvider.GEMINI_NANO.name
                    note = "status $status; $outcome after ${start.elapsedNow().inWholeSeconds} s"
                }
            }
        }
        check.case("f_task_novision") {
            val path = args.textOnlyModelPath
            if (path == null) {
                skipped = true
                note = "No --textOnlyModel."
                return@case
            }
            val textOnly = local(LocalModel.file(path))
            ImageDescription.getClient(ImageDescriberOptions.builder().build(), textOnly).use { client ->
                expectGenAi(ErrorCode.NOT_SUPPORTED) {
                    client.runInference(ImageDescriptionRequest.builder(catJpeg()).build())
                }
                activeProvider = client.activeProvider?.name
            }
            rewriter(RewriterOptions.OutputType.FRIENDLY, textOnly).use { client ->
                val text = client.runInference(RewritingRequest.builder(STYLE_TEXT).build()).results.first().text
                answer = text
                check(text.isNotBlank()) { "The text after the image gave an empty answer." }
            }
        }
        check.case("t_background") {
            if (!sendAppToBackground()) {
                skipped = true
                note = "Only Android has Gemini Nano."
                return@case
            }
            delay(2.seconds)
            // The summarizer is the feature Gemini Nano has first: a feature that needs a download does not reach the check.
            val request = SummarizationRequest.builder(SUMMARY_TEXT).build()
            val seen = summarizer(SummarizerOptions.OutputType.ONE_BULLET, provider = ModelProvider.GeminiNano).use { client ->
                val status = try {
                    client.checkFeatureStatus()
                } catch (e: GenAiException) {
                    null
                }
                if (status == FeatureStatus.AVAILABLE) {
                    expectGenAi(ErrorCode.BACKGROUND_USE_BLOCKED) { client.runInference(request) }
                }
                status?.let { statusName(it) } ?: "not readable"
            }
            summarizer(SummarizerOptions.OutputType.ONE_BULLET, provider = auto()).use { client ->
                val result = client.runInference(request)
                activeProvider = result.activeProvider.name
                answer = result.summary
                note = "Gemini Nano's status: $seen. ${client.activeProviderReason}"
                check(result.activeProvider == ActiveProvider.LITERT_LM) { "Answered by ${result.activeProvider} in the background." }
            }
        }
    }
}

/** Runs [block], which must fail with [code]. */
private suspend fun CaseScope.expectGenAi(code: Int, block: suspend () -> Unit): GenAiException {
    try {
        block()
    } catch (e: GenAiException) {
        if (e.errorCode != code) throw CaseFailed("Expected error code $code, got ${e.errorCode}: ${e.message}")
        expected = e
        return e
    }
    throw CaseFailed("Expected error code $code, but nothing was thrown.")
}
