# The lower level

`Engine`, `Conversation` and `EmbeddingEngine` take a `.litertlm` file by path. A `Conversation` keeps its turns.

```kotlin
import io.github.johnrocky.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun chatOnEngine(path: String, show: (String) -> Unit) {
    val engine = Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = 4096))
    try {
        engine.initialize()
        val conversation = engine.createConversation()
        conversation.stream("What is the capital of France?").collect { show(it) }
        conversation.stream("And of Japan?").collect { show(it) }
    } finally {
        // close() waits for a running reply to stop, so it stays off the main thread.
        withContext(Dispatchers.Default) { engine.close() }
    }
}
```

Tools come back to the caller. The library does not run them:

```kotlin
import io.github.johnrocky.litertlm.*

object GetWeather : OpenApiTool {
    override fun getToolDescriptionJsonString() =
        """{"name":"get_weather","description":"Get the current weather for a city.",""" +
            """"parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}"""
}

suspend fun askWithTool(engine: Engine): String {
    val conversation = engine.createConversation(ConversationConfig(tools = listOf(tool(GetWeather))))
    val reply = conversation.sendMessage("What is the weather in Tokyo right now? Use the tool.")
    val call = reply.toolCalls.firstOrNull() ?: return reply.toString()
    val result = Content.ToolResponse(call.name, mapOf("temperature_c" to 21, "condition" to "sunny"))
    return conversation.sendMessage(Message.tool(Contents.of(result))).toString()
}
```

An image needs `visionBackend`, and an embedding its own model:

```kotlin
import io.github.johnrocky.litertlm.*

suspend fun describeOnEngine(path: String, jpeg: ByteArray): String {
    val engine = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), visionBackend = Backend.CPU()))
    return engine.use {
        it.initialize()
        val question = Contents.of(Content.ImageBytes(jpeg), Content.Text("What animal is in this picture?"))
        it.createConversation().sendMessage(question).toString()
    }
}

// For example litert-community/embeddinggemma-2-text-270m-litert-lm, 165 MB.
suspend fun embed(path: String, text: String): FloatArray =
    EmbeddingEngine(EmbeddingEngineConfig(modelPath = path)).use {
        it.initialize()
        it.computeEmbedding(listOf(InputData.Text(text))).embedding
    }
```

`initialize()` first checks the file and the memory that is free. By default it wants half the file's size free on Android and iOS, or three quarters with a `visionBackend`, and it checks nothing on the JVM. Below that it throws `InsufficientMemory`, because on iOS the system kills a process that runs out, and nothing can catch that. `minAvailableMemoryBytes` changes the figure, and 0 turns the check off.

`Engine` is outside the one-model rule of the task clients, and on iOS the address space can run out before the memory does. So before you load a second model with `Engine`, close the first, and close any task client that has a model loaded. A load that does not fit reports a missing part of the model, or ends the process. `initialize()` throws `InsufficientMemory` instead when less address space is free than 1.4 times the file's size, or 2 times with a `visionBackend`, unless `minAvailableMemoryBytes` is 0. To keep two models loaded, sign the app with `com.apple.developer.kernel.extended-virtual-addressing`. The memory check still applies then. [`docs/tested-devices.md`](tested-devices.md) has the figures.

## Koog

[Koog](https://github.com/JetBrains/koog) agents can run on the lower level through a small `LLMClient`. This needs `ai.koog:agents-core:1.3.0` as well, and `minSdk` 26 on Android: below that, the build fails when it dexes the Jackson library that Koog brings. [`docs/koog.md`](koog.md) has the code.
