package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.Content
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.ConversationConfig
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.SamplerConfig
import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.FeatureStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.LocalModel
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionRequest
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionResult
import io.github.johnrocky.litertlm.genai.prompt.Candidate
import io.github.johnrocky.litertlm.genai.prompt.Feature
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentRequest
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentResponse
import io.github.johnrocky.litertlm.genai.prompt.ImagePart
import io.github.johnrocky.litertlm.genai.prompt.Part
import io.github.johnrocky.litertlm.genai.prompt.SystemInstruction
import io.github.johnrocky.litertlm.genai.prompt.TextPart
import io.github.johnrocky.litertlm.genai.proofreading.ProofreaderOptions
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingRequest
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingResult
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingSuggestion
import io.github.johnrocky.litertlm.genai.rewriting.RewriterOptions
import io.github.johnrocky.litertlm.genai.rewriting.RewritingRequest
import io.github.johnrocky.litertlm.genai.rewriting.RewritingResult
import io.github.johnrocky.litertlm.genai.rewriting.RewritingSuggestion
import io.github.johnrocky.litertlm.genai.summarization.SummarizationRequest
import io.github.johnrocky.litertlm.genai.summarization.SummarizationResult
import io.github.johnrocky.litertlm.genai.summarization.SummarizerOptions
import kotlin.random.Random
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** The input of a summary of an article must be over this, as ML Kit documents. */
private const val MIN_ARTICLE_CHARACTERS = 400

/** How much of the context window a shortened input leaves to the reply. */
private const val OUTPUT_RESERVE_TOKENS = 512

private const val MAX_SHORTENINGS = 4

private const val TOP_P = 0.95

/** The failure of a raw call, as a task reports it. The cause stays: it has the runtime's own words. */
internal fun LiteRtLmException.toGenAiException(): GenAiException = GenAiException(
    this,
    when (this) {
        is LiteRtLmException.ModelNotFound,
        is LiteRtLmException.InvalidModel,
        is LiteRtLmException.InsufficientMemory,
        -> ErrorCode.NOT_AVAILABLE
        is LiteRtLmException.InputTooLong -> ErrorCode.REQUEST_TOO_LARGE
        is LiteRtLmException.Cancelled, is LiteRtLmException.ClosedUse -> ErrorCode.CANCELLED
        is LiteRtLmException.BackendUnavailable -> ErrorCode.NOT_SUPPORTED
        is LiteRtLmException.InvalidImage -> ErrorCode.INVALID_INPUT_IMAGE
        is LiteRtLmException.Internal,
        is LiteRtLmException.NotInitialized,
        is LiteRtLmException.AlreadyInitialized,
        -> ErrorCode.UNKNOWN
    },
)

private val TOO_LONG_NUMBERS = Regex("(\\d+)(?: input tokens)? >= (\\d+)")

/**
 * The start of [text], cut so that it should fit, going by what the runtime said in [failure]: `N >= M` tokens. Half of
 * it if the runtime did not say. Keeps the start, as ML Kit's automatic truncation does.
 */
internal fun shortenedToFit(text: String, failure: String?): String {
    val numbers = failure?.let { TOO_LONG_NUMBERS.find(it) }?.groupValues
    val keep = if (numbers != null) {
        val tokens = numbers[1].toDouble()
        val limit = numbers[2].toDouble()
        val room = (limit - OUTPUT_RESERVE_TOKENS).coerceAtLeast(limit / 2)
        (text.length * room / tokens).toInt()
    } else {
        text.length / 2
    }
    val cut = text.take(keep.coerceIn(1, (text.length - 1).coerceAtLeast(1)))
    val lastSpace = cut.indexOfLast { it.isWhitespace() }
    return if (lastSpace > cut.length / 2) cut.take(lastSpace) else cut
}

/** What a turn wrote. */
internal class TurnOutput(val text: String, val stoppedAtLimit: Boolean)

/** One local model as one client sees it: its file, its download, and its share of the loaded model. */
internal class LocalRunner(
    private val model: LocalModel,
    private val modelPath: String,
    private val cacheDir: String?,
    val maxNumTokens: Int,
    private val engine: SharedEngine,
    private val downloads: ModelDownloads,
) {
    private val released = atomic(false)
    private val target = (model as? LocalModel.HuggingFace)?.let { DownloadTarget(it, modelPath) }

    /** The file name without its extension. */
    val baseModelName: String = modelPath.substringAfterLast('/').substringBeforeLast('.')

    suspend fun status(): Int = withContext(Dispatchers.IO) {
        val path = Path(modelPath)
        when {
            // Not isRegularFile: on iOS it is false for a symbolic link to a file. The load judges what the file is.
            SystemFileSystem.exists(path) && SystemFileSystem.metadataOrNull(path)?.isDirectory != true -> FeatureStatus.AVAILABLE
            target == null -> FeatureStatus.UNAVAILABLE
            downloads.isDownloading(modelPath) -> FeatureStatus.DOWNLOADING
            else -> FeatureStatus.DOWNLOADABLE
        }
    }

    suspend fun download(callback: DownloadCallback) {
        when (status()) {
            FeatureStatus.AVAILABLE -> callback.onDownloadCompleted()
            FeatureStatus.UNAVAILABLE -> callback.onDownloadFailed(
                GenAiException("There is no model file at $modelPath.", null, ErrorCode.NOT_AVAILABLE),
            )
            else -> downloads.follow(target ?: return, callback)
        }
    }

    /** As ML Kit documents: a model that is not there yet is downloaded first. */
    private suspend fun ensureModel() {
        val target = target
        if (target != null && status() != FeatureStatus.AVAILABLE) downloads.ensure(target)
        if (cacheDir != null) withContext(Dispatchers.IO) { SystemFileSystem.createDirectories(Path(cacheDir)) }
    }

    suspend fun prepare(needsVision: Boolean) {
        ensureModel()
        translated { engine.withEngine(needsVision) {} }
    }

    /**
     * One turn in a new conversation, so nothing carries over from the last one. What [filter] lets through goes to
     * [callback] on the caller's context, and together that is the text returned.
     *
     * @param maxChunks Stop after this many pieces of reply, if not 0.
     */
    suspend fun turn(
        message: Message,
        sampler: SamplerConfig = TASK_SAMPLER,
        needsVision: Boolean = false,
        earlier: List<Message> = emptyList(),
        system: Contents? = null,
        filter: OutputFilter = PassThrough,
        callback: StreamingCallback? = null,
        maxChunks: Int = 0,
    ): TurnOutput {
        ensureModel()
        return translated {
            engine.withEngine(needsVision) { loaded ->
                val conversation = loaded.createConversation(
                    ConversationConfig(systemInstruction = system, initialMessages = earlier, samplerConfig = sampler),
                )
                try {
                    val text = StringBuilder()
                    fun deliver(piece: String) {
                        if (piece.isEmpty()) return
                        text.append(piece)
                        callback?.onNewText(piece)
                    }
                    var chunks = 0
                    var stoppedAtLimit = false
                    try {
                        // Leaving the flow stops the model.
                        conversation.sendMessageAsync(message).takeWhile { chunk ->
                            chunks++
                            deliver(filter.accept(chunk.toString()))
                            if (maxChunks in 1..chunks) stoppedAtLimit = true
                            !filter.isDone && !stoppedAtLimit
                        }.collect {}
                    } catch (e: LiteRtLmException.InputTooLong) {
                        // Filled up while writing: the input did fit, so this is a reply that ends early.
                        if (chunks == 0) throw e
                        stoppedAtLimit = true
                    }
                    deliver(filter.finish())
                    TurnOutput(text.toString(), stoppedAtLimit)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { conversation.close() }
                }
            }
        }
    }

    private inline fun <T> translated(block: () -> T): T = try {
        block()
    } catch (e: LiteRtLmException) {
        throw e.toGenAiException()
    }

    /** Does not block. A second call does nothing. */
    fun close() {
        if (released.compareAndSet(expect = false, update = true)) engine.release()
    }

    companion object {
        fun create(
            context: PlatformContext,
            model: LocalModel,
            backend: Backend,
            visionBackend: Backend,
            maxNumTokens: Int,
            cacheDir: String?,
            minAvailableMemoryBytes: Long?,
        ): LocalRunner {
            val path = when (model) {
                is LocalModel.File -> model.path
                is LocalModel.HuggingFace -> {
                    val file = model.file.substringAfterLast('/')
                    val directory = model.directory ?: "${defaultModelsDir(context)}/${model.repo.replace("/", "--")}"
                    "$directory/$file"
                }
            }
            val cache = cacheDir ?: defaultCacheDir(context)
            val config = EngineConfig(
                modelPath = path,
                backend = backend,
                visionBackend = visionBackend,
                maxNumTokens = maxNumTokens,
                cacheDir = cache,
                minAvailableMemoryBytes = minAvailableMemoryBytes,
            )
            return LocalRunner(model, path, cache, maxNumTokens, EnginePool.shared.acquire(config), ModelDownloads.shared)
        }
    }
}

/** What all the local clients have in common. */
internal abstract class LocalClient<Req, Res>(
    protected val runner: LocalRunner,
    private val preparesVision: Boolean = false,
) : FeatureClient<Req, Res> {
    override val activeProvider: ActiveProvider = ActiveProvider.LITERT_LM
    override val activeProviderReason: String = "The app chose LiteRT-LM."

    override suspend fun checkFeatureStatus(): Int = runner.status()

    override suspend fun downloadFeature(callback: DownloadCallback) = runner.download(callback)

    override suspend fun prepareInferenceEngine() = runner.prepare(preparesVision)

    override suspend fun getBaseModelName(): String = runner.baseModelName

    override fun close() = runner.close()
}

private fun nothingWritten(): GenAiException =
    GenAiException("The model wrote nothing usable.", null, ErrorCode.RESPONSE_GENERATION_ERROR)

internal class LocalSummarizer(runner: LocalRunner, private val options: SummarizerOptions) :
    LocalClient<SummarizationRequest, SummarizationResult>(runner) {

    override suspend fun runInference(request: SummarizationRequest, callback: StreamingCallback?): SummarizationResult {
        val tooSmall = when {
            request.text.isBlank() -> "There is no text to summarize."
            options.inputType == SummarizerOptions.InputType.ARTICLE && request.text.length < MIN_ARTICLE_CHARACTERS ->
                "An article must be over $MIN_ARTICLE_CHARACTERS characters, and this one has ${request.text.length}."
            else -> null
        }
        if (tooSmall != null) throw GenAiException(tooSmall, null, ErrorCode.REQUEST_TOO_SMALL)

        var text = request.text
        var shortenings = 0
        while (true) {
            try {
                val output = runner.turn(
                    Message.user(summarizePrompt(text, options.inputType, options.outputType, options.language)),
                    filter = BulletFilter(bulletCount(options.outputType)),
                    callback = callback,
                )
                if (output.text.isEmpty()) throw nothingWritten()
                return SummarizationResult(output.text, ActiveProvider.LITERT_LM)
            } catch (e: GenAiException) {
                // Nothing reached the callback: this failure comes only before the first piece of the reply.
                val mayShorten = options.longInputAutoTruncationEnabled && shortenings < MAX_SHORTENINGS
                if (e.errorCode != ErrorCode.REQUEST_TOO_LARGE || !mayShorten) throw e
                text = shortenedToFit(text, e.message)
                shortenings++
            }
        }
    }
}

internal class LocalProofreader(runner: LocalRunner, private val options: ProofreaderOptions) :
    LocalClient<ProofreadingRequest, ProofreadingResult>(runner) {

    override suspend fun runInference(request: ProofreadingRequest, callback: StreamingCallback?): ProofreadingResult {
        if (request.text.isBlank()) throw GenAiException("There is no text to proofread.", null, ErrorCode.REQUEST_TOO_SMALL)
        val output = runner.turn(
            Message.user(proofreadPrompt(request.text, options.inputType, options.language)),
            filter = ProseFilter(),
            callback = callback,
        )
        if (output.text.isEmpty()) throw nothingWritten()
        return ProofreadingResult(listOf(ProofreadingSuggestion(output.text)), ActiveProvider.LITERT_LM)
    }
}

internal class LocalRewriter(runner: LocalRunner, private val options: RewriterOptions) :
    LocalClient<RewritingRequest, RewritingResult>(runner) {

    override suspend fun runInference(request: RewritingRequest, callback: StreamingCallback?): RewritingResult {
        if (request.text.isBlank()) throw GenAiException("There is no text to rewrite.", null, ErrorCode.REQUEST_TOO_SMALL)
        val output = runner.turn(
            Message.user(rewritePrompt(request.text, options.outputType, options.language)),
            filter = ProseFilter(),
            callback = callback,
        )
        if (output.text.isEmpty()) throw nothingWritten()
        return RewritingResult(listOf(RewritingSuggestion(output.text)), ActiveProvider.LITERT_LM)
    }
}

internal class LocalImageDescriber(runner: LocalRunner) :
    LocalClient<ImageDescriptionRequest, ImageDescriptionResult>(runner, preparesVision = true) {

    override suspend fun runInference(request: ImageDescriptionRequest, callback: StreamingCallback?): ImageDescriptionResult {
        val contents = Contents.of(Content.ImageBytes(request.bytes.value), Content.Text(DESCRIBE_IMAGE_PROMPT))
        val output = runner.turn(Message.user(contents), needsVision = true, filter = ProseFilter(), callback = callback)
        if (output.text.isEmpty()) throw nothingWritten()
        return ImageDescriptionResult(output.text, ActiveProvider.LITERT_LM)
    }
}

internal class LocalGenerativeModel(runner: LocalRunner) :
    LocalClient<GenerateContentRequest, GenerateContentResponse>(runner),
    PromptClient {

    override suspend fun runInference(request: GenerateContentRequest, callback: StreamingCallback?): GenerateContentResponse {
        val last = request.contents.lastOrNull()
        if (last == null || last.parts.isEmpty()) {
            throw GenAiException("The request has no content.", null, ErrorCode.REQUEST_TOO_SMALL)
        }
        if (last.role != io.github.johnrocky.litertlm.genai.prompt.Content.Role.USER) {
            throw GenAiException("The last content of a request must be the user's.", null, ErrorCode.REQUEST_PROCESSING_ERROR)
        }
        val parts = request.contents.flatMap { it.parts }
        val system = request.systemInstruction ?: parts.filterIsInstance<SystemInstruction>().firstOrNull()
        val sampler = try {
            // ML Kit's seed 0 means a different one each time.
            val seed = if (request.seed == 0) Random.nextInt(1, Int.MAX_VALUE) else request.seed
            SamplerConfig(request.topK, TOP_P, request.temperature.toDouble(), seed)
        } catch (e: IllegalArgumentException) {
            throw GenAiException(e.message, e, ErrorCode.REQUEST_PROCESSING_ERROR)
        }
        val output = runner.turn(
            message = Message.user(rawContents(last.parts)),
            sampler = sampler,
            needsVision = parts.any { it is ImagePart },
            earlier = request.contents.dropLast(1).map { content ->
                if (content.role == io.github.johnrocky.litertlm.genai.prompt.Content.Role.USER) {
                    Message.user(rawContents(content.parts))
                } else {
                    Message.model(rawContents(content.parts))
                }
            },
            system = system?.let { Contents.of(it.textString) },
            callback = callback,
            maxChunks = request.maxOutputTokens,
        )
        val reason = if (output.stoppedAtLimit) Candidate.FinishReason.MAX_TOKENS else Candidate.FinishReason.STOP
        return GenerateContentResponse(listOf(Candidate(output.text, reason)), ActiveProvider.LITERT_LM)
    }

    private fun rawContents(parts: List<Part>): Contents = Contents.of(
        parts.mapNotNull { part ->
            when (part) {
                is TextPart -> Content.Text(part.textString)
                is ImagePart -> Content.ImageBytes(part.bytes)
                is SystemInstruction -> null
            }
        },
    )

    override suspend fun getTokenLimit(): Int = runner.maxNumTokens

    override suspend fun countTokens(request: GenerateContentRequest): Int = throw GenAiException(
        "LiteRT-LM's Kotlin API has no call that counts tokens.",
        null,
        ErrorCode.NOT_SUPPORTED,
    )

    override suspend fun getAvailableFeatures(): List<Int> = listOf(Feature.SYSTEM_PROMPT)
}
