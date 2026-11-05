package io.github.johnrocky.litertlm.genai.prompt

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.DownloadStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.common.platformContext
import io.github.johnrocky.litertlm.genai.internal.GuardedClient
import io.github.johnrocky.litertlm.genai.internal.LocalGenerativeModel
import io.github.johnrocky.litertlm.genai.internal.PromptClient
import io.github.johnrocky.litertlm.genai.internal.clientFor
import io.github.johnrocky.litertlm.genai.internal.noGeminiNano
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/** Creates a [GenerativeModel]. Same call as `com.google.mlkit.genai.prompt.Generation`, plus the provider. */
public object Generation {
    public fun getClient(): GenerativeModel = getClient(GenerationConfig.DEFAULT, ModelProvider.Auto())

    public fun getClient(config: GenerationConfig): GenerativeModel = getClient(config, ModelProvider.Auto())

    public fun getClient(provider: ModelProvider): GenerativeModel = getClient(GenerationConfig.DEFAULT, provider)

    public fun getClient(config: GenerationConfig, provider: ModelProvider): GenerativeModel = GenerativeModel(
        clientFor(
            context = platformContext(),
            provider = provider,
            nano = { it.generativeModel(config) },
            local = { LocalGenerativeModel(it) },
        ),
    )
}

/**
 * Custom prompts, text or text and images. Same methods as `com.google.mlkit.genai.prompt.GenerativeModel`, without
 * structured output, caches and tools.
 */
public class GenerativeModel internal constructor(
    private val client: GuardedClient<GenerateContentRequest, GenerateContentResponse>,
) : AutoCloseable {
    /** Who answered last, or who [checkStatus] chose. `null` before either. */
    public val activeProvider: ActiveProvider? get() = client.activeProvider

    /** Why [activeProvider] was chosen, for logs. */
    public val activeProviderReason: String? get() = client.activeProviderReason

    /** One of [io.github.johnrocky.litertlm.genai.common.FeatureStatus]. */
    public suspend fun checkStatus(): Int = client.checkFeatureStatus()

    /** Downloads what the model needs. A call while a download is running follows that download. */
    public fun download(): Flow<DownloadStatus> = callbackFlow {
        val job = launch {
            client.downloadFeature(
                object : DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) {
                        trySend(DownloadStatus.DownloadStarted(bytesToDownload))
                    }

                    override fun onDownloadProgress(totalBytesDownloaded: Long) {
                        trySend(DownloadStatus.DownloadProgress(totalBytesDownloaded))
                    }

                    override fun onDownloadFailed(e: GenAiException) {
                        trySend(DownloadStatus.DownloadFailed(e))
                    }

                    override fun onDownloadCompleted() {
                        trySend(DownloadStatus.DownloadCompleted)
                    }
                },
            )
            channel.close()
        }
        awaitClose { job.cancel() }
    }.buffer(Channel.UNLIMITED)

    /** Loads the model now instead of at the first request, downloading it first if needed. */
    public suspend fun warmup(): Unit = client.prepareInferenceEngine()

    /** The name of the base model: Gemini Nano's version, or the local model's file name. After a request, whoever answered. */
    public suspend fun getBaseModelName(): String = client.getBaseModelName()

    /**
     * The total limit for input and output tokens: Gemini Nano's, or [ModelProvider.LiteRtLm.maxNumTokens].
     */
    public suspend fun getTokenLimit(): Int = prompt { it.getTokenLimit() }

    /**
     * The tokens of the input.
     *
     * @throws GenAiException with [GenAiException.ErrorCode.NOT_SUPPORTED] on a local model: LiteRT-LM's Kotlin API
     *   has no tokenizer call.
     */
    public suspend fun countTokens(request: GenerateContentRequest): CountTokensResponse =
        prompt { CountTokensResponse(it.countTokens(request)) }

    /** The optional features that work here, as [Feature] values. A local model has [Feature.SYSTEM_PROMPT]. */
    public suspend fun getAvailableFeatures(): List<Int> = prompt { it.getAvailableFeatures() }

    /** Whether [SystemInstruction] works here. Gemini Nano needs version 3 or later. */
    public suspend fun isSystemPromptAvailable(): Boolean = Feature.SYSTEM_PROMPT in getAvailableFeatures()

    public suspend fun generateContent(request: GenerateContentRequest): GenerateContentResponse =
        client.runInference(request, null)

    public suspend fun generateContent(prompt: String): GenerateContentResponse = generateContent(request(prompt))

    public suspend fun generateContent(
        request: GenerateContentRequest,
        streamingCallback: StreamingCallback,
    ): GenerateContentResponse = client.runInference(request, streamingCallback)

    public suspend fun generateContent(prompt: String, streamingCallback: StreamingCallback): GenerateContentResponse =
        generateContent(request(prompt), streamingCallback)

    /**
     * Each item has the next piece of the reply; the last has [Candidate.finishReason]. Cancelling the collector stops
     * the model.
     */
    public fun generateContentStream(request: GenerateContentRequest): Flow<GenerateContentResponse> = channelFlow {
        val whole = client.runInference(
            request,
            StreamingCallback { text ->
                val provider = client.activeProvider ?: ActiveProvider.LITERT_LM
                trySend(GenerateContentResponse(listOf(Candidate(text, null)), provider))
            },
        )
        // The last item has no text: it says why the reply ended.
        val finishReason = whole.candidates.firstOrNull()?.finishReason
        trySend(GenerateContentResponse(listOf(Candidate("", finishReason)), whole.activeProvider))
    }.buffer(Channel.UNLIMITED)

    public fun generateContentStream(prompt: String): Flow<GenerateContentResponse> = generateContentStream(request(prompt))

    /** See [io.github.johnrocky.litertlm.genai.summarization.Summarizer.close]. */
    override fun close(): Unit = client.close()

    private fun request(prompt: String) = GenerateContentRequest.Builder(TextPart(prompt)).build()

    private suspend fun <T> prompt(block: suspend (PromptClient) -> T): T = client.withChosen { chosen ->
        // Only the stand-in for a missing Gemini Nano is not a PromptClient.
        block(chosen as? PromptClient ?: throw noGeminiNano())
    }
}

/** Same as `com.google.mlkit.genai.prompt.GenerationConfig`, without the executor. */
public class GenerationConfig private constructor(public val modelConfig: ModelConfig?) {
    public class Builder {
        /** `null` leaves the choice to Gemini Nano's default. */
        public var modelConfig: ModelConfig? = null

        public fun build(): GenerationConfig = GenerationConfig(modelConfig)
    }

    public companion object {
        public val DEFAULT: GenerationConfig = Builder().build()

        public fun builder(): Builder = Builder()
    }
}

public fun generationConfig(init: GenerationConfig.Builder.() -> Unit): GenerationConfig =
    GenerationConfig.Builder().apply(init).build()

/** Which Gemini Nano to use. A local model ignores it. `null` leaves the choice to Gemini Nano's default. */
public class ModelConfig private constructor(public val releaseStage: Int?, public val preference: Int?) {
    public class Builder {
        public var releaseStage: Int? = null
        public var preference: Int? = null

        public fun build(): ModelConfig = ModelConfig(releaseStage, preference)
    }

    public companion object {
        public val DEFAULT: ModelConfig = Builder().build()

        public fun builder(): Builder = Builder()
    }
}

public fun modelConfig(init: ModelConfig.Builder.() -> Unit): ModelConfig = ModelConfig.Builder().apply(init).build()

public object ModelReleaseStage {
    public const val STABLE: Int = 0
    public const val PREVIEW: Int = 1
}

public object ModelPreference {
    public const val FAST: Int = 1
    public const val FULL: Int = 2
}

/** The optional features of a model, with the same numbers as `com.google.mlkit.genai.prompt.Feature`. */
public object Feature {
    public const val SYSTEM_PROMPT: Int = 1
    public const val THINKING_MODE: Int = 2
    public const val PREFIX_CACHING: Int = 3
    public const val STRUCTURED_OUTPUT: Int = 4
    public const val FUNCTION_CALLING: Int = 5
}

/** @property totalTokens The tokens of the input. */
public data class CountTokensResponse(val totalTokens: Int)
