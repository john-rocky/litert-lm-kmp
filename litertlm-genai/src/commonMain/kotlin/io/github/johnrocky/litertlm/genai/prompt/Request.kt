package io.github.johnrocky.litertlm.genai.prompt

import io.github.johnrocky.litertlm.genai.common.ActiveProvider

/** A part of a [Content]. */
public sealed class Part

/** @property textString The text. */
public data class TextPart(val textString: String) : Part()

/**
 * An image. `android.graphics.Bitmap` does not exist in `commonMain`, so this takes the bytes of a JPEG or PNG file; on
 * Android there is also an `ImagePart(bitmap)` function.
 */
public class ImagePart internal constructor(
    internal val encoded: Lazy<ByteArray>,
    internal val platformImage: Any?,
) : Part() {
    public constructor(bytes: ByteArray) : this(lazyOf(bytes), null)

    /** The bytes of the file. */
    public val bytes: ByteArray get() = encoded.value
}

/** Rules for the whole request. Gemini Nano needs version 3 or later; see [GenerativeModel.isSystemPromptAvailable]. */
public data class SystemInstruction(val textString: String) : Part()

/** One turn of a conversation that the app keeps itself. */
public class Content internal constructor(public val role: Int, public val parts: List<Part>) {
    public object Role {
        public const val USER: Int = 0
        public const val MODEL: Int = 1
    }

    public class Builder {
        public var role: Int = Role.USER
        private val parts = ArrayList<Part>()

        public fun role(role: Int): Builder = apply { this.role = role }

        public fun addPart(part: Part): Builder = apply { parts += part }

        public fun parts(vararg parts: Part): Builder = apply { this.parts += parts }

        public fun text(text: String): Builder = addPart(TextPart(text))

        public fun image(bytes: ByteArray): Builder = addPart(ImagePart(bytes))

        public fun build(): Content = Content(role, parts.toList())
    }

    public companion object {
        public fun builder(): Builder = Builder()
    }
}

public fun content(init: Content.Builder.() -> Unit): Content = Content.Builder().apply(init).build()

public fun content(role: Int, init: Content.Builder.() -> Unit): Content =
    Content.Builder().role(role).apply(init).build()

/**
 * What to generate. Same as `com.google.mlkit.genai.prompt.GenerateContentRequest`, with the same defaults: temperature
 * 0, top-K 3, one candidate, a new seed each time.
 */
public class GenerateContentRequest private constructor(
    public val contents: List<Content>,
    public val systemInstruction: SystemInstruction?,
    public val temperature: Float,
    public val topK: Int,
    public val seed: Int,
    public val candidateCount: Int,
    public val maxOutputTokens: Int,
    public val sessionId: String?,
) {
    /** The contents, then the settings. [candidateCount] above 1 may be ignored: a local model gives one candidate. */
    public class Builder(private val contents: List<Content>) {
        public var systemInstruction: SystemInstruction? = null
        public var temperature: Float? = null
        public var topK: Int? = null
        public var seed: Int? = null
        public var candidateCount: Int? = null
        public var maxOutputTokens: Int? = null
        public var sessionId: String? = null

        public constructor(content: Content) : this(listOf(content))

        /** Any mix of [ImagePart], [TextPart] and one [SystemInstruction]. */
        public constructor(vararg parts: Part) : this(listOf(Content(Content.Role.USER, parts.filterNot { it is SystemInstruction }))) {
            systemInstruction = parts.filterIsInstance<SystemInstruction>().firstOrNull()
        }

        public fun build(): GenerateContentRequest = GenerateContentRequest(
            contents,
            systemInstruction,
            temperature ?: 0f,
            topK ?: 3,
            seed ?: 0,
            candidateCount ?: 1,
            maxOutputTokens ?: 0,
            sessionId,
        )
    }

    public companion object {
        public fun builder(vararg parts: Part): Builder = Builder(*parts)

        public fun builder(content: Content): Builder = Builder(content)
    }
}

/** Any mix of [ImagePart], [TextPart] and one [SystemInstruction], then the settings. */
public fun generateContentRequest(
    vararg parts: Part,
    init: GenerateContentRequest.Builder.() -> Unit = {},
): GenerateContentRequest = GenerateContentRequest.Builder(*parts).apply(init).build()

public fun generateContentRequest(
    content: Content,
    init: GenerateContentRequest.Builder.() -> Unit = {},
): GenerateContentRequest = GenerateContentRequest.Builder(content).apply(init).build()

public fun generateContentRequest(
    systemInstruction: SystemInstruction,
    content: Content,
    init: GenerateContentRequest.Builder.() -> Unit = {},
): GenerateContentRequest = GenerateContentRequest.Builder(content).apply {
    this.systemInstruction = systemInstruction
    init()
}.build()

/**
 * The reply.
 *
 * @property candidates The answers, most confident first.
 * @property activeProvider Who answered.
 */
public class GenerateContentResponse internal constructor(
    public val candidates: List<Candidate>,
    public val activeProvider: ActiveProvider,
)

/**
 * @property text The text.
 * @property finishReason One of [FinishReason]. `null` on the pieces of a stream.
 */
public class Candidate internal constructor(public val text: String, public val finishReason: Int?) {
    public object FinishReason {
        public const val STOP: Int = 0
        public const val MAX_TOKENS: Int = 1
        public const val OTHER: Int = -100
    }
}
