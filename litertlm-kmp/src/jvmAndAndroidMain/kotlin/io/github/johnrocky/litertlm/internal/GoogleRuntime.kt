package io.github.johnrocky.litertlm.internal

import com.google.ai.edge.litertlm.Backend as GoogleBackend
import com.google.ai.edge.litertlm.Content as GoogleContent
import com.google.ai.edge.litertlm.Contents as GoogleContents
import com.google.ai.edge.litertlm.Conversation as GoogleConversation
import com.google.ai.edge.litertlm.ConversationConfig as GoogleConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine as GoogleEmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig as GoogleEmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions as GoogleEmbeddingOptions
import com.google.ai.edge.litertlm.Engine as GoogleEngine
import com.google.ai.edge.litertlm.EngineConfig as GoogleEngineConfig
import com.google.ai.edge.litertlm.InputData as GoogleInputData
import com.google.ai.edge.litertlm.Message as GoogleMessage
import com.google.ai.edge.litertlm.MessageCallback as GoogleMessageCallback
import com.google.ai.edge.litertlm.OpenApiTool as GoogleOpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig as GoogleSamplerConfig
import com.google.ai.edge.litertlm.ToolCall as GoogleToolCall
import com.google.ai.edge.litertlm.tool as googleTool
import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.Content
import io.github.johnrocky.litertlm.Contents
import io.github.johnrocky.litertlm.EmbeddingOptions
import io.github.johnrocky.litertlm.EmbeddingResponse
import io.github.johnrocky.litertlm.InputData
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.Role
import io.github.johnrocky.litertlm.ToolCall
import java.util.concurrent.CancellationException

internal actual fun platformRuntime(): NativeRuntime = GoogleRuntime

/** Delegates to Google's Kotlin API. This file is compiled twice: against litertlm-android and litertlm-jvm. */
private object GoogleRuntime : NativeRuntime {
    override fun createEngine(settings: NativeEngineSettings): NativeEngine = translated {
        val engine = GoogleEngine(
            GoogleEngineConfig(
                modelPath = settings.modelPath,
                backend = settings.backend.toGoogle(),
                visionBackend = settings.visionBackend?.toGoogle(),
                maxNumTokens = settings.maxNumTokens,
                cacheDir = settings.cacheDir,
            ),
        )
        engine.initialize()
        GoogleEngineHandle(engine)
    }

    override fun createEmbeddingEngine(settings: NativeEmbeddingSettings): NativeEmbeddingEngine = translated {
        val engine = GoogleEmbeddingEngine(
            GoogleEmbeddingEngineConfig(
                modelPath = settings.modelPath,
                backend = settings.backend.toGoogle(),
                cacheDir = settings.cacheDir,
                maxInputLength = settings.maxInputLength,
            ),
        )
        engine.initialize()
        GoogleEmbeddingHandle(engine)
    }
}

private class GoogleEngineHandle(private val engine: GoogleEngine) : NativeEngine {
    override fun createConversation(settings: NativeConversationSettings): NativeConversation = translated {
        val conversation = engine.createConversation(
            GoogleConversationConfig(
                systemInstruction = settings.systemInstruction?.toGoogle(),
                initialMessages = settings.messages.map { it.toGoogle() },
                tools = settings.tools.map { provider ->
                    googleTool(
                        object : GoogleOpenApiTool {
                            override fun getToolDescriptionJsonString(): String = provider.description.toString()

                            // Never called: automaticToolCalling is false below.
                            override fun execute(paramsJsonString: String): String =
                                throw UnsupportedOperationException()
                        },
                    )
                },
                samplerConfig = settings.sampler?.let {
                    GoogleSamplerConfig(topK = it.topK, topP = it.topP, temperature = it.temperature, seed = it.seed)
                },
                automaticToolCalling = false,
            ),
        )
        GoogleConversationHandle(conversation)
    }

    override fun close() = translated { engine.close() }
}

private class GoogleConversationHandle(private val conversation: GoogleConversation) : NativeConversation {
    override fun sendMessageStream(message: Message, sink: NativeStreamSink) = translated {
        conversation.sendMessageAsync(
            message.toGoogle(),
            object : GoogleMessageCallback {
                override fun onMessage(message: GoogleMessage) = sink.onChunk(message.toCommon())

                override fun onDone() = sink.onDone()

                override fun onError(throwable: Throwable) = sink.onError(throwable.toNativeFailure())
            },
        )
    }

    override fun cancel() = translated { conversation.cancelProcess() }

    override fun tokenCount(): Int = translated { conversation.getTokenCount() }

    override fun close() = translated { conversation.close() }
}

private class GoogleEmbeddingHandle(private val engine: GoogleEmbeddingEngine) : NativeEmbeddingEngine {
    override fun computeEmbedding(contents: List<InputData>, options: EmbeddingOptions): EmbeddingResponse = translated {
        val inputs = contents.map { input ->
            when (input) {
                is InputData.Text -> GoogleInputData.Text(input.text)
            }
        }
        val response = engine.computeEmbedding(
            inputs,
            GoogleEmbeddingOptions(
                normalize = options.normalize,
                insertSpecialTokens = options.insertSpecialTokens,
                outputSize = options.outputSize,
            ),
        )
        EmbeddingResponse(response.embedding)
    }

    override fun close() = translated { engine.close() }
}

private fun Backend.toGoogle(): GoogleBackend = when (this) {
    is Backend.CPU -> GoogleBackend.CPU(threadCount = threadCount)
    is Backend.GPU -> GoogleBackend.GPU()
}

private fun Message.toGoogle(): GoogleMessage = when (role) {
    Role.SYSTEM -> GoogleMessage.system(contents.toGoogle())
    Role.USER -> GoogleMessage.user(contents.toGoogle())
    Role.MODEL -> GoogleMessage.model(
        contents.toGoogle(),
        toolCalls.map { GoogleToolCall(it.name, it.arguments) },
        channels,
    )
    Role.TOOL -> GoogleMessage.tool(contents.toGoogle())
}

private fun Contents.toGoogle(): GoogleContents = GoogleContents.of(
    contents.map { content ->
        when (content) {
            is Content.Text -> GoogleContent.Text(content.text)
            is Content.ImageBytes -> GoogleContent.ImageBytes(content.bytes)
            is Content.ToolResponse -> GoogleContent.ToolResponse(content.name, content.response)
        }
    },
)

private fun GoogleMessage.toCommon(): Message = Message.model(
    Contents.of(contents.contents.filterIsInstance<GoogleContent.Text>().map { Content.Text(it.text) }),
    toolCalls.map { call -> ToolCall(call.name, call.arguments.mapValues { (_, v) -> plainNumbers(v) }) },
    channels,
)

/** Gson's numbers are LazilyParsedNumber; the shared API promises Long or Double, as on iOS. */
private fun plainNumbers(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to plainNumbers(v) }
    is List<*> -> value.map { plainNumbers(it) }
    is Number -> value.toString().let { it.toLongOrNull() ?: it.toDoubleOrNull() ?: value }
    else -> value
}

private val JNI_STATUS = Regex("^Status Code: (\\d+)\\. Message: ")

private fun Throwable.toNativeFailure(): NativeFailure = when (this) {
    is NativeFailure -> this
    is CancellationException -> NativeFailure(1, message ?: "Cancelled", this)
    else -> {
        val text = message ?: toString()
        NativeFailure(JNI_STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull(), text, this)
    }
}

/** A library that cannot load is a LinkageError; it is reported like any other native failure. */
private inline fun <T> translated(block: () -> T): T = try {
    block()
} catch (e: Exception) {
    throw e.toNativeFailure()
} catch (e: LinkageError) {
    throw e.toNativeFailure()
}
