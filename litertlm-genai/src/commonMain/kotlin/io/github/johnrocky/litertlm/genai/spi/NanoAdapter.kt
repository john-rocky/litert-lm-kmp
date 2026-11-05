package io.github.johnrocky.litertlm.genai.spi

import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentRequest
import io.github.johnrocky.litertlm.genai.prompt.GenerationConfig
import io.github.johnrocky.litertlm.genai.prompt.ImagePart

/**
 * What `litertlm-genai-nano` implements and `litertlm-genai` calls, and nothing else uses. An app chooses Gemini Nano with
 * `ModelProvider`. This can change in any release, so the two artifacts have to be the same version.
 */
@RequiresOptIn(
    message = "This is only for litertlm-genai-nano and can change in any release. An app uses ModelProvider.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class NanoAdapterApi

/**
 * Gemini Nano, as `litertlm-genai-nano` provides it. The numbers are the constants of the options classes, which are also
 * ML Kit's. `litertlm-genai` finds the implementation by its class name, so it has a constructor with no arguments, and that
 * constructor does not touch the provider's classes.
 */
@NanoAdapterApi
public interface NanoAdapter {
    public fun summarizer(
        context: PlatformContext,
        inputType: Int,
        outputType: Int,
        language: Int,
        longInputAutoTruncationEnabled: Boolean,
    ): NanoClient<String, String>

    public fun proofreader(context: PlatformContext, inputType: Int, language: Int): NanoClient<String, List<String>>

    public fun rewriter(context: PlatformContext, outputType: Int, language: Int): NanoClient<String, List<String>>

    public fun imageDescriber(context: PlatformContext): NanoClient<NanoImage, String>

    public fun generativeModel(config: GenerationConfig): NanoPromptClient
}

/**
 * One feature of Gemini Nano. Creating it does nothing yet, because a phone without AICore has to be able to. Failures are
 * [io.github.johnrocky.litertlm.genai.common.GenAiException]: a call throws them, and a download reports them to its
 * callback. Cancelling the coroutine stops what is running.
 */
@NanoAdapterApi
public interface NanoClient<Req, Res> {
    public suspend fun checkFeatureStatus(): Int

    /** Returns when the download has finished or failed. */
    public suspend fun downloadFeature(callback: DownloadCallback)

    public suspend fun prepareInferenceEngine()

    public suspend fun getBaseModelName(): String

    /** What Gemini Nano wrote, as it wrote it. [callback] gets the same text in pieces, on the caller's context. */
    public suspend fun runInference(request: Req, callback: StreamingCallback?): Res

    /** Must not block. */
    public fun close()
}

/** The calls that only the prompt API has. */
@NanoAdapterApi
public interface NanoPromptClient : NanoClient<GenerateContentRequest, List<NanoCandidate>> {
    public suspend fun getTokenLimit(): Int

    public suspend fun countTokens(request: GenerateContentRequest): Int

    public suspend fun getAvailableFeatures(): List<Int>
}

@NanoAdapterApi
public class NanoCandidate(public val text: String, public val finishReason: Int?)

/**
 * An image for Gemini Nano.
 *
 * @property platformImage The `Bitmap`, on Android, if the image came as one. Gemini Nano takes that as it is.
 * @property bytes The encoded image, encoded on demand if it came as a `Bitmap`.
 */
@NanoAdapterApi
public class NanoImage(public val platformImage: Any?, public val bytes: Lazy<ByteArray>)

@NanoAdapterApi
public fun ImagePart.toNanoImage(): NanoImage = NanoImage(platformImage, encoded)
