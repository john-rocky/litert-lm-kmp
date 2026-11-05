package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.Content
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.OpenApiTool
import io.github.johnrocky.litertlm.ToolCall
import io.github.johnrocky.litertlm.tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MessageJsonTest {
    @Test
    fun aTextChunk() {
        val message = parseMessageJson("""{"role":"assistant","content":[{"type":"text","text":"ready"}]}""")
        assertEquals("ready", message.toString())
        assertEquals(emptyList(), message.toolCalls)
    }

    @Test
    fun aToolCallWithNestedArguments() {
        val message = parseMessageJson(
            """{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"f","arguments":
            {"n":3,"x":1.5,"s":"a","b":true,"z":null,"l":[1,2],"o":{"k":"v"}}}}]}""",
        )
        val call = message.toolCalls.single()
        assertEquals("f", call.name)
        assertEquals(
            mapOf(
                "n" to 3L,
                "x" to 1.5,
                "s" to "a",
                "b" to true,
                "z" to null,
                "l" to listOf(1L, 2L),
                "o" to mapOf("k" to "v"),
            ),
            call.arguments,
        )
    }

    @Test
    fun channels() {
        val message = parseMessageJson("""{"role":"assistant","content":[],"channels":{"thought":"hmm"}}""")
        assertEquals(mapOf("thought" to "hmm"), message.channels)
    }

    @Test
    fun aUserMessage() {
        assertEquals(
            """{"role":"user","content":[{"type":"text","text":"hi"}]}""",
            Message.user("hi").toJson().toString(),
        )
    }

    @Test
    fun aModelMessageWithAToolCall() {
        val message = Message.model(toolCalls = listOf(ToolCall("f", mapOf("n" to 3L))))
        assertEquals(
            """{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"f","arguments":{"n":3}}}]}""",
            message.toJson().toString(),
        )
    }

    @Test
    fun aToolResponse() {
        val message = Message.tool(Contents.of(Content.ToolResponse("f", mapOf("ok" to true))))
        assertEquals(
            """{"role":"tool","content":[{"type":"tool_response","name":"f","response":{"ok":true}}]}""",
            message.toJson().toString(),
        )
    }

    @Test
    fun anImageAndAQuestion() {
        val message = Message.user(Contents.of(Content.ImageBytes(byteArrayOf(1, 2, 3)), Content.Text("What?")))
        assertEquals(
            """{"role":"user","content":[{"type":"image","blob":"AQID"},{"type":"text","text":"What?"}]}""",
            message.toJson().toString(),
        )
        assertEquals("What?", message.toString())
    }

    @Test
    fun theToolsArray() {
        val provider = tool(fakeTool("""{"name":"f","description":"d","parameters":{"type":"object"}}"""))
        assertEquals(
            """[{"type":"function","function":{"name":"f","description":"d","parameters":{"type":"object"}}}]""",
            listOf(provider).toToolsJson().toString(),
        )
    }

    @Test
    fun aToolWithoutANameIsRejected() {
        assertFailsWith<IllegalArgumentException> { tool(fakeTool("""{"description":"d"}""")) }
    }

    @Test
    fun aToolThatIsNotJsonIsRejected() {
        assertFailsWith<IllegalArgumentException> { tool(fakeTool("not json")) }
    }

    private fun fakeTool(json: String) = object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = json
    }
}
