package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.Conversation
import io.github.johnrocky.litertlm.ConversationConfig
import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.MessageCallback
import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.FeatureStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.LocalModel
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import kotlin.random.Random
import kotlin.test.fail
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** A provider: what it answers, what it fails with, and what was asked of it. */
internal class FakeFeature(
    private val answer: String = "answer",
    var status: Int = FeatureStatus.AVAILABLE,
    var statusFailure: GenAiException? = null,
    failures: List<GenAiException> = emptyList(),
    private val pieces: List<String> = emptyList(),
) : FeatureClient<String, String> {
    private val failures = ArrayDeque(failures)
    var inferences = 0
    var downloads = 0
    var prepares = 0
    var closes = 0

    override val activeProvider: ActiveProvider? = null
    override val activeProviderReason: String? = null

    override suspend fun checkFeatureStatus(): Int {
        statusFailure?.let { throw it }
        return status
    }

    override suspend fun downloadFeature(callback: DownloadCallback) {
        downloads++
        callback.onDownloadCompleted()
    }

    override suspend fun prepareInferenceEngine() {
        prepares++
        failures.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun getBaseModelName(): String = answer

    override suspend fun runInference(request: String, callback: StreamingCallback?): String {
        inferences++
        pieces.forEach { callback?.onNewText(it) }
        failures.removeFirstOrNull()?.let { throw it }
        return answer
    }

    override fun close() {
        closes++
    }
}

/** A thread-safe record of what happened, in order. */
internal class Events {
    private val lock = SynchronizedObject()
    private val list = ArrayList<String>()

    fun add(event: String) = synchronized(lock) { list += event }

    fun all(): List<String> = synchronized(lock) { list.toList() }
}

/** Stands in for the raw layer's factory. */
internal class FakeRuntime(
    private val initFailure: (EngineConfig) -> Throwable? = { null },
    private val reply: (ConversationConfig, Message) -> Flow<Message> = { _, _ -> flowOf(Message.model("* ok")) },
    private val conversationFailure: (EngineConfig) -> Throwable? = { null },
) {
    val events = Events()
    private val lock = SynchronizedObject()
    private val made = ArrayList<FakeEngine>()
    private val configs = ArrayList<ConversationConfig>()
    private val messages = ArrayList<Message>()

    val engines: List<FakeEngine> get() = synchronized(lock) { made.toList() }
    val conversationConfigs: List<ConversationConfig> get() = synchronized(lock) { configs.toList() }
    val sentMessages: List<Message> get() = synchronized(lock) { messages.toList() }

    fun factory(config: EngineConfig): Engine = FakeEngine(config, this).also { synchronized(lock) { made += it } }

    private fun kind(config: EngineConfig) = if (config.visionBackend == null) "text" else "vision"

    inner class FakeEngine(override val engineConfig: EngineConfig, private val runtime: FakeRuntime) : Engine {
        private var loaded = false
        val closed = CompletableDeferred<Unit>()

        override fun isInitialized(): Boolean = loaded

        override suspend fun initialize() {
            events.add("load ${kind(engineConfig)}")
            initFailure(engineConfig)?.let { throw it }
            loaded = true
        }

        override suspend fun createConversation(conversationConfig: ConversationConfig): Conversation {
            events.add("conversation")
            conversationFailure(engineConfig)?.let { throw it }
            synchronized(lock) { configs += conversationConfig }
            return FakeConversation(conversationConfig, runtime)
        }

        override fun close() {
            events.add("close ${kind(engineConfig)}")
            closed.complete(Unit)
        }
    }

    private class FakeConversation(
        private val config: ConversationConfig,
        private val runtime: FakeRuntime,
    ) : Conversation {
        private var alive = true

        override val isAlive: Boolean get() = alive

        override suspend fun sendMessage(message: Message): Message = error("Tasks use the stream.")

        override fun sendMessageAsync(message: Message): Flow<Message> {
            synchronized(runtime.lock) { runtime.messages += message }
            return runtime.reply(config, message)
        }

        override fun sendMessageAsync(message: Message, callback: MessageCallback): Unit = error("Tasks use the stream.")

        override fun cancelProcess() {}

        override suspend fun getTokenCount(): Int = 0

        override fun close() {
            alive = false
            runtime.events.add("conversation closed")
        }
    }
}

/** A runner on a model file that exists, over [runtime]. Pass [scope] for what closes in the background. */
internal fun fakeRunner(
    runtime: FakeRuntime,
    scope: CoroutineScope,
    model: LocalModel? = null,
    path: String? = null,
): LocalRunner {
    val file = path ?: temporaryDirectory().let { directory ->
        val modelFile = Path(directory, "model.litertlm")
        writeFile(modelFile, ByteArray(16))
        modelFile.toString()
    }
    val config = EngineConfig(modelPath = file, visionBackend = io.github.johnrocky.litertlm.Backend.CPU(), maxNumTokens = 4608)
    val engine = SharedEngine(config, runtime::factory, scope).also { it.retain() }
    return LocalRunner(model ?: LocalModel.file(file), file, null, 4608, engine, ModelDownloads(http = NoHttp, scope = scope))
}

internal object NoHttp : HttpClient {
    override suspend fun getText(url: String): HttpText = throw NetworkFailure("No network in this test.")

    override suspend fun getBytes(url: String, from: Long, onStatus: (Int) -> Unit, onBytes: (ByteArray, Int) -> Unit): Unit =
        throw NetworkFailure("No network in this test.")
}

internal fun temporaryDirectory(): Path {
    val directory = Path(SystemTemporaryDirectory, "litertlm-genai-test-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(directory)
    return directory
}

internal fun writeFile(path: Path, bytes: ByteArray) {
    val buffer = Buffer()
    buffer.write(bytes)
    SystemFileSystem.sink(path).use { it.write(buffer, buffer.size) }
}

/** The error code of the [GenAiException] that [block] must throw. */
internal suspend fun codeOf(block: suspend () -> Unit): Int {
    try {
        block()
    } catch (e: GenAiException) {
        return e.errorCode
    }
    fail("Expected a GenAiException, but nothing was thrown.")
}
