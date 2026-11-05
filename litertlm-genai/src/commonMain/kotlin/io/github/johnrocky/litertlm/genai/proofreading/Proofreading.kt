package io.github.johnrocky.litertlm.genai.proofreading

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.common.platformContext
import io.github.johnrocky.litertlm.genai.internal.FeatureClient
import io.github.johnrocky.litertlm.genai.internal.LocalProofreader
import io.github.johnrocky.litertlm.genai.internal.clientFor

/** Creates a [Proofreader]. Same call as `com.google.mlkit.genai.proofreading.Proofreading`, plus the provider. */
public object Proofreading {
    public fun getClient(options: ProofreaderOptions, provider: ModelProvider = ModelProvider.Auto()): Proofreader =
        Proofreader(
            clientFor(
                context = options.context,
                provider = provider,
                nano = { it.proofreader(options) },
                local = { LocalProofreader(it, options) },
            ),
        )
}

/**
 * Fixes the grammar and spelling of short text. The methods work as on
 * [io.github.johnrocky.litertlm.genai.summarization.Summarizer].
 */
public class Proofreader internal constructor(
    private val client: FeatureClient<ProofreadingRequest, ProofreadingResult>,
) : AutoCloseable {
    public val activeProvider: ActiveProvider? get() = client.activeProvider

    public val activeProviderReason: String? get() = client.activeProviderReason

    public suspend fun checkFeatureStatus(): Int = client.checkFeatureStatus()

    public suspend fun downloadFeature(callback: DownloadCallback): Unit = client.downloadFeature(callback)

    public suspend fun prepareInferenceEngine(): Unit = client.prepareInferenceEngine()

    public suspend fun getBaseModelName(): String = client.getBaseModelName()

    public suspend fun runInference(request: ProofreadingRequest): ProofreadingResult =
        client.runInference(request, null)

    public suspend fun runInference(request: ProofreadingRequest, callback: StreamingCallback): ProofreadingResult =
        client.runInference(request, callback)

    override fun close(): Unit = client.close()
}

/** How the text was written, and its language. Same builder as `com.google.mlkit.genai.proofreading.ProofreaderOptions`. */
public class ProofreaderOptions private constructor(
    internal val context: PlatformContext,
    internal val inputType: Int,
    internal val language: Int,
) {
    public object InputType {
        /** Typed: expect slips of nearby keys. */
        public const val KEYBOARD: Int = 1

        /** From speech: expect words that sound alike. */
        public const val VOICE: Int = 2
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
        private var inputType = InputType.KEYBOARD
        private var language = Language.ENGLISH

        public fun setInputType(inputType: Int): Builder = apply { this.inputType = inputType }

        public fun setLanguage(language: Int): Builder = apply { this.language = language }

        public fun build(): ProofreaderOptions = ProofreaderOptions(context, inputType, language)
    }

    public companion object {
        public fun builder(context: PlatformContext = platformContext()): Builder = Builder(context)
    }
}

/** The text to proofread. */
public class ProofreadingRequest private constructor(internal val text: String) {
    public class Builder internal constructor(private val text: String) {
        public fun build(): ProofreadingRequest = ProofreadingRequest(text)
    }

    public companion object {
        public fun builder(text: String): Builder = Builder(text)
    }
}

/**
 * @property results At least one suggestion, the most confident first. A local model gives one.
 * @property activeProvider Who answered.
 */
public class ProofreadingResult internal constructor(
    public val results: List<ProofreadingSuggestion>,
    public val activeProvider: ActiveProvider,
)

/** @property text The corrected text. */
public class ProofreadingSuggestion internal constructor(public val text: String)
