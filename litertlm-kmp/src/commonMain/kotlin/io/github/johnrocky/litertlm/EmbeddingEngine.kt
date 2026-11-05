package io.github.johnrocky.litertlm

import io.github.johnrocky.litertlm.internal.DefaultEmbeddingEngine
import io.github.johnrocky.litertlm.internal.platformRuntime

/** A loaded embedding model. The names follow `com.google.ai.edge.litertlm.EmbeddingEngine`. */
public interface EmbeddingEngine : AutoCloseable {
    public val config: EmbeddingEngineConfig

    public fun isInitialized(): Boolean

    /** Checks the file and the available memory, then loads the model. Throws what [Engine.initialize] throws. */
    public suspend fun initialize()

    /**
     * Computes one embedding for [contents].
     *
     * @throws LiteRtLmException.NotInitialized before [initialize].
     * @throws LiteRtLmException.ClosedUse if the engine is closed.
     */
    public suspend fun computeEmbedding(
        contents: List<InputData>,
        options: EmbeddingOptions = EmbeddingOptions(),
    ): EmbeddingResponse

    /** Releases the model. A second call does nothing. */
    override fun close()
}

/** Creates an [EmbeddingEngine]. Nothing is loaded until [EmbeddingEngine.initialize]. */
public fun EmbeddingEngine(config: EmbeddingEngineConfig): EmbeddingEngine =
    DefaultEmbeddingEngine(config, platformRuntime())

/**
 * Configuration for an [EmbeddingEngine].
 *
 * @property modelPath The path to a `.litertlm` embedding model.
 * @property backend Where the model runs.
 * @property cacheDir A writable directory for compiled caches. `null` uses the model file's directory.
 * @property maxInputLength The longest input, in tokens. `null` uses the model's default.
 * @property minAvailableMemoryBytes See [EngineConfig.minAvailableMemoryBytes].
 */
public data class EmbeddingEngineConfig(
    val modelPath: String,
    val backend: Backend = Backend.CPU(),
    val cacheDir: String? = null,
    val maxInputLength: Int? = null,
    val minAvailableMemoryBytes: Long? = null,
)

/** An input to embed. Images and audio are not in this version. */
public sealed class InputData {
    public data class Text(val text: String) : InputData()
}

/**
 * Options for one embedding. `null` uses the runtime's default.
 *
 * @property normalize Whether to L2-normalize the vector.
 * @property insertSpecialTokens Whether to add the model's special tokens.
 * @property outputSize The size to truncate the vector to.
 */
public data class EmbeddingOptions(
    val normalize: Boolean? = null,
    val insertSpecialTokens: Boolean? = null,
    val outputSize: Int? = null,
)

/** One embedding. */
public class EmbeddingResponse(public val embedding: FloatArray) {
    override fun equals(other: Any?): Boolean = other is EmbeddingResponse && embedding.contentEquals(other.embedding)

    override fun hashCode(): Int = embedding.contentHashCode()
}
