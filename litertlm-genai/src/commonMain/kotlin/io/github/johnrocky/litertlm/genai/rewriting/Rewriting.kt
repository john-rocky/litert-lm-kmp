package io.github.johnrocky.litertlm.genai.rewriting

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.common.platformContext
import io.github.johnrocky.litertlm.genai.internal.FeatureClient
import io.github.johnrocky.litertlm.genai.internal.LocalRewriter
import io.github.johnrocky.litertlm.genai.internal.clientFor

/** Creates a [Rewriter]. Same call as `com.google.mlkit.genai.rewriting.Rewriting`, plus the provider. */
public object Rewriting {
    public fun getClient(options: RewriterOptions, provider: ModelProvider = ModelProvider.Auto()): Rewriter =
        Rewriter(
            clientFor(
                context = options.context,
                provider = provider,
                nano = { it.rewriter(options) },
                local = { LocalRewriter(it, options) },
            ),
        )
}

/**
 * Rewrites short text in another style. The methods work as on
 * [io.github.johnrocky.litertlm.genai.summarization.Summarizer].
 */
public class Rewriter internal constructor(
    private val client: FeatureClient<RewritingRequest, RewritingResult>,
) : AutoCloseable {
    public val activeProvider: ActiveProvider? get() = client.activeProvider

    public val activeProviderReason: String? get() = client.activeProviderReason

    public suspend fun checkFeatureStatus(): Int = client.checkFeatureStatus()

    public suspend fun downloadFeature(callback: DownloadCallback): Unit = client.downloadFeature(callback)

    public suspend fun prepareInferenceEngine(): Unit = client.prepareInferenceEngine()

    public suspend fun getBaseModelName(): String = client.getBaseModelName()

    public suspend fun runInference(request: RewritingRequest): RewritingResult = client.runInference(request, null)

    public suspend fun runInference(request: RewritingRequest, callback: StreamingCallback): RewritingResult =
        client.runInference(request, callback)

    override fun close(): Unit = client.close()
}

/** The style and the language. Same builder as `com.google.mlkit.genai.rewriting.RewriterOptions`. */
public class RewriterOptions private constructor(
    internal val context: PlatformContext,
    internal val outputType: Int,
    internal val language: Int,
) {
    public object OutputType {
        public const val ELABORATE: Int = 1
        public const val EMOJIFY: Int = 2
        public const val SHORTEN: Int = 3
        public const val FRIENDLY: Int = 4
        public const val PROFESSIONAL: Int = 5
        public const val REPHRASE: Int = 6
    }

    public object Language {
        public const val ENGLISH: Int = 0
        public const val JAPANESE: Int = 1
        public const val KOREAN: Int = 2
        public const val GERMAN: Int = 3
        public const val FRENCH: Int = 4
        public const val ITALIAN: Int = 5
        public const val SPANISH: Int = 6
    }

    public class Builder internal constructor(private val context: PlatformContext) {
        private var outputType = OutputType.ELABORATE
        private var language = Language.ENGLISH

        public fun setOutputType(outputType: Int): Builder = apply { this.outputType = outputType }

        public fun setLanguage(language: Int): Builder = apply { this.language = language }

        public fun build(): RewriterOptions = RewriterOptions(context, outputType, language)
    }

    public companion object {
        public fun builder(context: PlatformContext = platformContext()): Builder = Builder(context)
    }
}

/** The text to rewrite. */
public class RewritingRequest private constructor(internal val text: String) {
    public class Builder internal constructor(private val text: String) {
        public fun build(): RewritingRequest = RewritingRequest(text)
    }

    public companion object {
        public fun builder(text: String): Builder = Builder(text)
    }
}

/**
 * @property results At least one suggestion, the most confident first. A local model gives one.
 * @property activeProvider Who answered.
 */
public class RewritingResult internal constructor(
    public val results: List<RewritingSuggestion>,
    public val activeProvider: ActiveProvider,
)

/** @property text The rewritten text. */
public class RewritingSuggestion internal constructor(public val text: String)
