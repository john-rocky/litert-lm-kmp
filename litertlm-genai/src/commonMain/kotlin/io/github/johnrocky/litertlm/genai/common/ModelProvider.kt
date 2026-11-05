package io.github.johnrocky.litertlm.genai.common

import io.github.johnrocky.litertlm.Backend

/**
 * Who answers a request. This is the one thing here that ML Kit does not have: every `getClient` takes it as an optional
 * last argument, and the default is [Auto].
 */
public sealed class ModelProvider {
    /**
     * Gemini Nano where it is ready to answer, otherwise [local].
     *
     * On each request, by Gemini Nano's status for this feature on this device:
     * - AVAILABLE: Gemini Nano gets the request.
     * - DOWNLOADABLE or DOWNLOADING: [local] answers if it is already on the device, because a request to Gemini Nano
     *   would first wait for the download. If it is not on the device, Gemini Nano gets the request.
     * - UNAVAILABLE, or the status check fails: [local] answers.
     *
     * The cost of this: with [local] on the device, Auto does not fetch what Gemini Nano is missing, and [local] goes on
     * answering. To have Gemini Nano answer on such a device, call `downloadFeature` on a client made with [GeminiNano].
     * Auto uses it from the first request after its status is AVAILABLE.
     *
     * Once Gemini Nano has a request, it falls back to [local], if that is already on the device, when Gemini Nano answers
     * [GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED], [GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED],
     * [GenAiException.ErrorCode.NOT_AVAILABLE], [GenAiException.ErrorCode.NOT_SUPPORTED],
     * [GenAiException.ErrorCode.AICORE_INCOMPATIBLE], [GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE] or
     * [GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE], or still [GenAiException.ErrorCode.BUSY] after [busyRetries]
     * tries with exponential backoff. A fallback never starts a download, and never happens once text has been delivered
     * to a streaming callback: that failure is reported, as it is from Gemini Nano itself.
     *
     * On iOS and the JVM there is no Gemini Nano, so [local] always answers. The same goes for an Android app without the
     * `litertlm-genai-nano` artifact, and the client's `activeProviderReason` says so.
     */
    public data class Auto(
        val local: LocalModel = LocalModel.Gemma4E2B,
        val backend: Backend = Backend.CPU(),
        val visionBackend: Backend = backend,
        val maxNumTokens: Int = LocalModel.DEFAULT_MAX_NUM_TOKENS,
        val cacheDir: String? = null,
        val minAvailableMemoryBytes: Long? = null,
        val busyRetries: Int = 3,
    ) : ModelProvider()

    /**
     * Only Gemini Nano, on Android through AICore, in an app that has the `litertlm-genai-nano` artifact. A failure comes
     * back with Gemini Nano's own error code. Without the artifact, and on iOS and the JVM, the status is
     * [FeatureStatus.UNAVAILABLE] and a request fails with [GenAiException.ErrorCode.NOT_AVAILABLE].
     */
    public data object GeminiNano : ModelProvider()

    /**
     * Only a LiteRT-LM model, on every platform.
     *
     * @property local The model.
     * @property backend Where the model runs.
     * @property visionBackend Where the vision part runs, for image input. It is loaded only when a request has an image.
     * @property maxNumTokens The size of the context window: input and output tokens together.
     * @property cacheDir A writable directory for compiled caches. `null` uses the platform's cache directory.
     * @property minAvailableMemoryBytes See [io.github.johnrocky.litertlm.EngineConfig.minAvailableMemoryBytes].
     */
    public data class LiteRtLm(
        val local: LocalModel = LocalModel.Gemma4E2B,
        val backend: Backend = Backend.CPU(),
        val visionBackend: Backend = backend,
        val maxNumTokens: Int = LocalModel.DEFAULT_MAX_NUM_TOKENS,
        val cacheDir: String? = null,
        val minAvailableMemoryBytes: Long? = null,
    ) : ModelProvider()
}

/** Who answered. */
public enum class ActiveProvider {
    GEMINI_NANO,
    LITERT_LM,
}

/** A `.litertlm` model, chosen by the app. */
public sealed class LocalModel {
    /** A file that is already on the device. */
    public class File internal constructor(public val path: String) : LocalModel()

    /** A file on the Hugging Face Hub, fetched by `downloadFeature`. */
    public class HuggingFace internal constructor(
        public val repo: String,
        public val file: String,
        public val revision: String,
        public val directory: String?,
        public val endpoint: String,
    ) : LocalModel()

    public companion object {
        /**
         * Fits ML Kit's limit of 4000 input tokens with room left for a reply. Clients that use the same file and
         * settings share one loaded model, however many there are.
         */
        public const val DEFAULT_MAX_NUM_TOKENS: Int = 4608

        /** Gemma 4 E2B: 2.6 GB, Apache-2.0, text and images. */
        public val Gemma4E2B: LocalModel = huggingFace(
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            file = "gemma-4-E2B-it.litertlm",
        )

        public fun file(path: String): LocalModel = File(path)

        /**
         * A file in a Hugging Face repository. The download resumes after a failure, and the file appears only after its
         * size and SHA-256 match what the Hub lists. Only public repositories: for a gated one, get the file yourself
         * and use [file].
         *
         * @param directory Where the file goes. `null` uses a directory of this library's, which backups leave out.
         * @param endpoint The Hub, or a mirror of it.
         */
        public fun huggingFace(
            repo: String,
            file: String,
            revision: String = "main",
            directory: String? = null,
            endpoint: String = "https://huggingface.co",
        ): LocalModel = HuggingFace(repo, file, revision, directory, endpoint.trimEnd('/'))
    }
}
