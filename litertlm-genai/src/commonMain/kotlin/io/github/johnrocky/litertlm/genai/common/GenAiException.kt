package io.github.johnrocky.litertlm.genai.common

import kotlin.time.Duration

/**
 * A failure of a GenAI request, with the same names and numbers as `com.google.mlkit.genai.common.GenAiException`, so
 * one piece of handling works whichever provider answered. The provider's own failure, if there is one, is the [cause].
 *
 * @property errorCode One of [ErrorCode].
 * @property retryDelay How long to wait before trying again, if the provider said.
 */
public class GenAiException(
    message: String?,
    cause: Throwable?,
    public val errorCode: Int,
    public val retryDelay: Duration? = null,
) : Exception(message, cause) {
    public constructor(cause: Throwable?, errorCode: Int) : this(cause?.message, cause, errorCode)

    /** What went wrong. */
    public object ErrorCode {
        public const val UNKNOWN: Int = 0
        public const val REQUEST_PROCESSING_ERROR: Int = 4
        public const val CANCELLED: Int = 7
        public const val NOT_AVAILABLE: Int = 8
        public const val BUSY: Int = 9
        public const val RESPONSE_PROCESSING_ERROR: Int = 11
        public const val REQUEST_TOO_LARGE: Int = 12
        public const val REQUEST_TOO_SMALL: Int = -100
        public const val RESPONSE_GENERATION_ERROR: Int = 15
        public const val NOT_SUPPORTED: Int = 16
        public const val PER_APP_BATTERY_USE_QUOTA_EXCEEDED: Int = 27
        public const val BACKGROUND_USE_BLOCKED: Int = 30
        public const val NOT_ENOUGH_DISK_SPACE: Int = 501
        public const val NEEDS_SYSTEM_UPDATE: Int = 604
        public const val AICORE_INCOMPATIBLE: Int = -101
        public const val INVALID_INPUT_IMAGE: Int = -102
        public const val CACHE_PROCESSING_ERROR: Int = -103
        public const val STRUCTURED_OUTPUT_REQUEST_ERROR: Int = -104
        public const val STRUCTURED_OUTPUT_RESPONSE_ERROR: Int = -105
        public const val STRUCTURED_OUTPUT_MAX_TOKENS_ERROR: Int = -106
        public const val AUDIO_BUFFER_OVERFLOW: Int = -107
        public const val MAX_TURNS_EXCEEDED: Int = -112
    }
}
