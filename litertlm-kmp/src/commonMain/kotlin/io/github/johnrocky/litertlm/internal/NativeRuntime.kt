package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.EmbeddingOptions
import io.github.johnrocky.litertlm.EmbeddingResponse
import io.github.johnrocky.litertlm.InputData
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.SamplerConfig
import io.github.johnrocky.litertlm.ToolProvider

/**
 * What differs between platforms: Google's Kotlin API on Android and the JVM, the C API on iOS. Everything above
 * this line (lifecycle, history, error types, file and memory checks) is the same code on all three.
 *
 * Implementations throw [NativeFailure]. Calls on one handle never overlap (see "Threading model" in the C API's
 * engine.h); the callers in this package guarantee that.
 */
internal interface NativeRuntime {
    /**
     * Throws a [io.github.johnrocky.litertlm.LiteRtLmException] if the runtime is not there. Only an iOS app can lack
     * it, because the app adds it. The first call on a runtime, before the checks of the model file: a missing runtime
     * is the problem to report even when the file is missing too.
     */
    fun checkAvailable() {}

    fun createEngine(settings: NativeEngineSettings): NativeEngine

    fun createEmbeddingEngine(settings: NativeEmbeddingSettings): NativeEmbeddingEngine
}

internal class NativeEngineSettings(
    val modelPath: String,
    val backend: Backend,
    val visionBackend: Backend?,
    val maxNumTokens: Int?,
    val cacheDir: String?,
)

internal class NativeConversationSettings(
    val systemInstruction: Contents?,
    val messages: List<Message>,
    val tools: List<ToolProvider>,
    val sampler: SamplerConfig?,
)

internal class NativeEmbeddingSettings(
    val modelPath: String,
    val backend: Backend,
    val cacheDir: String?,
    val maxInputLength: Int?,
)

internal interface NativeEngine {
    fun createConversation(settings: NativeConversationSettings): NativeConversation

    fun close()
}

internal interface NativeConversation {
    /**
     * Starts a turn and returns without waiting for the reply. [sink] gets exactly one terminal call. It can run on
     * any thread, and on this thread before this function returns.
     */
    fun sendMessageStream(message: Message, sink: NativeStreamSink)

    /** Only requests the stop: [sink] can still run after this returns. Called only while a turn is running. */
    fun cancel()

    fun tokenCount(): Int

    fun close()
}

internal interface NativeStreamSink {
    fun onChunk(message: Message)

    fun onDone()

    fun onError(failure: NativeFailure)
}

internal interface NativeEmbeddingEngine {
    fun computeEmbedding(contents: List<InputData>, options: EmbeddingOptions): EmbeddingResponse

    fun close()
}

/** An error from the native layer. [code] is the canonical status code when the layer gives one as a number. */
internal class NativeFailure(val code: Int?, override val message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal expect fun platformRuntime(): NativeRuntime
