@file:OptIn(NanoAdapterApi::class)

package io.github.johnrocky.litertlm.genai.nano

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback as MlDownloadCallback
import com.google.mlkit.genai.common.DownloadStatus as MlDownloadStatus
import com.google.mlkit.genai.common.GenAiException as MlGenAiException
import com.google.mlkit.genai.common.StreamingCallback as MlStreamingCallback
import com.google.mlkit.genai.imagedescription.ImageDescriber as MlImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions as MlImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescription as MlImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest as MlImageDescriptionRequest
import com.google.mlkit.genai.prompt.Content as MlContent
import com.google.mlkit.genai.prompt.GenerateContentRequest as MlGenerateContentRequest
import com.google.mlkit.genai.prompt.Generation as MlGeneration
import com.google.mlkit.genai.prompt.GenerativeModel as MlGenerativeModel
import com.google.mlkit.genai.prompt.ImagePart as MlImagePart
import com.google.mlkit.genai.prompt.Part as MlPart
import com.google.mlkit.genai.prompt.SystemInstruction as MlSystemInstruction
import com.google.mlkit.genai.prompt.TextPart as MlTextPart
import com.google.mlkit.genai.prompt.generationConfig as mlGenerationConfig
import com.google.mlkit.genai.prompt.modelConfig as mlModelConfig
import com.google.mlkit.genai.proofreading.Proofreader as MlProofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions as MlProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading as MlProofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest as MlProofreadingRequest
import com.google.mlkit.genai.rewriting.Rewriter as MlRewriter
import com.google.mlkit.genai.rewriting.RewriterOptions as MlRewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting as MlRewriting
import com.google.mlkit.genai.rewriting.RewritingRequest as MlRewritingRequest
import com.google.mlkit.genai.summarization.Summarization as MlSummarization
import com.google.mlkit.genai.summarization.SummarizationRequest as MlSummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer as MlSummarizer
import com.google.mlkit.genai.summarization.SummarizerOptions as MlSummarizerOptions
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentRequest
import io.github.johnrocky.litertlm.genai.prompt.GenerationConfig
import io.github.johnrocky.litertlm.genai.prompt.ImagePart
import io.github.johnrocky.litertlm.genai.prompt.Part
import io.github.johnrocky.litertlm.genai.prompt.SystemInstruction
import io.github.johnrocky.litertlm.genai.prompt.TextPart
import io.github.johnrocky.litertlm.genai.spi.NanoAdapter
import io.github.johnrocky.litertlm.genai.spi.NanoAdapterApi
import io.github.johnrocky.litertlm.genai.spi.NanoCandidate
import io.github.johnrocky.litertlm.genai.spi.NanoClient
import io.github.johnrocky.litertlm.genai.spi.NanoImage
import io.github.johnrocky.litertlm.genai.spi.NanoPromptClient
import io.github.johnrocky.litertlm.genai.spi.toNanoImage
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private fun bitmapOf(image: NanoImage): Bitmap {
    (image.platformImage as? Bitmap)?.let { return it }
    val encoded = image.bytes.value
    return BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        ?: throw GenAiException("These bytes are not an image.", null, ErrorCode.INVALID_INPUT_IMAGE)
}

private fun MlGenAiException.toOurs(): GenAiException =
    GenAiException(message, this, errorCode, kotlinDelay(retryDelay))

/** ML Kit declares it non-null, so "no delay given" can only be zero. Null is handled as well, since that is unread. */
private fun kotlinDelay(delay: java.time.Duration?): Duration? = delay?.takeIf { !it.isZero }?.toKotlinDuration()

/** Everything from ML Kit comes out as our exception, so the router and the app need only one kind of handling. */
private inline fun <T> translated(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: GenAiException) {
    throw e
} catch (e: MlGenAiException) {
    throw e.toOurs()
} catch (e: Exception) {
    throw GenAiException(e.message, e, ErrorCode.UNKNOWN)
}

/** The texts reach [callback] on the caller's context, as they do from a local model. */
private suspend fun <T> withStreaming(callback: StreamingCallback?, call: suspend (MlStreamingCallback?) -> T): T {
    if (callback == null) return call(null)
    return coroutineScope {
        val texts = Channel<String>(Channel.UNLIMITED)
        val answer = async {
            try {
                call(MlStreamingCallback { texts.trySend(it) })
            } finally {
                texts.close()
            }
        }
        for (text in texts) callback.onNewText(text)
        answer.await()
    }
}

/** One of ML Kit's four task clients. They have the same calls but no type in common. */
private class NanoTask<Client : Any, Req, Res>(
    create: () -> Client,
    private val status: (Client) -> ListenableFuture<Int>,
    private val download: (Client, MlDownloadCallback) -> ListenableFuture<Void>,
    private val prepare: (Client) -> ListenableFuture<Void>,
    private val baseName: (Client) -> ListenableFuture<String>,
    private val run: suspend (Client, Req, MlStreamingCallback?) -> Res,
    private val closeClient: (Client) -> Unit,
) : NanoClient<Req, Res> {
    private val client = lazy(create)

    override suspend fun checkFeatureStatus(): Int = translated { status(client.value).await() }

    override suspend fun downloadFeature(callback: DownloadCallback): Unit = coroutineScope {
        val events = Channel<(DownloadCallback) -> Unit>(Channel.UNLIMITED)
        launch {
            var failed = false
            try {
                val future = translated {
                    download(
                        client.value,
                        object : MlDownloadCallback {
                            override fun onDownloadStarted(bytesToDownload: Long) {
                                events.trySend { it.onDownloadStarted(bytesToDownload) }
                            }

                            override fun onDownloadProgress(totalBytesDownloaded: Long) {
                                events.trySend { it.onDownloadProgress(totalBytesDownloaded) }
                            }

                            override fun onDownloadFailed(e: MlGenAiException) {
                                failed = true
                                events.trySend { it.onDownloadFailed(e.toOurs()) }
                            }

                            override fun onDownloadCompleted() {
                                events.trySend { it.onDownloadCompleted() }
                            }
                        },
                    )
                }
                translated { future.await() }
            } catch (e: GenAiException) {
                // A failure that ML Kit did not also report to the callback.
                if (!failed) events.trySend { it.onDownloadFailed(e) }
            } finally {
                events.close()
            }
        }
        for (event in events) event(callback)
    }

    override suspend fun prepareInferenceEngine() {
        translated { prepare(client.value).await() }
    }

    override suspend fun getBaseModelName(): String = translated { baseName(client.value).await() }

    override suspend fun runInference(request: Req, callback: StreamingCallback?): Res = translated {
        withStreaming(callback) { streaming -> run(client.value, request, streaming) }
    }

    override fun close() {
        if (client.isInitialized()) closeClient(client.value)
    }
}

private class NanoPrompt(private val config: GenerationConfig) : NanoPromptClient {
    private val client = lazy {
        val modelConfig = config.modelConfig
        if (modelConfig == null) {
            MlGeneration.getClient()
        } else {
            MlGeneration.getClient(
                mlGenerationConfig {
                    this.modelConfig = mlModelConfig {
                        modelConfig.releaseStage?.let { releaseStage = it }
                        modelConfig.preference?.let { preference = it }
                    }
                },
            )
        }
    }
    private val model: MlGenerativeModel get() = client.value

    override suspend fun checkFeatureStatus(): Int = translated { model.checkStatus() }

    override suspend fun downloadFeature(callback: DownloadCallback) {
        try {
            translated {
                model.download().collect { status ->
                    when (status) {
                        is MlDownloadStatus.DownloadStarted -> callback.onDownloadStarted(status.bytesToDownload)
                        is MlDownloadStatus.DownloadProgress -> callback.onDownloadProgress(status.totalBytesDownloaded)
                        is MlDownloadStatus.DownloadFailed -> callback.onDownloadFailed(status.e.toOurs())
                        is MlDownloadStatus.DownloadCompleted -> callback.onDownloadCompleted()
                    }
                }
            }
        } catch (e: GenAiException) {
            callback.onDownloadFailed(e)
        }
    }

    override suspend fun prepareInferenceEngine() = translated { model.warmup() }

    override suspend fun getBaseModelName(): String = translated { model.getBaseModelName() }

    override suspend fun runInference(request: GenerateContentRequest, callback: StreamingCallback?): List<NanoCandidate> =
        translated {
            val mlRequest = request.toMl()
            val response = withStreaming(callback) { streaming ->
                if (streaming == null) model.generateContent(mlRequest) else model.generateContent(mlRequest, streaming)
            }
            response.candidates.map { NanoCandidate(it.text.orEmpty(), it.finishReason) }
        }

    override suspend fun getTokenLimit(): Int = translated { model.getTokenLimit() }

    override suspend fun countTokens(request: GenerateContentRequest): Int =
        translated { model.countTokens(request.toMl()).totalTokens }

    override suspend fun getAvailableFeatures(): List<Int> = translated { model.getAvailableFeatures() }

    override fun close() {
        if (client.isInitialized()) client.value.close()
    }

    /** ML Kit's builder gets only what differs from its defaults, which are ours too. */
    private fun GenerateContentRequest.toMl(): MlGenerateContentRequest {
        val mlContents = contents.map { content ->
            val builder = MlContent.Builder().role(content.role)
            content.parts.forEach { builder.addPart(it.toMl()) }
            builder.build()
        }
        val source = this
        return MlGenerateContentRequest.Builder(mlContents).apply {
            source.systemInstruction?.let { systemInstruction = MlSystemInstruction(it.textString) }
            if (source.temperature != 0f) temperature = source.temperature
            if (source.topK != 3) topK = source.topK
            if (source.seed != 0) seed = source.seed
            if (source.candidateCount != 1) candidateCount = source.candidateCount
            if (source.maxOutputTokens != 0) maxOutputTokens = source.maxOutputTokens
            source.sessionId?.let { sessionId = it }
        }.build()
    }

    private fun Part.toMl(): MlPart = when (this) {
        is TextPart -> MlTextPart(textString)
        is ImagePart -> MlImagePart(bitmapOf(toNanoImage()))
        is SystemInstruction -> MlSystemInstruction(textString)
    }
}

/** Created by `litertlm-genai`, by its name. */
internal class MlKitNanoAdapter : NanoAdapter {
    override fun summarizer(
        context: PlatformContext,
        inputType: Int,
        outputType: Int,
        language: Int,
        longInputAutoTruncationEnabled: Boolean,
    ): NanoClient<String, String> = NanoTask(
        create = {
            MlSummarization.getClient(
                MlSummarizerOptions.builder(context)
                    .setInputType(inputType)
                    .setOutputType(outputType)
                    .setLanguage(language)
                    .setLongInputAutoTruncationEnabled(longInputAutoTruncationEnabled)
                    .build(),
            )
        },
        status = MlSummarizer::checkFeatureStatus,
        download = MlSummarizer::downloadFeature,
        prepare = MlSummarizer::prepareInferenceEngine,
        baseName = MlSummarizer::getBaseModelName,
        run = { client, text, streaming ->
            val request = MlSummarizationRequest.builder(text).build()
            val future = if (streaming == null) client.runInference(request) else client.runInference(request, streaming)
            future.await().summary
        },
        closeClient = MlSummarizer::close,
    )

    override fun proofreader(context: PlatformContext, inputType: Int, language: Int): NanoClient<String, List<String>> = NanoTask(
        create = {
            MlProofreading.getClient(
                MlProofreaderOptions.builder(context)
                    .setInputType(inputType)
                    .setLanguage(language)
                    .build(),
            )
        },
        status = MlProofreader::checkFeatureStatus,
        download = MlProofreader::downloadFeature,
        prepare = MlProofreader::prepareInferenceEngine,
        baseName = MlProofreader::getBaseModelName,
        run = { client, text, streaming ->
            val request = MlProofreadingRequest.builder(text).build()
            val future = if (streaming == null) client.runInference(request) else client.runInference(request, streaming)
            future.await().results.map { it.text }
        },
        closeClient = MlProofreader::close,
    )

    override fun rewriter(context: PlatformContext, outputType: Int, language: Int): NanoClient<String, List<String>> = NanoTask(
        create = {
            MlRewriting.getClient(
                MlRewriterOptions.builder(context)
                    .setOutputType(outputType)
                    .setLanguage(language)
                    .build(),
            )
        },
        status = MlRewriter::checkFeatureStatus,
        download = MlRewriter::downloadFeature,
        prepare = MlRewriter::prepareInferenceEngine,
        baseName = MlRewriter::getBaseModelName,
        run = { client, text, streaming ->
            val request = MlRewritingRequest.builder(text).build()
            val future = if (streaming == null) client.runInference(request) else client.runInference(request, streaming)
            future.await().results.map { it.text }
        },
        closeClient = MlRewriter::close,
    )

    override fun imageDescriber(context: PlatformContext): NanoClient<NanoImage, String> = NanoTask(
        create = { MlImageDescription.getClient(MlImageDescriberOptions.builder(context).build()) },
        status = MlImageDescriber::checkFeatureStatus,
        download = MlImageDescriber::downloadFeature,
        prepare = MlImageDescriber::prepareInferenceEngine,
        baseName = MlImageDescriber::getBaseModelName,
        run = { client, image, streaming ->
            val request = MlImageDescriptionRequest.builder(bitmapOf(image)).build()
            val future = if (streaming == null) client.runInference(request) else client.runInference(request, streaming)
            future.await().description
        },
        closeClient = MlImageDescriber::close,
    )

    override fun generativeModel(config: GenerationConfig): NanoPromptClient = NanoPrompt(config)
}
