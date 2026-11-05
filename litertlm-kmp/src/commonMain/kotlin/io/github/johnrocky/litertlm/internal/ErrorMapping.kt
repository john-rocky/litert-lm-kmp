package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.LiteRtLmException

private const val STATUS_CANCELLED = 1
private const val STATUS_UNKNOWN = 2

private val STATUS_NAMES = listOf(
    "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", "DEADLINE_EXCEEDED", "NOT_FOUND", "ALREADY_EXISTS",
    "PERMISSION_DENIED", "RESOURCE_EXHAUSTED", "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED",
    "INTERNAL", "UNAVAILABLE", "DATA_LOSS", "UNAUTHENTICATED",
)

private val STATUS_NAME = Regex("\\b(${STATUS_NAMES.joinToString("|")}): ")

/** The canonical code of a failure: the number the layer gave, or the name in the text. */
internal fun NativeFailure.statusCode(): Int =
    code ?: STATUS_NAME.find(message)?.let { STATUS_NAMES.indexOf(it.groupValues[1]) + 1 } ?: STATUS_UNKNOWN

/**
 * The texts come from LiteRT-LM v0.19.0: runtime/core/tasks.cc ("Input token ids are too long", "Not enough space
 * left in the context window"), runtime/conversation/conversation.cc and internal_callback_util.cc ("Max number of
 * tokens reached"). The status code does not tell these apart: the first is INVALID_ARGUMENT, the last
 * RESOURCE_EXHAUSTED.
 */
private fun isContextWindowFull(message: String): Boolean =
    "Input token ids are too long" in message ||
        "Not enough space left in the context window" in message ||
        "Max number of tokens reached" in message

/** Maps a failure of a running turn. */
internal fun NativeFailure.toTurnException(): LiteRtLmException = when {
    statusCode() == STATUS_CANCELLED -> LiteRtLmException.Cancelled()
    isContextWindowFull(message) -> LiteRtLmException.InputTooLong(message)
    "Failed to decode image" in message -> LiteRtLmException.InvalidImage(message)
    "Vision executor" in message -> LiteRtLmException.BackendUnavailable(
        "vision",
        "An image needs EngineConfig.visionBackend. $message",
    )
    else -> LiteRtLmException.Internal(statusCode(), message, this)
}

/** What the runtime says about a file it cannot read: a damaged section reaches this after the file checks pass. */
private val DAMAGED_FILE_TEXTS = listOf("magic number", "not found in the model", "Failed to parse", "Invalid flatbuffer")

/** Maps a failure of loading a model that passed the file and memory checks. */
internal fun NativeFailure.toLoadException(path: String, backend: Backend): LiteRtLmException = when {
    "No such file" in message || "Model file not found" in message -> LiteRtLmException.ModelNotFound(path)
    DAMAGED_FILE_TEXTS.any { it in message } -> LiteRtLmException.InvalidModel(path, message)
    backend is Backend.GPU -> LiteRtLmException.BackendUnavailable(backend.name, message)
    else -> LiteRtLmException.Internal(statusCode(), message, this)
}
