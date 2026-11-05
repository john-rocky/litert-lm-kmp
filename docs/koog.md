# Koog

[Koog](https://github.com/JetBrains/koog) agents can run on the lower level through a small `LLMClient`. This needs `ai.koog:agents-core:1.3.0` as well, and `minSdk` 26 on Android: below that, the build fails when it dexes the Jackson library that Koog brings.

```kotlin
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.ConversationConfig
import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.Message as LiteRtMessage

object OnDevice : LLMProvider("on-device", "LiteRT-LM on this device")

private fun Message.text() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }

class LiteRtLmClient(private val engine: Engine) : LLMClient() {
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
        val system = prompt.messages.filterIsInstance<Message.System>().joinToString("\n") { it.text() }
        val turns = prompt.messages.filter { it is Message.User || it is Message.Assistant }
        val config = ConversationConfig(
            systemInstruction = if (system.isEmpty()) null else Contents.of(system),
            initialMessages = turns.dropLast(1).map {
                if (it is Message.User) LiteRtMessage.user(it.text()) else LiteRtMessage.model(it.text())
            },
        )
        val reply = engine.createConversation(config).use { it.sendMessage(turns.last().text()) }
        return Message.Assistant(reply.toString(), ResponseMetaInfo.create(KoogClock.System))
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        throw UnsupportedOperationException("This client does not moderate.")

    override fun llmProvider(): LLMProvider = OnDevice

    override fun close() {}
}

suspend fun askAgent(engine: Engine, question: String): String {
    val model = LLModel(OnDevice, "gemma-4-e2b", listOf(LLMCapability.Completion), contextLength = 4096)
    val executor = MultiLLMPromptExecutor(mapOf(OnDevice to LiteRtLmClient(engine)))
    return AIAgent(executor, model, systemPrompt = "Answer in one short sentence.").run(question)
}
```

This client sends text only. For Koog's tools, map each `ToolDescriptor` to `tool(...)` and `reply.toolCalls` to `MessagePart.Tool.Call`.
