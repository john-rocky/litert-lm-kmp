@file:OptIn(NanoAdapterApi::class)

package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriberOptions
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionRequest
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionResult
import io.github.johnrocky.litertlm.genai.prompt.Candidate
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentRequest
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentResponse
import io.github.johnrocky.litertlm.genai.prompt.GenerationConfig
import io.github.johnrocky.litertlm.genai.proofreading.ProofreaderOptions
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingRequest
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingResult
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingSuggestion
import io.github.johnrocky.litertlm.genai.rewriting.RewriterOptions
import io.github.johnrocky.litertlm.genai.rewriting.RewritingRequest
import io.github.johnrocky.litertlm.genai.rewriting.RewritingResult
import io.github.johnrocky.litertlm.genai.rewriting.RewritingSuggestion
import io.github.johnrocky.litertlm.genai.spi.NanoAdapter
import io.github.johnrocky.litertlm.genai.spi.NanoAdapterApi
import io.github.johnrocky.litertlm.genai.spi.NanoCandidate
import io.github.johnrocky.litertlm.genai.spi.NanoClient
import io.github.johnrocky.litertlm.genai.spi.NanoImage
import io.github.johnrocky.litertlm.genai.spi.NanoPromptClient
import io.github.johnrocky.litertlm.genai.summarization.SummarizationRequest
import io.github.johnrocky.litertlm.genai.summarization.SummarizationResult
import io.github.johnrocky.litertlm.genai.summarization.SummarizerOptions

/** A feature of the adapter as a client like every other provider's. The adapter's text becomes a result in [answer]. */
private open class NanoFeature<Req, Res, NanoReq, NanoRes>(
    private val client: NanoClient<NanoReq, NanoRes>,
    private val answer: suspend (NanoClient<NanoReq, NanoRes>, Req, StreamingCallback?) -> Res,
) : FeatureClient<Req, Res> {
    override val activeProvider: ActiveProvider = ActiveProvider.GEMINI_NANO
    override val activeProviderReason: String = "The app chose Gemini Nano."

    override suspend fun checkFeatureStatus(): Int = client.checkFeatureStatus()

    override suspend fun downloadFeature(callback: DownloadCallback) = client.downloadFeature(callback)

    override suspend fun prepareInferenceEngine() = client.prepareInferenceEngine()

    override suspend fun getBaseModelName(): String = client.getBaseModelName()

    override suspend fun runInference(request: Req, callback: StreamingCallback?): Res = answer(client, request, callback)

    override fun close() = client.close()
}

private class NanoPrompt(private val prompt: NanoPromptClient) :
    NanoFeature<GenerateContentRequest, GenerateContentResponse, GenerateContentRequest, List<NanoCandidate>>(
        prompt,
        { client, request, callback ->
            GenerateContentResponse(
                client.runInference(request, callback).map { Candidate(it.text, it.finishReason) },
                ActiveProvider.GEMINI_NANO,
            )
        },
    ),
    PromptClient {
    override suspend fun getTokenLimit(): Int = prompt.getTokenLimit()

    override suspend fun countTokens(request: GenerateContentRequest): Int = prompt.countTokens(request)

    override suspend fun getAvailableFeatures(): List<Int> = prompt.getAvailableFeatures()
}

private fun StreamingCallback.deliver(text: String) {
    if (text.isNotEmpty()) onNewText(text)
}

/** Gemini Nano through the adapter: the results in this library's types, and in the same format as a local model's. */
internal class AdapterNanoClients(private val adapter: NanoAdapter, private val context: PlatformContext) : NanoClients {
    override fun summarizer(options: SummarizerOptions): FeatureClient<SummarizationRequest, SummarizationResult> = NanoFeature(
        adapter.summarizer(
            context,
            options.inputType,
            options.outputType,
            options.language,
            options.longInputAutoTruncationEnabled,
        ),
    ) { client, request, callback ->
        val bullets = bulletCount(options.outputType)
        val live = BulletFilter(bullets)
        val whole = client.runInference(request.text, callback?.let { StreamingCallback { text -> it.deliver(live.accept(text)) } })
        callback?.deliver(live.finish())
        // nano-v4-full writes a single bullet without the marker that ML Kit's reference promises, and the local model
        // writes it: the same format from every provider is what lets an app switch between them.
        val filter = BulletFilter(bullets)
        SummarizationResult(filter.accept(whole) + filter.finish(), ActiveProvider.GEMINI_NANO)
    }

    override fun proofreader(options: ProofreaderOptions): FeatureClient<ProofreadingRequest, ProofreadingResult> = NanoFeature(
        adapter.proofreader(context, options.inputType, options.language),
    ) { client, request, callback ->
        ProofreadingResult(client.runInference(request.text, callback).map(::ProofreadingSuggestion), ActiveProvider.GEMINI_NANO)
    }

    override fun rewriter(options: RewriterOptions): FeatureClient<RewritingRequest, RewritingResult> = NanoFeature(
        adapter.rewriter(context, options.outputType, options.language),
    ) { client, request, callback ->
        RewritingResult(client.runInference(request.text, callback).map(::RewritingSuggestion), ActiveProvider.GEMINI_NANO)
    }

    override fun imageDescriber(options: ImageDescriberOptions): FeatureClient<ImageDescriptionRequest, ImageDescriptionResult> =
        NanoFeature(adapter.imageDescriber(context)) { client, request, callback ->
            val image = NanoImage(request.platformImage, request.bytes)
            ImageDescriptionResult(client.runInference(image, callback), ActiveProvider.GEMINI_NANO)
        }

    override fun generativeModel(config: GenerationConfig): PromptClient = NanoPrompt(adapter.generativeModel(config))
}
