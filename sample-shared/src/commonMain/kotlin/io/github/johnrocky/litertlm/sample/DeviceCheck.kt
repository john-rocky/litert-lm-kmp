package io.github.johnrocky.litertlm.sample

import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.Content
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.Conversation
import io.github.johnrocky.litertlm.ConversationConfig
import io.github.johnrocky.litertlm.EmbeddingEngine
import io.github.johnrocky.litertlm.EmbeddingEngineConfig
import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.InputData
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.OpenApiTool
import io.github.johnrocky.litertlm.genai.common.DownloadStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.LocalModel
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.prompt.Generation
import io.github.johnrocky.litertlm.stream
import io.github.johnrocky.litertlm.tool
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Enough to read a whole summary in the line. */
private const val ANSWER_CHARS_SHOWN = 600
private const val SHORT_PROMPT = "Reply with one word: ready."
private const val LONG_PROMPT = "Count from 1 to 200, one number per line."
private const val MAX_TOKENS = 2048
private const val TRUNCATED_BYTES = 64L * 1024 * 1024
private const val GARBAGE_BYTES = 1024 * 1024
private const val HEADER_PREFIX_BYTES = 32
private const val MIB = 1024L * 1024
private const val BALLAST_STEP_BYTES = 256L * MIB

private val WEATHER_TOOL = object : OpenApiTool {
    override fun getToolDescriptionJsonString(): String =
        """{"name":"get_weather","description":"Get the current weather for a city.",""" +
            """"parameters":{"type":"object","properties":{"city":{"type":"string","description":"The city name."}},""" +
            """"required":["city"]}}"""
}

/**
 * What DeviceCheck runs against. On the command line: --model PATH [--embedding PATH] --workdir DIR [--out FILE]
 * [--backend cpu|gpu] [--visionBackend cpu|gpu] [--skipDownload true] [--cases a,b,c] [--leaveMB N]
 * [--suite raw|task|all] [--provider auto|nano|litertlm] [--textOnlyModel PATH] [--image PATH] [--nanoDownloadMinutes N].
 * A relative path is under the app's own directory: an iPhone app cannot know the absolute path of its container from
 * outside.
 */
class DeviceCheckArgs(
    val modelPath: String,
    val workDir: String,
    val embeddingModelPath: String? = null,
    val outFile: String? = null,
    val backend: Backend = Backend.CPU(),
    val visionBackend: Backend = backend,
    val skipDownload: Boolean = false,
    val cases: Set<String>? = null,
    /** How much memory the cases that run only when named leave available. */
    val leaveBytes: Long? = null,
    /** `raw` for the cases of `:litertlm-kmp`, `task` for those of `:litertlm-genai`, `all` for both. */
    val suite: String = "all",
    /** Who answers the task cases: `auto`, `nano` or `litertlm`. The cases about local files always use LiteRT-LM. */
    val provider: String = "auto",
    /** A model without a vision part, for what a task does with an image on it. */
    val textOnlyModelPath: String? = null,
    /** A photo for the task case that runs only when named, `t_memory_photo`. */
    val imagePath: String? = null,
    /** How long `t_nano_status` follows one of Gemini Nano's downloads. 0 reads the status and starts none. */
    val nanoDownloadMinutes: Int = 15,
) {
    companion object {
        /** `--key value` pairs, or null if `--deviceCheck` is not among them on a platform that needs it. */
        fun fromOptions(
            options: Map<String, String>,
            defaultWorkDir: String? = null,
            baseDir: String? = null,
        ): DeviceCheckArgs? {
            fun path(name: String): String? =
                options[name]?.let { if (baseDir == null || it.startsWith("/")) it else "$baseDir/$it" }

            val model = path("model") ?: return null
            val workDir = path("workdir") ?: defaultWorkDir ?: return null
            return DeviceCheckArgs(
                modelPath = model,
                workDir = workDir,
                embeddingModelPath = path("embedding"),
                outFile = path("out"),
                backend = backendNamed(options["backend"]),
                visionBackend = backendNamed(options["visionBackend"] ?: options["backend"]),
                skipDownload = options["skipDownload"] == "true",
                cases = options["cases"]?.split(",")?.map { it.trim() }?.toSet(),
                leaveBytes = options["leaveMB"]?.toLongOrNull()?.let { it * MIB },
                suite = options["suite"] ?: "all",
                provider = options["provider"] ?: "auto",
                textOnlyModelPath = path("textOnlyModel"),
                imagePath = path("image"),
                nanoDownloadMinutes = options["nanoDownloadMinutes"]?.toIntOrNull() ?: 15,
            )
        }

        private fun backendNamed(name: String?): Backend = if (name == "gpu") Backend.GPU() else Backend.CPU()

        fun parseCommandLine(arguments: List<String>): Map<String, String> {
            val options = LinkedHashMap<String, String>()
            var i = 0
            while (i < arguments.size) {
                val key = arguments[i]
                if (key.startsWith("--")) {
                    val hasValue = i + 1 < arguments.size && !arguments[i + 1].startsWith("--")
                    options[key.removePrefix("--")] = if (hasValue) arguments[i + 1] else "true"
                    i += if (hasValue) 2 else 1
                } else {
                    i++
                }
            }
            return options
        }
    }
}

internal class CaseFailed(message: String) : Exception(message)

/** What one case found out. */
internal class CaseScope {
    var answer: String? = null
    var activeProvider: String? = null
    var tokens: Int? = null
    var expected: Throwable? = null
    var note: String? = null
    var skipped = false

    fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw CaseFailed(message())
    }

    /** Runs [block], which must throw a [T]: the case's line then reports it. */
    inline fun <reified T : Throwable> expectThrows(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) {
                expected = t
                return
            }
            throw t
        }
        throw CaseFailed("Expected ${T::class.simpleName}, but nothing was thrown.")
    }

    fun answerAndTokens(text: String, tokenCount: Int) {
        answer = text
        tokens = tokenCount
    }
}

/**
 * Exercises the library the way an app does and prints one JSON line per case, prefixed `DEVICECHECK `. The same code
 * runs on the JVM, the simulator and phones.
 */
class DeviceCheck(private val args: DeviceCheckArgs, private val print: (String) -> Unit) {
    private val workDir = Path(args.workDir)
    private val outFile = Path(args.outFile ?: "${args.workDir}/devicecheck.jsonl")
    private val cacheDir = "${args.workDir}/cache"
    private val backendName = args.backend.name
    private var passed = 0
    private var failed = 0
    private var skipped = 0

    private var engine: Engine? = null
    private var chat: Conversation? = null
    private var downloadedEmbeddingModel: String? = null

    private fun config(
        minAvailableMemoryBytes: Long? = null,
        path: String = args.modelPath,
        backend: Backend = args.backend,
        visionBackend: Backend? = null,
    ) =
        EngineConfig(
            modelPath = path,
            backend = backend,
            visionBackend = visionBackend,
            maxNumTokens = MAX_TOKENS,
            cacheDir = cacheDir,
            minAvailableMemoryBytes = minAvailableMemoryBytes,
        )

    /** Returns true if every case that ran passed. */
    suspend fun run(): Boolean {
        SystemFileSystem.createDirectories(workDir)
        SystemFileSystem.createDirectories(Path(cacheDir))
        outFile.parent?.let { SystemFileSystem.createDirectories(it) }
        if (SystemFileSystem.exists(outFile)) SystemFileSystem.delete(outFile)
        try {
            runCases()
        } finally {
            removeBadFiles()
        }
        emit(
            buildJsonObject {
                put("case", JsonPrimitive("_summary"))
                put("ok", JsonPrimitive(failed == 0))
                put("backend", JsonPrimitive(backendName))
                put("passed", JsonPrimitive(passed))
                put("failed", JsonPrimitive(failed))
                put("skipped", JsonPrimitive(skipped))
            },
        )
        return failed == 0
    }

    private suspend fun runCases() {
        if (args.suite != "task") rawCases()
        if (args.suite != "raw") TaskCases(args, this).run()
    }

    private suspend fun rawCases() {
        // Only when named: the system may end the process in these, which is what they look at.
        if (args.cases != null) {
            case("f_memory_unchecked") {
                withMemoryDownTo("f_memory_unchecked", modelFileSize() / 2) { ballast ->
                    Engine(config(minAvailableMemoryBytes = 0)).use { unchecked ->
                        unchecked.initialize()
                        progress("f_memory_unchecked", "loaded", ballast.availableBytes())
                        unchecked.createConversation().use { conversation ->
                            checkAnswer(conversation.sendMessage(SHORT_PROMPT).toString(), conversation)
                        }
                    }
                }
            }
            // The default check of an engine that takes images: above it image_1 has to answer, below it the type comes.
            case("f_memory_vision") {
                withMemoryDownTo("f_memory_vision", modelFileSize() / 4 * 3) { ballast ->
                    Engine(config(visionBackend = args.visionBackend)).use { images ->
                        images.initialize()
                        progress("f_memory_vision", "loaded", ballast.availableBytes())
                        askAboutTheCat(images)
                    }
                }
            }
        }

        case("engine_load") {
            engine = Engine(config()).also { it.initialize() }
        }

        case("chat_1") {
            val conversation = mainEngine().createConversation().also { chat = it }
            val reply = conversation.sendMessage(SHORT_PROMPT).toString()
            checkAnswer(reply, conversation)
        }
        case("chat_2") {
            val conversation = chat ?: throw CaseFailed("chat_1 made no conversation.")
            val reply = conversation.stream("What is the capital of France? Answer in one sentence.").toList()
                .joinToString("")
            checkAnswer(reply, conversation)
        }
        case("chat_3") {
            mainEngine().createConversation().use { conversation ->
                val prompt = "Summarize the following text in about 200 words.\n\n$SUMMARY_TEXT"
                val reply = conversation.stream(prompt).toList().joinToString("")
                checkAnswer(reply, conversation)
            }
        }

        case("f_missing") {
            expectThrows<LiteRtLmException.ModelNotFound> {
                Engine(config(path = "${args.workDir}/no-such-file.litertlm")).initialize()
            }
        }
        case("f_badpath_dir") {
            val directory = badFile("a_directory.litertlm")
            SystemFileSystem.createDirectories(directory)
            expectThrows<LiteRtLmException.InvalidModel> { Engine(config(path = directory.toString())).initialize() }
        }
        case("f_badpath_garbage") {
            val file = badFile("garbage_1mb.litertlm")
            writeBytes(file, Random(1).nextBytes(GARBAGE_BYTES))
            expectThrows<LiteRtLmException.InvalidModel> { Engine(config(path = file.toString())).initialize() }
        }
        case("f_badpath_magic") {
            // The real first bytes, then garbage: the native loader dies with SIGSEGV if this reaches it.
            val file = badFile("magic_garbage.litertlm")
            val start = SystemFileSystem.source(Path(args.modelPath)).buffered()
                .use { it.readByteArray(HEADER_PREFIX_BYTES) }
            writeBytes(file, start + Random(2).nextBytes(GARBAGE_BYTES))
            expectThrows<LiteRtLmException.InvalidModel> { Engine(config(path = file.toString())).initialize() }
        }
        case("f_badpath_trunc") {
            val file = badFile("trunc_64mb.litertlm")
            copyPrefix(args.modelPath, file, TRUNCATED_BYTES)
            expectThrows<LiteRtLmException.InvalidModel> { Engine(config(path = file.toString())).initialize() }
        }

        case("f_memory_det") {
            expectThrows<LiteRtLmException.InsufficientMemory> {
                Engine(config(minAvailableMemoryBytes = Long.MAX_VALUE)).initialize()
            }
        }
        case("f_memory_ballast") {
            val ballast = memoryBallast()
            if (ballast == null) {
                skip("No ballast on this platform: the check above is the deterministic one.")
                return@case
            }
            val required = modelFileSize() / 2
            val before = ballast.availableBytes()
            var added = 0L
            try {
                // Twice what looks free: the system also gives memory back as the ballast grows, by killing other apps.
                while (ballast.availableBytes() >= required && added < before * 2) {
                    ballast.add(BALLAST_STEP_BYTES)
                    added += BALLAST_STEP_BYTES
                }
                note = "available ${ballast.availableBytes()} of $before after $added bytes of ballast; required $required"
                expectThrows<LiteRtLmException.InsufficientMemory> { Engine(config()).initialize() }
            } finally {
                ballast.release()
            }
        }

        case("f_toolong") {
            val conversation = mainEngine().createConversation()
            try {
                expectThrows<LiteRtLmException.InputTooLong> { conversation.sendMessage("word ".repeat(10_000)) }
                // The same conversation keeps working, as the API promises.
                val same = conversation.sendMessage(SHORT_PROMPT).toString()
                check(same.isNotEmpty()) { "The same conversation gave an empty answer." }
            } finally {
                conversation.close()
            }
            mainEngine().createConversation().use { fresh ->
                checkAnswer(fresh.sendMessage(SHORT_PROMPT).toString(), fresh)
            }
        }

        case("f_cancel") {
            mainEngine().createConversation().use { conversation ->
                var chunks = 0
                expectThrows<LiteRtLmException.Cancelled> {
                    conversation.sendMessageAsync(LONG_PROMPT).collect {
                        chunks++
                        if (chunks == 3) conversation.cancelProcess()
                    }
                }
                val tokensAfterCancel = conversation.getTokenCount()
                val afterCancel = conversation.sendMessage(SHORT_PROMPT).toString()
                check(afterCancel.isNotEmpty()) { "The turn after cancelProcess gave an empty answer." }

                // Cancelling by leaving the collector stops the model the same way.
                val firstChunks = conversation.stream(LONG_PROMPT).take(3).toList()
                check(firstChunks.size == 3) { "Expected 3 chunks before leaving, got ${firstChunks.size}." }
                checkAnswer(conversation.sendMessage(SHORT_PROMPT).toString(), conversation)
                note = "cancelled at chunk $chunks; $tokensAfterCancel tokens before the next turn"
            }
        }

        case("f_tool") {
            val config = ConversationConfig(tools = listOf(tool(WEATHER_TOOL)))
            mainEngine().createConversation(config).use { conversation ->
                val reply = conversation.sendMessage("What is the weather in Tokyo right now? Use the tool.")
                val call = reply.toolCalls.firstOrNull()
                    ?: throw CaseFailed("No tool call. The reply was: $reply")
                check(call.name == "get_weather") { "Wrong tool: ${call.name}" }
                check(call.arguments["city"] is String) { "Bad arguments: ${call.arguments}" }
                note = "tool call ${call.name} ${call.arguments}"

                val response = Content.ToolResponse("get_weather", mapOf("temperature_c" to 21, "condition" to "sunny"))
                val final = conversation.sendMessage(Message.tool(Contents.of(response))).toString()
                check(final.isNotEmpty()) { "Empty answer after the tool response." }

                // Start over with a tool call in the history, as a cancelled turn makes the conversation do.
                conversation.stream(LONG_PROMPT).take(1).toList()
                checkAnswer(conversation.sendMessage(SHORT_PROMPT).toString(), conversation)
            }
        }

        case("f_image_novision") {
            mainEngine().createConversation().use { conversation ->
                val message = Contents.of(Content.ImageBytes(catJpeg()), Content.Text(IMAGE_QUESTION))
                expectThrows<LiteRtLmException.BackendUnavailable> { conversation.sendMessage(message) }
                checkAnswer(conversation.sendMessage(SHORT_PROMPT).toString(), conversation)
            }
        }

        case("f_closeduring_conversation") {
            val conversation = mainEngine().createConversation()
            expected = closeDuringStream(conversation) { conversation.close() }
            check(expected is LiteRtLmException.Cancelled) { "The stream ended with $expected." }
            mainEngine().createConversation().use { fresh ->
                checkAnswer(fresh.sendMessage(SHORT_PROMPT).toString(), fresh)
            }
        }

        case("f_useafterclose") {
            val conversation = mainEngine().createConversation()
            conversation.close()
            expectThrows<LiteRtLmException.ClosedUse> { conversation.sendMessage(SHORT_PROMPT) }
            expectThrows<LiteRtLmException.ClosedUse> { conversation.stream(SHORT_PROMPT).toList() }
            expectThrows<LiteRtLmException.ClosedUse> { conversation.getTokenCount() }
            conversation.cancelProcess()
            expectThrows<LiteRtLmException.AlreadyInitialized> { mainEngine().initialize() }
            val notLoaded = Engine(config())
            expectThrows<LiteRtLmException.NotInitialized> { notLoaded.createConversation() }
            notLoaded.close()
            expectThrows<LiteRtLmException.ClosedUse> { notLoaded.createConversation() }
        }

        case("f_closeduring_engine") {
            val closing = mainEngine()
            val conversation = closing.createConversation()
            expected = closeDuringStream(conversation) { closing.close() }
            engine = null
            check(expected is LiteRtLmException.Cancelled) { "The stream ended with $expected." }
            check(!conversation.isAlive) { "The conversation outlived its engine." }
            expectThrows<LiteRtLmException.ClosedUse> { closing.createConversation() }
        }

        case("f_reload") {
            val first = Engine(config()).also { it.initialize() }
            first.createConversation().use { it.sendMessage(SHORT_PROMPT) }
            first.close()
            val second = Engine(config()).also { it.initialize() }
            engine = second
            second.createConversation().use { conversation ->
                checkAnswer(conversation.sendMessage(SHORT_PROMPT).toString(), conversation)
            }
        }

        case("f_doubleclose") {
            val conversation = mainEngine().createConversation()
            conversation.close()
            conversation.close()
            val closing = mainEngine()
            engine = null
            closing.close()
            closing.close()
        }

        // After the chat engine is closed: a phone should not have two of them in memory.
        case("image_1") {
            val images = Engine(config(visionBackend = args.visionBackend)).also { it.initialize() }
            engine = images
            askAboutTheCat(images)
        }
        case("f_image") {
            mainEngine().createConversation().use { conversation ->
                val cat = catJpeg()
                val random = Random(3)
                val jpegStart = byteArrayOf(-1, -40, -1, -32)
                val pngStart = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
                val broken = listOf(
                    random.nextBytes(4096),
                    jpegStart + random.nextBytes(4092),
                    pngStart + random.nextBytes(4088),
                    ByteArray(0),
                )
                for (bytes in broken) {
                    expectThrows<LiteRtLmException.InvalidImage> {
                        conversation.sendMessage(Contents.of(Content.ImageBytes(bytes), Content.Text(IMAGE_QUESTION)))
                    }
                }
                val half = cat.copyOf(cat.size / 2)
                val fromHalf = conversation.sendMessage(Contents.of(Content.ImageBytes(half), Content.Text(IMAGE_QUESTION)))
                note = "half a JPEG decodes: $fromHalf"
                checkAnswer(conversation.sendMessage(SHORT_PROMPT).toString(), conversation)
            }
            engine?.close()
            engine = null
        }

        case("f_gpu") {
            // Either it works here, or it says so with the right type.
            try {
                Engine(config(backend = Backend.GPU())).use { gpu ->
                    gpu.initialize()
                    gpu.createConversation().use { conversation ->
                        checkAnswer(conversation.sendMessage(SHORT_PROMPT).toString(), conversation)
                    }
                }
                note = "The GPU backend works here."
            } catch (e: LiteRtLmException.BackendUnavailable) {
                expected = e
                note = "The GPU backend is not available here."
            }
        }

        case("download_fail") {
            if (args.skipDownload) {
                skip("--skipDownload")
                return@case
            }
            suspend fun failureOf(model: LocalModel): GenAiException =
                Generation.getClient(ModelProvider.LiteRtLm(model, cacheDir = cacheDir)).use { client ->
                    client.download().toList().filterIsInstance<DownloadStatus.DownloadFailed>().singleOrNull()?.e
                        ?: throw CaseFailed("No failure was reported.")
                }

            val name = "no-such-file.litertlm"
            val noHost = failureOf(LocalModel.huggingFace(GEMMA_REPO, name, directory = args.workDir, endpoint = UNREACHABLE_ENDPOINT))
            note = "no such host: ${noHost.message}"
            expected = failureOf(LocalModel.huggingFace(GEMMA_REPO, name, directory = args.workDir))
            check(!SystemFileSystem.exists(Path(args.workDir, name))) { "A failed download left a file behind." }
        }
        case("download_ok") {
            if (args.skipDownload) {
                skip("--skipDownload")
                return@case
            }
            val destination = "${args.workDir}/$EMBEDDING_FILE_NAME"
            if (SystemFileSystem.exists(Path(destination))) SystemFileSystem.delete(Path(destination))
            val fromHub = LocalModel.huggingFace(EMBEDDING_REPO, EMBEDDING_FILE_NAME, directory = args.workDir)
            var last = 0L
            var total: Long? = null
            Generation.getClient(ModelProvider.LiteRtLm(fromHub, cacheDir = cacheDir)).use { client ->
                client.download().collect { step ->
                    when (step) {
                        is DownloadStatus.DownloadStarted -> total = step.bytesToDownload
                        is DownloadStatus.DownloadProgress -> last = step.totalBytesDownloaded
                        is DownloadStatus.DownloadFailed -> throw step.e
                        DownloadStatus.DownloadCompleted -> Unit
                    }
                }
            }
            val size = SystemFileSystem.metadataOrNull(Path(destination))?.size ?: -1
            check(size > 0 && size == last) { "Got a file of $size bytes after $last bytes of progress." }
            check(total == size) { "Expected $total bytes, got $size." }
            note = "$size bytes"
            downloadedEmbeddingModel = destination
        }

        case("embedding") {
            val path = args.embeddingModelPath ?: downloadedEmbeddingModel
            if (path == null) {
                skip("No embedding model: pass --embedding, or let download_ok fetch one.")
                return@case
            }
            EmbeddingEngine(EmbeddingEngineConfig(modelPath = path, cacheDir = cacheDir)).use { embedding ->
                embedding.initialize()
                val vector = embedding.computeEmbedding(listOf(InputData.Text("The quick brown fox."))).embedding
                check(vector.isNotEmpty()) { "An empty vector." }
                check(vector.any { it != 0f }) { "A vector of zeros." }
                note = "vector length ${vector.size}"
            }
        }
    }

    private fun mainEngine(): Engine = engine ?: throw CaseFailed("An earlier case did not leave an engine.")

    internal fun modelFileSize(): Long = SystemFileSystem.metadataOrNull(Path(args.modelPath))!!.size

    /** Runs [block] with less than `--leaveMB` (or else [defaultLeave]) bytes available. A library failure is the case's finding. */
    internal suspend fun CaseScope.withMemoryDownTo(caseName: String, defaultLeave: Long, block: suspend (MemoryBallast) -> Unit) {
        val ballast = memoryBallast()
        if (ballast == null) {
            skip("No ballast on this platform.")
            return
        }
        val leave = args.leaveBytes ?: defaultLeave
        val before = ballast.availableBytes()
        var added = 0L
        try {
            while (ballast.availableBytes() >= leave && added < before) {
                val step = minOf(BALLAST_STEP_BYTES, ballast.availableBytes() - leave + MIB)
                ballast.add(step)
                added += step
            }
            note = "available ${ballast.availableBytes()} of $before after $added bytes of ballast"
            progress(caseName, "ballast", ballast.availableBytes())
            block(ballast)
        } catch (e: LiteRtLmException) {
            expected = e
        } catch (e: GenAiException) {
            expected = e
        } finally {
            ballast.release()
        }
    }

    private suspend fun CaseScope.askAboutTheCat(images: Engine) {
        images.createConversation().use { conversation ->
            val message = Contents.of(Content.ImageBytes(catJpeg()), Content.Text(IMAGE_QUESTION))
            val reply = conversation.sendMessage(message).toString()
            checkAnswer(reply, conversation)
            check(IMAGE_WORDS.any { it in reply.lowercase() }) { "None of $IMAGE_WORDS in: $reply" }

            // Start over with the image in the history, as a cancelled turn makes the conversation do.
            conversation.stream(LONG_PROMPT).take(1).toList()
            val again = conversation.sendMessage("Is it a cat? Answer with one word.").toString()
            check(again.isNotEmpty()) { "An empty answer after starting over with an image in the history." }
            note = listOfNotNull(note, "after starting over: $again").joinToString("; ")
        }
    }

    private fun badFile(name: String): Path {
        val directory = Path(workDir, "bad")
        SystemFileSystem.createDirectories(directory)
        return Path(directory, name)
    }

    /** Leaves nothing large behind on a phone. */
    private fun removeBadFiles() {
        val directory = Path(workDir, "bad")
        if (!SystemFileSystem.exists(directory)) return
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it, mustExist = false) }
        SystemFileSystem.delete(directory, mustExist = false)
    }

    private fun CaseScope.skip(reason: String) {
        skipped = true
        note = reason
    }

    /** No empty answer, the stream ended normally to get here, and the context window was not what stopped it. */
    private suspend fun CaseScope.checkAnswer(reply: String, conversation: Conversation) {
        val tokenCount = conversation.getTokenCount()
        answerAndTokens(reply, tokenCount)
        check(reply.isNotEmpty()) { "An empty answer." }
        check(tokenCount < MAX_TOKENS) { "The token count reached the limit: $tokenCount." }
    }

    /** Streams a long answer, runs [close] on another thread at the first chunk, and returns how the stream ended. */
    private suspend fun closeDuringStream(conversation: Conversation, close: () -> Unit): Throwable? {
        val firstChunk = CompletableDeferred<Unit>()
        var ended: Throwable? = null
        coroutineScope {
            val collector = launch(Dispatchers.Default) {
                try {
                    conversation.sendMessageAsync(LONG_PROMPT).collect { firstChunk.complete(Unit) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ended = e
                } finally {
                    // A stream that fails before its first chunk must not leave the wait below hanging.
                    firstChunk.complete(Unit)
                }
            }
            firstChunk.await()
            withContext(Dispatchers.Default) { close() }
            collector.join()
        }
        return ended
    }

    internal suspend fun case(name: String, block: suspend CaseScope.() -> Unit) {
        if (args.cases != null && name !in args.cases) return
        val scope = CaseScope()
        val start = TimeSource.Monotonic.markNow()
        val memory = memoryBallast()
        val availableStart = memory?.availableBytes()
        var availableLowest = availableStart
        var failure: Throwable? = null
        coroutineScope {
            val watcher = memory?.let {
                launch {
                    while (isActive) {
                        availableLowest = minOf(availableLowest!!, it.availableBytes())
                        delay(100)
                    }
                }
            }
            try {
                withTimeout(30.minutes) { scope.block() }
            } catch (e: Exception) {
                failure = e
            }
            watcher?.cancelAndJoin()
        }
        val shown = failure ?: scope.expected
        val ok = failure == null
        when {
            !ok -> failed++
            scope.skipped -> skipped++
            else -> passed++
        }
        emit(
            buildJsonObject {
                put("case", JsonPrimitive(name))
                put("ok", JsonPrimitive(ok))
                if (scope.skipped) put("skipped", JsonPrimitive(true))
                put("backend", JsonPrimitive(backendName))
                put("activeProvider", scope.activeProvider?.let { JsonPrimitive(it) } ?: JsonNull)
                put("ms", JsonPrimitive(start.elapsedNow().inWholeMilliseconds))
                put("answerChars", JsonPrimitive(scope.answer?.length ?: 0))
                put("tokens", scope.tokens?.let { JsonPrimitive(it) } ?: JsonNull)
                put("answer", scope.answer?.let { JsonPrimitive(it.take(ANSWER_CHARS_SHOWN)) } ?: JsonNull)
                put("exceptionType", shown?.let { JsonPrimitive(it::class.simpleName ?: "?") } ?: JsonNull)
                put("exceptionMessage", shown?.let { JsonPrimitive((it.message ?: "").take(300)) } ?: JsonNull)
                put("note", scope.note?.let { JsonPrimitive(it) } ?: JsonNull)
                put("availableStart", availableStart?.let { JsonPrimitive(it) } ?: JsonNull)
                put("availableLowest", availableLowest?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        )
    }

    /** A line written before a step that may not return. */
    private fun progress(case: String, step: String, available: Long) {
        emit(
            buildJsonObject {
                put("case", JsonPrimitive(case))
                put("step", JsonPrimitive(step))
                put("available", JsonPrimitive(available))
            },
        )
    }

    /** A line about a case that is still running: what it has found so far, in case it does not come back. */
    internal fun step(case: String, step: String) {
        emit(
            buildJsonObject {
                put("case", JsonPrimitive(case))
                put("step", JsonPrimitive(step))
            },
        )
    }

    private fun emit(line: JsonObject) {
        val text = line.toString()
        print("DEVICECHECK $text")
        val buffer = Buffer()
        buffer.write("$text\n".encodeToByteArray())
        SystemFileSystem.sink(outFile, append = true).use { it.write(buffer, buffer.size) }
    }

    private fun writeBytes(path: Path, bytes: ByteArray) {
        val buffer = Buffer()
        buffer.write(bytes)
        SystemFileSystem.sink(path).use { it.write(buffer, buffer.size) }
    }

    private fun copyPrefix(from: String, to: Path, count: Long) {
        SystemFileSystem.source(Path(from)).use { source ->
            SystemFileSystem.sink(to).use { sink ->
                val buffer = Buffer()
                var remaining = count
                while (remaining > 0) {
                    val read = source.readAtMostTo(buffer, minOf(remaining, 1L shl 20))
                    if (read <= 0) break
                    sink.write(buffer, read)
                    remaining -= read
                }
            }
        }
    }
}
