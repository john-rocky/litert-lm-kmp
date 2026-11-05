package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.ConversationConfig
import io.github.johnrocky.litertlm.EmbeddingEngineConfig
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.EmbeddingOptions
import io.github.johnrocky.litertlm.EmbeddingResponse
import io.github.johnrocky.litertlm.InputData
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** The lifecycle rules of the shared code, against a scripted runtime: no model and no native library. */
class LifecycleTest {
    @Test
    fun repliesAreCollectedAndTheHistoryKept() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        val conversation = engine.createConversation()

        runtime.onTurn = { sink, _ ->
            sink.onChunk(Message.model("Hel"))
            sink.onChunk(Message.model("lo"))
            sink.onDone()
        }
        assertEquals("Hello", conversation.sendMessage("Hi").toString())
        assertEquals(listOf("Hel", "lo"), conversation.stream("Again").toList())
        assertEquals(1, runtime.conversations.size)
    }

    @Test
    fun aCancelledTurnLeavesNoTraceAndTheNextTurnStartsOver() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        val conversation = engine.createConversation(ConversationConfig(initialMessages = listOf(Message.user("seed"))))

        runtime.onTurn = { sink, _ ->
            sink.onChunk(Message.model("One"))
            sink.onDone()
        }
        conversation.sendMessage("first")
        val first = runtime.conversations.single()

        runtime.onTurn = { sink, _ -> sink.onChunk(Message.model("par")) }
        withContext(Dispatchers.Default) {
            val failure = async { runCatching { conversation.sendMessage("cancelled") }.exceptionOrNull() }
            runtime.waitForStartedTurns(2)
            conversation.cancelProcess()
            assertTrue(failure.await() is LiteRtLmException.Cancelled)
        }
        assertTrue(first.cancelled)
        assertTrue(first.closed)

        runtime.onTurn = { sink, _ ->
            sink.onChunk(Message.model("Two"))
            sink.onDone()
        }
        assertEquals("Two", conversation.sendMessage("second").toString())

        val rebuilt = runtime.conversations.last()
        assertEquals(2, runtime.conversations.size)
        assertEquals(listOf("seed", "first", "One"), rebuilt.settings.messages.map { it.toString() })
    }

    @Test
    fun aFailedTurnStartsOverToo() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        val conversation = engine.createConversation()

        runtime.onTurn = { sink, _ ->
            sink.onError(NativeFailure(null, "INVALID_ARGUMENT: Input token ids are too long."))
        }
        assertFailsWith<LiteRtLmException.InputTooLong> { conversation.sendMessage("long") }
        assertTrue(runtime.conversations.single().closed)

        runtime.onTurn = { sink, _ ->
            sink.onChunk(Message.model("ok"))
            sink.onDone()
        }
        assertEquals("ok", conversation.sendMessage("short").toString())
        assertEquals(emptyList(), runtime.conversations.last().settings.messages)
    }

    @Test
    fun aCollectorThatGoesAwayStopsTheModelAndWaitsForTheLastCallback() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        val conversation = engine.createConversation()

        runtime.onTurn = { sink, _ -> sink.onChunk(Message.model("first")) }
        withContext(Dispatchers.Default) {
            assertEquals("first", conversation.stream("Hi").first())
        }
        val native = runtime.conversations.single()
        assertTrue(native.cancelled)
        assertTrue(native.closed)
        assertTrue(native.finishedBeforeClose)
    }

    @Test
    fun closeDuringAStreamEndsItAsCancelledAfterTheLastCallback() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        val conversation = engine.createConversation()

        runtime.onTurn = { sink, _ -> sink.onChunk(Message.model("par")) }
        withContext(Dispatchers.Default) {
            val failure = async { runCatching { conversation.stream("Hi").toList() }.exceptionOrNull() }
            runtime.waitForStartedTurns(1)
            conversation.close()
            assertTrue(failure.await() is LiteRtLmException.Cancelled)
        }
        val native = runtime.conversations.single()
        assertTrue(native.closed)
        assertTrue(native.finishedBeforeClose)
        assertFalse(conversation.isAlive)
    }

    @Test
    fun engineCloseStopsItsConversationsFirst() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        val conversation = engine.createConversation()

        runtime.onTurn = { sink, _ -> sink.onChunk(Message.model("par")) }
        withContext(Dispatchers.Default) {
            val failure = async { runCatching { conversation.stream("Hi").toList() }.exceptionOrNull() }
            runtime.waitForStartedTurns(1)
            engine.close()
            assertTrue(failure.await() is LiteRtLmException.Cancelled)
        }
        assertEquals(listOf("conversation closed", "engine closed"), runtime.events.toList().takeLast(2))
        assertFalse(conversation.isAlive)
    }

    @Test
    fun aSecondCloseDoesNothing() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        val conversation = engine.createConversation()
        conversation.close()
        conversation.close()
        engine.close()
        engine.close()
        assertEquals(listOf("conversation closed", "engine closed"), runtime.events.toList())
    }

    @Test
    fun useAfterCloseAndBeforeInitializeAreTyped() = runTest {
        val runtime = FakeRuntime()
        val engine = DefaultEngine(EngineConfig("/model"), runtime) {}
        assertFalse(engine.isInitialized())
        assertFailsWith<LiteRtLmException.NotInitialized> { engine.createConversation() }

        engine.initialize()
        assertTrue(engine.isInitialized())
        assertFailsWith<LiteRtLmException.AlreadyInitialized> { engine.initialize() }

        val conversation = engine.createConversation()
        conversation.close()
        assertFailsWith<LiteRtLmException.ClosedUse> { conversation.sendMessage("Hi") }
        assertFailsWith<LiteRtLmException.ClosedUse> { conversation.stream("Hi").toList() }
        assertFailsWith<LiteRtLmException.ClosedUse> { conversation.getTokenCount() }
        conversation.cancelProcess()

        engine.close()
        assertFalse(engine.isInitialized())
        assertFailsWith<LiteRtLmException.ClosedUse> { engine.createConversation() }
        assertFailsWith<LiteRtLmException.ClosedUse> { engine.initialize() }
    }

    @Test
    fun aPreflightFailureLeavesTheEngineReadyToRetry() = runTest {
        val runtime = FakeRuntime()
        var fail = true
        val engine = DefaultEngine(EngineConfig("/model"), runtime) {
            if (fail) throw LiteRtLmException.ModelNotFound("/model")
        }
        assertFailsWith<LiteRtLmException.ModelNotFound> { engine.initialize() }
        fail = false
        engine.initialize()
        assertTrue(engine.isInitialized())
    }

    @Test
    fun twoTurnsNeverOverlap() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        val conversation = engine.createConversation()
        val release = CompletableDeferred<NativeStreamSink>()

        runtime.onTurn = { sink, _ -> release.complete(sink) }
        withContext(Dispatchers.Default) {
            coroutineScope {
                val first = async { conversation.sendMessage("first").toString() }
                runtime.waitForStartedTurns(1)
                runtime.onTurn = { sink, _ ->
                    sink.onChunk(Message.model("second reply"))
                    sink.onDone()
                }
                val second = async { conversation.sendMessage("second").toString() }
                launch {
                    val sink = release.await()
                    // The second turn is queued behind the first one, not started.
                    assertEquals(1, runtime.startedTurns)
                    sink.onChunk(Message.model("first reply"))
                    sink.onDone()
                }
                assertEquals("first reply", first.await())
                assertEquals("second reply", second.await())
            }
        }
        assertEquals(2, runtime.startedTurns)
    }

    @Test
    fun theSystemInstructionGoesToTheRuntime() = runTest {
        val runtime = FakeRuntime()
        val engine = readyEngine(runtime)
        engine.createConversation(ConversationConfig(systemInstruction = Contents.of("Be brief.")))
        assertEquals("Be brief.", runtime.conversations.single().settings.systemInstruction.toString())
    }

    @Test
    fun aMissingRuntimeIsReportedBeforeAMissingModelFile() = runTest {
        val runtime = object : NativeRuntime by FakeRuntime() {
            override fun checkAvailable() = throw LiteRtLmException.Internal(9, "no runtime")
        }
        val engine = DefaultEngine(EngineConfig("/no/such/model.litertlm"), runtime)
        assertEquals("no runtime", assertFailsWith<LiteRtLmException.Internal> { engine.initialize() }.message)
        assertFalse(engine.isInitialized())
        val embedding = DefaultEmbeddingEngine(EmbeddingEngineConfig("/no/such/model.litertlm"), runtime)
        assertEquals("no runtime", assertFailsWith<LiteRtLmException.Internal> { embedding.initialize() }.message)
    }

    private suspend fun readyEngine(runtime: FakeRuntime): Engine =
        DefaultEngine(EngineConfig("/model"), runtime) {}.also { it.initialize() }
}

private class FakeRuntime : NativeRuntime {
    private val lock = SynchronizedObject()
    private var started = 0

    val conversations = ArrayList<FakeConversation>()
    val events = ArrayList<String>()
    var onTurn: (NativeStreamSink, Message) -> Unit = { sink, _ -> sink.onDone() }

    val startedTurns: Int get() = synchronized(lock) { started }

    fun turnStarted() {
        synchronized(lock) { started++ }
    }

    /** Waits on a background thread: the turn runs on another one. */
    suspend fun waitForStartedTurns(count: Int) {
        withContext(Dispatchers.Default) {
            while (startedTurns < count) delay(5)
        }
    }

    fun event(text: String) {
        synchronized(lock) { events += text }
    }

    override fun createEngine(settings: NativeEngineSettings): NativeEngine = object : NativeEngine {
        override fun createConversation(settings: NativeConversationSettings): NativeConversation =
            FakeConversation(this@FakeRuntime, settings).also { conversations += it }

        override fun close() = event("engine closed")
    }

    override fun createEmbeddingEngine(settings: NativeEmbeddingSettings): NativeEmbeddingEngine =
        object : NativeEmbeddingEngine {
            override fun computeEmbedding(contents: List<InputData>, options: EmbeddingOptions) =
                EmbeddingResponse(floatArrayOf(0f))

            override fun close() = Unit
        }
}

private class FakeConversation(
    private val runtime: FakeRuntime,
    val settings: NativeConversationSettings,
) : NativeConversation {
    private var sink: NativeStreamSink? = null
    var cancelled = false
    var closed = false
    var finishedBeforeClose = false

    override fun sendMessageStream(message: Message, sink: NativeStreamSink) {
        this.sink = sink
        runtime.onTurn(sink, message)
        runtime.turnStarted()
    }

    /** As on the real runtime: the last callback reports CANCELLED after the request. */
    override fun cancel() {
        cancelled = true
        sink?.onError(NativeFailure(null, "CANCELLED: Task cancelled"))
        finishedBeforeClose = true
    }

    override fun tokenCount(): Int = 0

    override fun close() {
        closed = true
        runtime.event("conversation closed")
    }
}
