package io.github.johnrocky.litertlm

/** The role of a message in a conversation. */
public enum class Role(public val value: String) {
    SYSTEM("system"),
    USER("user"),
    MODEL("assistant"),
    TOOL("tool"),
}

/** A message in a conversation. The names follow `com.google.ai.edge.litertlm.Message`. */
public class Message internal constructor(
    public val role: Role,
    public val contents: Contents = Contents.of(emptyList()),
    public val toolCalls: List<ToolCall> = emptyList(),
    public val channels: Map<String, String> = emptyMap(),
) {
    /** The text of the message. */
    override fun toString(): String = contents.toString()

    public companion object {
        public fun system(text: String): Message = system(Contents.of(text))

        public fun system(contents: Contents): Message = Message(Role.SYSTEM, contents)

        public fun user(text: String): Message = user(Contents.of(text))

        public fun user(contents: Contents): Message = Message(Role.USER, contents)

        public fun model(text: String): Message = model(Contents.of(text))

        public fun model(
            contents: Contents = Contents.of(emptyList()),
            toolCalls: List<ToolCall> = emptyList(),
            channels: Map<String, String> = emptyMap(),
        ): Message = Message(Role.MODEL, contents, toolCalls, channels)

        /** The result of a tool call, as [Content.ToolResponse] items. */
        public fun tool(contents: Contents): Message = Message(Role.TOOL, contents)
    }
}

/** The parts of a [Message]. */
public class Contents private constructor(public val contents: List<Content>) {
    /** The text parts joined together. */
    override fun toString(): String = contents.joinToString("")

    public companion object {
        public fun of(text: String): Contents = of(Content.Text(text))

        public fun of(vararg contents: Content): Contents = of(contents.toList())

        public fun of(contents: List<Content>): Contents = Contents(contents)
    }
}

/** A part of a [Message]. Audio is not in this version. */
public sealed class Content {
    public data class Text(val text: String) : Content() {
        override fun toString(): String = text
    }

    /**
     * An image as the bytes of a JPEG or PNG file. The engine needs [EngineConfig.visionBackend].
     *
     * @see LiteRtLmException.InvalidImage
     */
    public class ImageBytes(public val bytes: ByteArray) : Content() {
        override fun equals(other: Any?): Boolean = other is ImageBytes && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = ""
    }

    /**
     * The result of a tool call.
     *
     * @property response A String, Number, Boolean, null, or a List or Map of those.
     */
    public data class ToolResponse(val name: String, val response: Any?) : Content() {
        override fun toString(): String = ""
    }
}

/**
 * A tool call from the model, found in [Message.toolCalls].
 *
 * @property arguments Whole numbers are Long, other numbers Double, objects Map, arrays List.
 */
public data class ToolCall(val name: String, val arguments: Map<String, Any?>)
