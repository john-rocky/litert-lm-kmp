package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.Conversation
import io.github.johnrocky.litertlm.ConversationConfig
import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.Message
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DefaultEngine(
    override val engineConfig: EngineConfig,
    private val runtime: NativeRuntime,
    private val preflight: (EngineConfig) -> Unit = { config ->
        checkAvailableMemory(config.minAvailableMemoryBytes, preflightModelFile(config.modelPath), config.visionBackend != null)
    },
) : Engine {
    private val lock = SynchronizedObject()
    private var native: NativeEngine? = null
    private var initializing = false
    private var closed = false
    private var leakedChild = false
    private val children = LinkedHashSet<DefaultConversation>()
    private val closeDone = CompletableDeferred<Unit>()

    /** Calls on the engine handle must not overlap, and it must not be deleted during one. */
    private val nativeCalls = Mutex()

    override fun isInitialized(): Boolean = synchronized(lock) { native != null }

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) throw LiteRtLmException.ClosedUse("Engine")
            if (native != null || initializing) throw LiteRtLmException.AlreadyInitialized("Engine")
            initializing = true
        }
        val created = try {
            runtime.checkAvailable()
            preflight(engineConfig)
            load()
        } catch (t: Throwable) {
            synchronized(lock) { initializing = false }
            throw t
        }
        val keep = synchronized(lock) {
            initializing = false
            if (closed) false else {
                native = created
                true
            }
        }
        if (!keep) {
            created.close()
            throw LiteRtLmException.ClosedUse("Engine")
        }
    }

    private fun load(): NativeEngine = try {
        runtime.createEngine(
            NativeEngineSettings(
                modelPath = engineConfig.modelPath,
                backend = engineConfig.backend,
                visionBackend = engineConfig.visionBackend,
                maxNumTokens = engineConfig.maxNumTokens,
                cacheDir = engineConfig.cacheDir,
            ),
        )
    } catch (f: NativeFailure) {
        throw f.toLoadException(engineConfig.modelPath, engineConfig.backend)
    }

    override suspend fun createConversation(conversationConfig: ConversationConfig): Conversation =
        withContext(Dispatchers.IO) {
            nativeCalls.withLock {
                val conversation = DefaultConversation(
                    this@DefaultEngine,
                    conversationConfig,
                    createNativeLocked(conversationConfig, conversationConfig.initialMessages),
                )
                synchronized(lock) { children += conversation }
                conversation
            }
        }

    /** A new native conversation that has [messages], for a conversation that has to start over. */
    internal suspend fun createNativeConversation(
        config: ConversationConfig,
        messages: List<Message>,
    ): NativeConversation = nativeCalls.withLock { createNativeLocked(config, messages) }

    private fun createNativeLocked(config: ConversationConfig, messages: List<Message>): NativeConversation {
        val engine = synchronized(lock) {
            if (closed) throw LiteRtLmException.ClosedUse("Engine")
            native ?: throw LiteRtLmException.NotInitialized("Engine")
        }
        return try {
            engine.createConversation(
                NativeConversationSettings(
                    systemInstruction = config.systemInstruction,
                    messages = messages,
                    tools = config.tools,
                    sampler = config.samplerConfig,
                ),
            )
        } catch (f: NativeFailure) {
            throw LiteRtLmException.Internal(f.statusCode(), f.message, f)
        }
    }

    /** Called by a conversation when it is closed. [leaked]: it could not be deleted, so neither can the engine. */
    internal fun onConversationClosed(conversation: DefaultConversation, leaked: Boolean) {
        synchronized(lock) {
            children -= conversation
            if (leaked) leakedChild = true
        }
    }

    override fun close() {
        val first = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!first) {
            runBlocking { closeDone.await() }
            return
        }
        try {
            // The C API requires every conversation to be gone before the engine. A createConversation that was
            // already inside the mutex can add one after the first pass, hence the loop.
            while (true) {
                synchronized(lock) { children.toList() }.forEach { it.close() }
                val finished = runBlocking {
                    nativeCalls.withLock {
                        val toDelete = synchronized(lock) {
                            if (children.isNotEmpty()) {
                                return@withLock false
                            }
                            native.also { native = null }.takeUnless { leakedChild }
                        }
                        toDelete?.close()
                        true
                    }
                }
                if (finished) return
            }
        } finally {
            closeDone.complete(Unit)
        }
    }
}
