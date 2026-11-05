package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.Content
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.Role
import io.github.johnrocky.litertlm.ToolCall
import io.github.johnrocky.litertlm.ToolProvider
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** The JSON form of a message in the runtime's conversation API. */
internal fun Message.toJson(): JsonObject {
    val fields = LinkedHashMap<String, JsonElement>()
    fields["role"] = JsonPrimitive(role.value)
    if (contents.contents.isNotEmpty()) fields["content"] = contents.toJson()
    if (toolCalls.isNotEmpty()) fields["tool_calls"] = JsonArray(toolCalls.map { it.toJson() })
    if (channels.isNotEmpty()) fields["channels"] = JsonObject(channels.mapValues { (_, v) -> JsonPrimitive(v) })
    return JsonObject(fields)
}

internal fun Contents.toJson(): JsonArray = JsonArray(
    contents.map { content ->
        when (content) {
            is Content.Text -> JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(content.text)))
            is Content.ImageBytes -> JsonObject(
                mapOf("type" to JsonPrimitive("image"), "blob" to JsonPrimitive(Base64.encode(content.bytes))),
            )
            is Content.ToolResponse -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("tool_response"),
                    "name" to JsonPrimitive(content.name),
                    "response" to anyToJson(content.response),
                ),
            )
        }
    },
)

private fun ToolCall.toJson(): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("function"),
        "function" to JsonObject(
            mapOf(
                "name" to JsonPrimitive(name),
                "arguments" to anyToJson(arguments),
            ),
        ),
    ),
)

/** The tools array of the runtime's conversation API. */
internal fun List<ToolProvider>.toToolsJson(): JsonArray = JsonArray(
    map { JsonObject(mapOf("type" to JsonPrimitive("function"), "function" to it.description)) },
)

internal fun anyToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJson(v) })
    is Iterable<*> -> JsonArray(value.map { anyToJson(it) })
    else -> JsonPrimitive(value.toString())
}

/** Whole numbers become Long, other numbers Double, on every platform. */
internal fun jsonToAny(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonObject -> element.mapValues { (_, v) -> jsonToAny(v) }
    is JsonArray -> element.map { jsonToAny(it) }
    is JsonPrimitive -> when {
        element.isString -> element.content
        else -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.contentOrNull
    }
}

/** Parses a reply or a reply chunk from the C API. */
internal fun parseMessageJson(text: String): Message {
    val json = Json.parseToJsonElement(text) as JsonObject
    val contents = when (val content = json["content"]) {
        is JsonArray -> content.mapNotNull { part ->
            val item = part as? JsonObject
            val type = (item?.get("type") as? JsonPrimitive)?.content
            val partText = (item?.get("text") as? JsonPrimitive)?.content
            if (type == "text" && partText != null) Content.Text(partText) else null
        }
        is JsonPrimitive -> if (content.isString && content.content.isNotEmpty()) listOf(Content.Text(content.content)) else emptyList()
        else -> emptyList()
    }
    val toolCalls = (json["tool_calls"] as? JsonArray).orEmpty().mapNotNull { parseToolCall(it) }
    val channels = (json["channels"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.content?.let { key to it }
    }.toMap()
    return Message(Role.MODEL, Contents.of(contents), toolCalls, channels)
}

private fun parseToolCall(element: JsonElement): ToolCall? {
    val function = (element as? JsonObject)?.get("function") as? JsonObject ?: return null
    val name = (function["name"] as? JsonPrimitive)?.content ?: return null
    val arguments = function["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    return ToolCall(name, arguments.mapValues { (_, v) -> jsonToAny(v) })
}
