package io.github.johnrocky.litertlm.genai.imagedescription

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.common.platformContext
import io.github.johnrocky.litertlm.genai.internal.FeatureClient
import io.github.johnrocky.litertlm.genai.internal.LocalImageDescriber
import io.github.johnrocky.litertlm.genai.internal.clientFor

/** Creates an [ImageDescriber]. Same call as `com.google.mlkit.genai.imagedescription.ImageDescription`, plus the provider. */
public object ImageDescription {
    public fun getClient(options: ImageDescriberOptions, provider: ModelProvider = ModelProvider.Auto()): ImageDescriber =
        ImageDescriber(
            clientFor(
                context = options.context,
                provider = provider,
                nano = { it.imageDescriber(options) },
                local = { LocalImageDescriber(it) },
            ),
        )
}

/**
 * Generates a short description of an image. The methods work as on
 * [io.github.johnrocky.litertlm.genai.summarization.Summarizer]. A local model without a vision part answers with
 * [io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode.NOT_SUPPORTED].
 */
public class ImageDescriber internal constructor(
    private val client: FeatureClient<ImageDescriptionRequest, ImageDescriptionResult>,
) : AutoCloseable {
    public val activeProvider: ActiveProvider? get() = client.activeProvider

    public val activeProviderReason: String? get() = client.activeProviderReason

    public suspend fun checkFeatureStatus(): Int = client.checkFeatureStatus()

    public suspend fun downloadFeature(callback: DownloadCallback): Unit = client.downloadFeature(callback)

    public suspend fun prepareInferenceEngine(): Unit = client.prepareInferenceEngine()

    public suspend fun getBaseModelName(): String = client.getBaseModelName()

    public suspend fun runInference(request: ImageDescriptionRequest): ImageDescriptionResult =
        client.runInference(request, null)

    public suspend fun runInference(request: ImageDescriptionRequest, callback: StreamingCallback): ImageDescriptionResult =
        client.runInference(request, callback)

    override fun close(): Unit = client.close()
}

/** Same builder as `com.google.mlkit.genai.imagedescription.ImageDescriberOptions`. */
public class ImageDescriberOptions private constructor(internal val context: PlatformContext) {
    public class Builder internal constructor(private val context: PlatformContext) {
        public fun build(): ImageDescriberOptions = ImageDescriberOptions(context)
    }

    public companion object {
        public fun builder(context: PlatformContext = platformContext()): Builder = Builder(context)
    }
}

/**
 * The image. `android.graphics.Bitmap` does not exist in `commonMain`, so [builder] takes the bytes of a JPEG or PNG
 * file; on Android there is also a `builder(bitmap)` extension.
 */
public class ImageDescriptionRequest internal constructor(
    /** Encoded on demand when the image came as a `Bitmap`, because Gemini Nano takes that as it is. */
    internal val bytes: Lazy<ByteArray>,
    /** The `Bitmap`, on Android, if the image came as one. */
    internal val platformImage: Any?,
) {
    public class Builder internal constructor(
        private val bytes: Lazy<ByteArray>,
        private val platformImage: Any?,
    ) {
        public fun build(): ImageDescriptionRequest = ImageDescriptionRequest(bytes, platformImage)
    }

    public companion object {
        public fun builder(bytes: ByteArray): Builder = Builder(lazyOf(bytes), null)
    }
}

/**
 * @property description The description.
 * @property activeProvider Who answered.
 */
public class ImageDescriptionResult internal constructor(
    public val description: String,
    public val activeProvider: ActiveProvider,
)
