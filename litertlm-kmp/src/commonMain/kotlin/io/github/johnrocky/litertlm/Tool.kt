package io.github.johnrocky.litertlm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tool described with an OpenAPI function declaration. The names follow
 * `com.google.ai.edge.litertlm.OpenApiTool`.
 */
public interface OpenApiTool {
    /**
     * The declaration as a JSON object with `name`, `description` and `parameters`, for example:
     * `{"name":"get_weather","description":"Current weather.","parameters":{"type":"object",
     * "properties":{"city":{"type":"string"}},"required":["city"]}}`
     */
    public fun getToolDescriptionJsonString(): String

    /**
     * Never called by this library, which does not run tools by itself. It is here so a tool written for the
     * Google API compiles unchanged.
     */
    public fun execute(paramsJsonString: String): String =
        throw UnsupportedOperationException("Tools run in the caller: see Message.toolCalls and Message.tool.")
}

/** A tool for [ConversationConfig.tools]. Make one with [tool]. */
public class ToolProvider internal constructor(internal val description: JsonObject)

/**
 * Wraps an [OpenApiTool] for [ConversationConfig.tools].
 *
 * @throws IllegalArgumentException if the declaration is not a JSON object with a `name`.
 */
public fun tool(openApiTool: OpenApiTool): ToolProvider {
    val text = openApiTool.getToolDescriptionJsonString()
    val parsed = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw IllegalArgumentException("Tool description is not valid JSON: $text", e)
    }
    val description = parsed as? JsonObject
        ?: throw IllegalArgumentException("Tool description must be a JSON object: $text")
    require((description["name"] as? JsonPrimitive)?.isString == true) { "Tool description needs a name: $text" }
    return ToolProvider(description)
}
