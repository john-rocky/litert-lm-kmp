package io.github.johnrocky.litertlm

/** Where the model runs. The names follow `com.google.ai.edge.litertlm.Backend`. */
public sealed class Backend(public val name: String) {
    /** @property threadCount The number of threads. `null` or 0 uses the runtime's default. */
    public data class CPU(val threadCount: Int? = null) : Backend("CPU")

    /** Metal on iOS, OpenCL on Android, the desktop GPU on the JVM. */
    public class GPU : Backend("GPU") {
        override fun equals(other: Any?): Boolean = other is GPU
        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = "GPU"
    }
}

/**
 * Configuration for an [Engine].
 *
 * @property modelPath The path to a `.litertlm` file.
 * @property backend Where the model runs.
 * @property visionBackend Where the vision part of a multimodal model runs. `null` means no images:
 *   a message with [Content.ImageBytes] then fails with [LiteRtLmException.BackendUnavailable].
 * @property maxNumTokens The size of the context window: input and output tokens together. `null` uses the model's default.
 * @property cacheDir A writable directory for compiled caches. `null` uses the model file's directory.
 * @property minAvailableMemoryBytes [Engine.initialize] throws [LiteRtLmException.InsufficientMemory] below this.
 *   `null` uses half the model file's size on Android and iOS, or three quarters of it with a [visionBackend], and no check
 *   on the JVM. On iOS it also throws when the process has too little address space for the file. 0 turns both off.
 */
public data class EngineConfig(
    val modelPath: String,
    val backend: Backend = Backend.CPU(),
    val visionBackend: Backend? = null,
    val maxNumTokens: Int? = null,
    val cacheDir: String? = null,
    val minAvailableMemoryBytes: Long? = null,
) {
    init {
        require(maxNumTokens == null || maxNumTokens > 0) { "maxNumTokens must be positive or null." }
    }
}

/**
 * Configuration for a [Conversation].
 *
 * @property systemInstruction The system instruction.
 * @property initialMessages Earlier turns. They add to the history without running the model.
 * @property tools Tools the model may call. See [tool].
 * @property samplerConfig The sampler. `null` uses the model's default.
 * @property automaticToolCalling Must stay `false`: tool calls come back in [Message.toolCalls] for the caller to
 *   run, and the result goes back with [Message.tool].
 */
public data class ConversationConfig(
    val systemInstruction: Contents? = null,
    val initialMessages: List<Message> = listOf(),
    val tools: List<ToolProvider> = listOf(),
    val samplerConfig: SamplerConfig? = null,
    val automaticToolCalling: Boolean = false,
) {
    init {
        require(!automaticToolCalling) {
            "automaticToolCalling is not supported: run the tool from Message.toolCalls and send Message.tool."
        }
    }
}

/**
 * The sampler.
 *
 * @property topK The number of top logits used.
 * @property topP The cumulative probability threshold.
 * @property temperature The temperature. 0 or more.
 * @property seed The random seed.
 */
public data class SamplerConfig(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int = 0,
) {
    init {
        require(topK > 0) { "topK should be positive, but got $topK." }
        require(topP in 0.0..1.0) { "topP should be between 0 and 1 inclusively, but got $topP." }
        require(temperature >= 0) { "temperature should be non-negative, but got $temperature." }
    }
}
