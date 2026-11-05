package io.github.johnrocky.litertlm

/**
 * Every failure this library reports, with the same types on Android, iOS and the JVM.
 *
 * [Cancelled] is not a [kotlinx.coroutines.CancellationException]: a collector that catches it keeps running.
 * When the collecting coroutine itself is cancelled, the usual CancellationException is thrown instead.
 */
public sealed class LiteRtLmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** The model file does not exist. */
    public class ModelNotFound(public val path: String) : LiteRtLmException("Model file not found: $path")

    /** The path is not a usable `.litertlm` file: a directory, not this format, or cut short. */
    public class InvalidModel(public val path: String, public val reason: String) :
        LiteRtLmException("Not a usable .litertlm file ($reason): $path")

    /**
     * Too little memory to load the model, or on iOS too little address space. Checked before loading, because on iOS the
     * system kills the process when it runs out, and nothing can catch that.
     */
    public class InsufficientMemory private constructor(
        public val availableBytes: Long,
        public val requiredBytes: Long,
        message: String,
    ) : LiteRtLmException(message) {
        public constructor(availableBytes: Long, requiredBytes: Long) : this(
            availableBytes,
            requiredBytes,
            "Not enough memory to load the model: $availableBytes bytes available, $requiredBytes required.",
        )

        internal companion object {
            fun ofAddressSpace(availableBytes: Long, requiredBytes: Long) = InsufficientMemory(
                availableBytes,
                requiredBytes,
                "Not enough address space to load the model: $availableBytes bytes free, $requiredBytes required. " +
                    "Another model may be loaded in this process. Close it first.",
            )
        }
    }

    /**
     * The input does not fit in the context window ([EngineConfig.maxNumTokens]), counting the earlier turns.
     * Shorten it, or start a new conversation.
     */
    public class InputTooLong(message: String) : LiteRtLmException(message)

    /** The turn stopped because of [Conversation.cancelProcess] or a close. */
    public class Cancelled(message: String = "The turn was cancelled.") : LiteRtLmException(message)

    /**
     * The requested backend cannot run this model on this device, or the turn needs one that was not set:
     * an image needs [EngineConfig.visionBackend].
     */
    public class BackendUnavailable(public val backend: String, message: String) : LiteRtLmException(message)

    /** The bytes of a [Content.ImageBytes] are not an image the runtime can decode. */
    public class InvalidImage(message: String) : LiteRtLmException(message)

    /**
     * Anything else the runtime reported. [code] is the canonical status code (see absl::StatusCode). On iOS also an
     * app that does not have the runtime: FAILED_PRECONDITION (9), and the message says to add the Swift package.
     */
    public class Internal(public val code: Int, message: String, cause: Throwable? = null) : LiteRtLmException(message, cause)

    /** The object was already closed. */
    public class ClosedUse(what: String) : LiteRtLmException("$what is closed.")

    /** [Engine.createConversation] or a similar call came before [Engine.initialize] finished. */
    public class NotInitialized(what: String) : LiteRtLmException("$what is not initialized.")

    /** [Engine.initialize] was called twice. */
    public class AlreadyInitialized(what: String) : LiteRtLmException("$what is already initialized.")
}
