package io.github.johnrocky.litertlm

import io.github.johnrocky.litertlm.internal.DefaultEngine
import io.github.johnrocky.litertlm.internal.platformRuntime

/**
 * A loaded model. The names follow `com.google.ai.edge.litertlm.Engine`:
 *
 * ```kotlin
 * val engine = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 2048))
 * engine.initialize()
 * val conversation = engine.createConversation()
 * println(conversation.sendMessage("Hello"))
 * engine.close()
 * ```
 *
 * The suspending calls do their work off the caller's thread.
 */
public interface Engine : AutoCloseable {
    public val engineConfig: EngineConfig

    public fun isInitialized(): Boolean

    /**
     * Checks the file and the available memory, then loads the model.
     *
     * @throws LiteRtLmException.Internal if the runtime fails. On iOS also if the app does not have the runtime, before
     *   the other checks: the message says to add the Swift package.
     * @throws LiteRtLmException.ModelNotFound if the file does not exist.
     * @throws LiteRtLmException.InvalidModel if the file is not a whole `.litertlm` file.
     * @throws LiteRtLmException.InsufficientMemory if less than [EngineConfig.minAvailableMemoryBytes] is available, or on
     *   iOS if the process has too little address space for the file.
     * @throws LiteRtLmException.BackendUnavailable if the backend cannot run this model.
     * @throws LiteRtLmException.AlreadyInitialized if it was already called with success.
     * @throws LiteRtLmException.ClosedUse if the engine is closed.
     */
    public suspend fun initialize()

    /**
     * @throws LiteRtLmException.NotInitialized before [initialize].
     * @throws LiteRtLmException.ClosedUse if the engine is closed.
     */
    public suspend fun createConversation(conversationConfig: ConversationConfig = ConversationConfig()): Conversation

    /**
     * Stops and closes every conversation of this engine, then releases the model. A second call does nothing.
     * It blocks until a running reply has stopped, so call it off the main thread if a reply may be running.
     */
    override fun close()
}

/** Creates an [Engine]. Nothing is loaded until [Engine.initialize]. */
public fun Engine(engineConfig: EngineConfig): Engine = DefaultEngine(engineConfig, platformRuntime())

/** Creates an [Engine] with the default settings. */
public fun Engine(modelPath: String, backend: Backend = Backend.CPU()): Engine =
    Engine(EngineConfig(modelPath = modelPath, backend = backend))
