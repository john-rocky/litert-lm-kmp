@file:OptIn(NanoAdapterApi::class)

package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.FeatureStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriberOptions
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionRequest
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionResult
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentRequest
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentResponse
import io.github.johnrocky.litertlm.genai.prompt.GenerationConfig
import io.github.johnrocky.litertlm.genai.proofreading.ProofreaderOptions
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingRequest
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingResult
import io.github.johnrocky.litertlm.genai.rewriting.RewriterOptions
import io.github.johnrocky.litertlm.genai.rewriting.RewritingRequest
import io.github.johnrocky.litertlm.genai.rewriting.RewritingResult
import io.github.johnrocky.litertlm.genai.spi.NanoAdapter
import io.github.johnrocky.litertlm.genai.spi.NanoAdapterApi
import io.github.johnrocky.litertlm.genai.summarization.SummarizationRequest
import io.github.johnrocky.litertlm.genai.summarization.SummarizationResult
import io.github.johnrocky.litertlm.genai.summarization.SummarizerOptions

/** What every provider does for one client. All the public clients are this, under their ML Kit names. */
internal interface FeatureClient<Req, Res> {
    val activeProvider: ActiveProvider?
    val activeProviderReason: String?

    suspend fun checkFeatureStatus(): Int

    /** Failures go to the callback. Returns when the download has finished or failed. */
    suspend fun downloadFeature(callback: DownloadCallback)

    suspend fun prepareInferenceEngine()

    suspend fun getBaseModelName(): String

    suspend fun runInference(request: Req, callback: StreamingCallback?): Res

    /** The client that would answer now: this one, unless it routes. */
    suspend fun chosen(): FeatureClient<Req, Res> = this

    /** Must not block. */
    fun close()
}

/** The calls that only the prompt API has. */
internal interface PromptClient : FeatureClient<GenerateContentRequest, GenerateContentResponse> {
    suspend fun getTokenLimit(): Int

    suspend fun countTokens(request: GenerateContentRequest): Int

    suspend fun getAvailableFeatures(): List<Int>
}

/** Gemini Nano, where it exists. */
internal interface NanoClients {
    fun summarizer(options: SummarizerOptions): FeatureClient<SummarizationRequest, SummarizationResult>

    fun proofreader(options: ProofreaderOptions): FeatureClient<ProofreadingRequest, ProofreadingResult>

    fun rewriter(options: RewriterOptions): FeatureClient<RewritingRequest, RewritingResult>

    fun imageDescriber(options: ImageDescriberOptions): FeatureClient<ImageDescriptionRequest, ImageDescriptionResult>

    fun generativeModel(config: GenerationConfig): PromptClient
}

/** `litertlm-genai-nano`, if the app has it. `null` on iOS and the JVM. */
internal expect fun findNanoAdapter(): NanoAdapter?

/** Why there is no Gemini Nano when [findNanoAdapter] finds nothing. */
internal expect val noNanoReason: String

internal const val NO_NANO_ON_THIS_PLATFORM = "Gemini Nano runs only on Android."

internal const val NO_NANO_ARTIFACT = "This app does not have litertlm-genai-nano, which Gemini Nano needs. Add " +
    "implementation(\"io.github.john-rocky.litertlm:litertlm-genai-nano:<the version of litertlm-genai>\") to " +
    "androidMain.dependencies."

internal fun noGeminiNano(reason: String = noNanoReason): GenAiException =
    GenAiException(reason, null, GenAiException.ErrorCode.NOT_AVAILABLE)

/** [ModelProvider.GeminiNano] where there is none. */
internal class NoGeminiNanoClient<Req, Res>(private val reason: String) : FeatureClient<Req, Res> {
    override val activeProvider: ActiveProvider? = null
    override val activeProviderReason: String = reason

    override suspend fun checkFeatureStatus(): Int = FeatureStatus.UNAVAILABLE

    override suspend fun downloadFeature(callback: DownloadCallback) = callback.onDownloadFailed(noGeminiNano(reason))

    override suspend fun prepareInferenceEngine() = throw noGeminiNano(reason)

    override suspend fun getBaseModelName(): String = throw noGeminiNano(reason)

    override suspend fun runInference(request: Req, callback: StreamingCallback?): Res = throw noGeminiNano(reason)

    override fun close() {}
}

/** Builds the client for [provider], with the Gemini Nano that this app and platform have. */
internal fun <Req, Res> clientFor(
    context: PlatformContext,
    provider: ModelProvider,
    nano: (NanoClients) -> FeatureClient<Req, Res>,
    local: (LocalRunner) -> FeatureClient<Req, Res>,
): GuardedClient<Req, Res> = clientFor(context, provider, nano, local, findNanoAdapter(), noNanoReason)

/**
 * @param adapter Gemini Nano, if there is one.
 * @param noAdapterReason What to say if there is none.
 */
internal fun <Req, Res> clientFor(
    context: PlatformContext,
    provider: ModelProvider,
    nano: (NanoClients) -> FeatureClient<Req, Res>,
    local: (LocalRunner) -> FeatureClient<Req, Res>,
    adapter: NanoAdapter?,
    noAdapterReason: String,
): GuardedClient<Req, Res> = GuardedClient(
    when (provider) {
        ModelProvider.GeminiNano -> adapter?.let { nano(AdapterNanoClients(it, context)) } ?: NoGeminiNanoClient(noAdapterReason)
        is ModelProvider.LiteRtLm -> local(
            LocalRunner.create(
                context,
                provider.local,
                provider.backend,
                provider.visionBackend,
                provider.maxNumTokens,
                provider.cacheDir,
                provider.minAvailableMemoryBytes,
            ),
        )
        is ModelProvider.Auto -> AutoRouter(
            nano = adapter?.let { nano(AdapterNanoClients(it, context)) },
            local = local(
                LocalRunner.create(
                    context,
                    provider.local,
                    provider.backend,
                    provider.visionBackend,
                    provider.maxNumTokens,
                    provider.cacheDir,
                    provider.minAvailableMemoryBytes,
                ),
            ),
            busyRetries = provider.busyRetries,
            noNano = noAdapterReason,
        )
    },
)
