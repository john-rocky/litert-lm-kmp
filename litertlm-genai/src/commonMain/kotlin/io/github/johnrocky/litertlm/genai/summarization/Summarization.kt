package io.github.johnrocky.litertlm.genai.summarization

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.common.platformContext
import io.github.johnrocky.litertlm.genai.internal.FeatureClient
import io.github.johnrocky.litertlm.genai.internal.LocalSummarizer
import io.github.johnrocky.litertlm.genai.internal.clientFor

/** Creates a [Summarizer]. Same call as `com.google.mlkit.genai.summarization.Summarization`, plus the provider. */
public object Summarization {
    public fun getClient(options: SummarizerOptions, provider: ModelProvider = ModelProvider.Auto()): Summarizer =
        Summarizer(
            clientFor(
                context = options.context,
                provider = provider,
                nano = { it.summarizer(options) },
                local = { LocalSummarizer(it, options) },
            ),
        )
}

/** Summarizes articles and conversations as a bulleted list. */
public class Summarizer internal constructor(
    private val client: FeatureClient<SummarizationRequest, SummarizationResult>,
) : AutoCloseable {
    /** Who answered last, or who `checkFeatureStatus` chose. `null` before either. */
    public val activeProvider: ActiveProvider? get() = client.activeProvider

    /** Why [activeProvider] was chosen, for logs. */
    public val activeProviderReason: String? get() = client.activeProviderReason

    /** One of [io.github.johnrocky.litertlm.genai.common.FeatureStatus]. */
    public suspend fun checkFeatureStatus(): Int = client.checkFeatureStatus()

    /**
     * Downloads what the feature needs and returns when that has finished or failed. A failure goes to
     * [DownloadCallback.onDownloadFailed]. A call while a download is running follows that download.
     * Cancelling the coroutine stops the download if nobody else follows it, and the next call resumes it.
     */
    public suspend fun downloadFeature(callback: DownloadCallback): Unit = client.downloadFeature(callback)

    /** Loads the model now instead of at the first request, downloading it first if needed. */
    public suspend fun prepareInferenceEngine(): Unit = client.prepareInferenceEngine()

    /** The name of the base model: Gemini Nano's version, or the local model's file name. After a request, whoever answered. */
    public suspend fun getBaseModelName(): String = client.getBaseModelName()

    /**
     * Summarizes the text, downloading the model first if needed.
     *
     * @throws io.github.johnrocky.litertlm.genai.common.GenAiException on failure.
     */
    public suspend fun runInference(request: SummarizationRequest): SummarizationResult =
        client.runInference(request, null)

    /** As [runInference], and [callback] gets the summary as it is generated. */
    public suspend fun runInference(request: SummarizationRequest, callback: StreamingCallback): SummarizationResult =
        client.runInference(request, callback)

    /**
     * Stops what is running, which then fails with
     * [io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode.CANCELLED], and releases the model when no
     * other client uses it. It does not block, and a second call does nothing.
     */
    override fun close(): Unit = client.close()
}

/** What to summarize and how. Same builder as `com.google.mlkit.genai.summarization.SummarizerOptions`. */
public class SummarizerOptions private constructor(
    internal val context: PlatformContext,
    internal val inputType: Int,
    internal val outputType: Int,
    internal val language: Int,
    internal val longInputAutoTruncationEnabled: Boolean,
) {
    public object InputType {
        /** An article or long paragraphs, over 400 characters. */
        public const val ARTICLE: Int = 1

        /** A conversation, one `<name>: <message>` per line. */
        public const val CONVERSATION: Int = 2
    }

    public object OutputType {
        public const val ONE_BULLET: Int = 1
        public const val TWO_BULLETS: Int = 2
        public const val THREE_BULLETS: Int = 3
    }

    public object Language {
        public const val ENGLISH: Int = 0
        public const val JAPANESE: Int = 1
        public const val KOREAN: Int = 2
    }

    public class Builder internal constructor(private val context: PlatformContext) {
        private var inputType = InputType.ARTICLE
        private var outputType = OutputType.ONE_BULLET
        private var language = Language.ENGLISH
        private var longInputAutoTruncationEnabled = false

        public fun setInputType(inputType: Int): Builder = apply { this.inputType = inputType }

        public fun setOutputType(outputType: Int): Builder = apply { this.outputType = outputType }

        public fun setLanguage(language: Int): Builder = apply { this.language = language }

        /** Keeps the start of an input that is too long, instead of failing with REQUEST_TOO_LARGE. */
        public fun setLongInputAutoTruncationEnabled(enabled: Boolean): Builder =
            apply { longInputAutoTruncationEnabled = enabled }

        public fun build(): SummarizerOptions =
            SummarizerOptions(context, inputType, outputType, language, longInputAutoTruncationEnabled)
    }

    public companion object {
        public fun builder(context: PlatformContext = platformContext()): Builder = Builder(context)
    }
}

/** The text to summarize. */
public class SummarizationRequest private constructor(internal val text: String) {
    public class Builder internal constructor(private val text: String) {
        public fun build(): SummarizationRequest = SummarizationRequest(text)
    }

    public companion object {
        public fun builder(text: String): Builder = Builder(text)
    }
}

/**
 * The summary.
 *
 * @property summary One bullet per line, each starting with `* `. There are at most as many as
 *   [SummarizerOptions.OutputType] asks for, and fewer when the input is short.
 * @property activeProvider Who answered.
 */
public class SummarizationResult internal constructor(
    public val summary: String,
    public val activeProvider: ActiveProvider,
)
