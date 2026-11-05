package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.Content
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.Conversation
import io.github.johnrocky.litertlm.ConversationConfig
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.MessageCallback
import io.github.johnrocky.litertlm.ToolCall
import kotlin.time.Duration.Companion.minutes
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a close waits for the runtime's last callback before it gives up and leaks the native objects: deleting
 * them while a callback may still run would be a use after free. Cancelling can wait for a prefill batch, which is
 * slow on a slow CPU, so the limit is generous.
 */
private val LAST_CALLBACK_WAIT = 2.minutes

/**
 * Every turn goes through the runtime's stream, sendMessage too, for three reasons: a stream can be cancelled without
 * overlapping another call on the handle (C API "Threading model"); the runtime's blocking send has a fixed 10-minute
 * limit past which its callback writes to a dead stack frame (LiteRT-LM v0.19.0 Conversation::SendMessage); and
 * there is one code path to get right.
 *
 * The native conversation is dead after a cancel (the C API says not to reuse it, and on v0.19.0 it answers CANCELLED
 * forever) and after a failure ("Task failed with state: 7"). So the finished turns are kept here, and the next turn
 * starts a new native conversation from them.
 */
internal class DefaultConversation(
    private val engine: DefaultEngine,
    private val config: ConversationConfig,
    initialNative: NativeConversation,
) : Conversation {
    /** Guards the fields below; never held across a blocking wait. */
    private val lock = SynchronizedObject()
    private var native: NativeConversation? = initialNative
    private var activeTurn: Turn? = null
    private var closed = false

    /** One turn at a time. Held until the turn's last callback has run and its native conversation is settled. */
    private val turns = Mutex()

    /** The finished turns. Read and written with [turns] held. */
    private val history: MutableList<Message> = config.initialMessages.toMutableList()

    private val closeDone = CompletableDeferred<Unit>()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val isAlive: Boolean get() = synchronized(lock) { !closed }

    override suspend fun sendMessage(message: Message): Message = withContext(Dispatchers.IO) {
        turns.withLock { runTurn(message) {} }
    }

    // flowOn: the turn must not need the collector's thread, because close() blocks waiting for it and may be called
    // on that thread. buffer: the runtime's callback must return quickly and cannot wait for a slow collector.
    override fun sendMessageAsync(message: Message): Flow<Message> = channelFlow {
        turns.withLock { runTurn(message) { chunk -> trySend(chunk) } }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    override fun sendMessageAsync(message: Message, callback: MessageCallback) {
        callbackScope.launch {
            try {
                sendMessageAsync(message).collect { callback.onMessage(it) }
            } catch (t: Throwable) {
                callback.onError(t)
                return@launch
            }
            callback.onDone()
        }
    }

    override fun cancelProcess() {
        synchronized(lock) { activeTurn?.let { requestCancelLocked(it) } }
    }

    private fun requestCancelLocked(turn: Turn) {
        if (turn.cancelRequested) return
        turn.cancelRequested = true
        native?.cancel()
    }

    override suspend fun getTokenCount(): Int = withContext(Dispatchers.IO) {
        turns.withLock {
            try {
                nativeForNextTurn().tokenCount()
            } catch (f: NativeFailure) {
                throw LiteRtLmException.Internal(f.statusCode(), f.message, f)
            }
        }
    }

    private suspend fun nativeForNextTurn(): NativeConversation {
        synchronized(lock) {
            if (closed) throw LiteRtLmException.ClosedUse("Conversation")
            native?.let { return it }
        }
        val rebuilt = engine.createNativeConversation(config, history.toList())
        val keep = synchronized(lock) {
            if (closed) false else {
                native = rebuilt
                true
            }
        }
        if (!keep) {
            rebuilt.close()
            throw LiteRtLmException.ClosedUse("Conversation")
        }
        return rebuilt
    }

    private suspend fun runTurn(message: Message, onChunk: (Message) -> Unit): Message {
        val conversation = nativeForNextTurn()
        val turn = Turn(onChunk)
        // In the lock so a cancel or a close sees either no turn or a started one, never a call in progress.
        synchronized(lock) {
            if (closed) throw LiteRtLmException.ClosedUse("Conversation")
            try {
                conversation.sendMessageStream(message, turn)
                activeTurn = turn
            } catch (f: NativeFailure) {
                native = null
                conversation.close()
                throw f.toTurnException()
            }
        }
        val failure = try {
            turn.terminal.await()
        } catch (e: CancellationException) {
            // The caller went away. Stop the model, and wait for the last callback before touching the handle.
            synchronized(lock) { requestCancelLocked(turn) }
            withContext(NonCancellable) {
                turn.terminal.await()
            }
            finishTurn(turn, failed = true)
            throw e
        }
        val cancelled = finishTurn(turn, failed = failure != null)
        if (failure != null) {
            throw if (cancelled) LiteRtLmException.Cancelled() else failure.toTurnException()
        }
        val reply = turn.reply()
        history += message
        // Without channels: the runtime leaves them out of what the model sees of a finished turn.
        history += Message.model(reply.contents, reply.toolCalls)
        return reply
    }

    /**
     * Ends the turn and deletes the native conversation if it cannot be used again. Returns whether a cancel reached
     * the turn. One lock covers both, so a cancel is either seen here or finds no turn to cancel.
     */
    private fun finishTurn(turn: Turn, failed: Boolean): Boolean {
        var cancelled = false
        val dead = synchronized(lock) {
            activeTurn = null
            cancelled = turn.cancelRequested
            // A close deletes the native conversation itself, once it has seen this turn end.
            if ((failed || cancelled) && !closed) native.also { native = null } else null
        }
        dead?.close()
        return cancelled
    }

    override fun close() {
        val first = synchronized(lock) {
            if (closed) false else {
                closed = true
                activeTurn?.let { requestCancelLocked(it) }
                true
            }
        }
        if (!first) {
            runBlocking { closeDone.await() }
            return
        }
        val idle = runBlocking { withTimeoutOrNull(LAST_CALLBACK_WAIT) { turns.withLock { true } } ?: false }
        if (idle) {
            synchronized(lock) { native.also { native = null } }?.close()
        }
        engine.onConversationClosed(this, leaked = !idle)
        closeDone.complete(Unit)
    }

    /** One turn: collects the chunks into the whole reply. The native side calls it, from any thread. */
    private class Turn(private val forward: (Message) -> Unit) : NativeStreamSink {
        /** Completed by the last callback: `null` if the turn finished, or what went wrong. */
        val terminal = CompletableDeferred<NativeFailure?>()

        /** Guarded by the conversation's lock. */
        var cancelRequested = false

        private val text = StringBuilder()
        private val toolCalls = ArrayList<ToolCall>()
        private val channels = LinkedHashMap<String, String>()

        override fun onChunk(message: Message) {
            text.append(message.contents.toString())
            toolCalls += message.toolCalls
            message.channels.forEach { (key, value) -> channels[key] = channels[key].orEmpty() + value }
            forward(message)
        }

        override fun onDone() {
            terminal.complete(null)
        }

        override fun onError(failure: NativeFailure) {
            terminal.complete(failure)
        }

        /** Call after [terminal] completed: the callbacks are over. */
        fun reply(): Message {
            val parts = if (text.isEmpty()) emptyList() else listOf<Content>(Content.Text(text.toString()))
            return Message.model(Contents.of(parts), toolCalls.toList(), channels.toMap())
        }
    }
}
