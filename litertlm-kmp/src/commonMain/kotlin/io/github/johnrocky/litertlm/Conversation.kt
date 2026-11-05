package io.github.johnrocky.litertlm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * A conversation with the model. The names follow `com.google.ai.edge.litertlm.Conversation`.
 *
 * One turn runs at a time; another turn waits for it. A turn that fails or is cancelled leaves no trace: the
 * next turn starts from the turns that finished.
 */
public interface Conversation : AutoCloseable {
    /** `false` after [close]. */
    public val isAlive: Boolean

    /**
     * Sends a message and returns the whole reply.
     *
     * @throws LiteRtLmException.InputTooLong if the input does not fit in the context window.
     * @throws LiteRtLmException.InvalidImage if the bytes of a [Content.ImageBytes] are not an image.
     * @throws LiteRtLmException.BackendUnavailable if a message has an image and [EngineConfig.visionBackend] is `null`.
     * @throws LiteRtLmException.Cancelled after [cancelProcess] or a close.
     * @throws LiteRtLmException.ClosedUse if the conversation is closed.
     */
    public suspend fun sendMessage(message: Message): Message

    public suspend fun sendMessage(contents: Contents): Message = sendMessage(Message.user(contents))

    public suspend fun sendMessage(text: String): Message = sendMessage(Contents.of(text))

    /**
     * Sends a message when the flow is collected and emits the reply as it is generated. No chunk is dropped
     * however slow the collector is. Cancelling the collector stops the model.
     *
     * The flow throws what [sendMessage] throws.
     */
    public fun sendMessageAsync(message: Message): Flow<Message>

    public fun sendMessageAsync(contents: Contents): Flow<Message> = sendMessageAsync(Message.user(contents))

    public fun sendMessageAsync(text: String): Flow<Message> = sendMessageAsync(Contents.of(text))

    /** Sends a message now and reports to [callback], on a background thread. */
    public fun sendMessageAsync(message: Message, callback: MessageCallback)

    public fun sendMessageAsync(contents: Contents, callback: MessageCallback): Unit =
        sendMessageAsync(Message.user(contents), callback)

    public fun sendMessageAsync(text: String, callback: MessageCallback): Unit =
        sendMessageAsync(Contents.of(text), callback)

    /**
     * Stops the running turn, which then fails with [LiteRtLmException.Cancelled]. Does nothing if no turn is
     * running. The conversation stays usable, and the next turn may take longer because the runtime has to read
     * the history again.
     */
    public fun cancelProcess()

    /**
     * The number of tokens in the context window: all input and output so far. After a cancelled or failed turn it is
     * 0 until the next turn has read the history again.
     */
    public suspend fun getTokenCount(): Int

    /**
     * Stops a running turn and releases the conversation. A second call does nothing. It blocks until the reply
     * has stopped, so call it off the main thread if a reply may be running.
     */
    override fun close()
}

/** Receives a reply as it is generated. */
public interface MessageCallback {
    public fun onMessage(message: Message)

    public fun onDone()

    public fun onError(throwable: Throwable)
}

/** Sends [text] when the flow is collected and emits the reply's text as it is generated. */
public fun Conversation.stream(text: String): Flow<String> =
    sendMessageAsync(text).map { it.toString() }.filter { it.isNotEmpty() }
