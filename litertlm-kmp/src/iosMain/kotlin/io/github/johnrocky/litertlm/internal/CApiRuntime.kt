@file:OptIn(ExperimentalForeignApi::class)

package io.github.johnrocky.litertlm.internal

import cnames.structs.LiteRtLmConversation
import cnames.structs.LiteRtLmConversationConfig
import cnames.structs.LiteRtLmEmbeddingEngine
import cnames.structs.LiteRtLmEmbeddingEngineSettings
import cnames.structs.LiteRtLmEmbeddingOptions
import cnames.structs.LiteRtLmEmbeddingResponse
import cnames.structs.LiteRtLmEngine
import cnames.structs.LiteRtLmEngineSettings
import cnames.structs.LiteRtLmInputData
import cnames.structs.LiteRtLmSamplerParams
import cnames.structs.LiteRtLmSessionConfig
import cnames.structs.LiteRtLmStreamChunk
import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.EmbeddingOptions
import io.github.johnrocky.litertlm.EmbeddingResponse
import io.github.johnrocky.litertlm.InputData
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.cinterop.LiteRtLmStatusCode
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_cancel_process
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_config_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_config_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_config_set_messages
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_config_set_session_config
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_config_set_tools
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_get_token_count
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_conversation_send_message_stream
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_engine_compute_embedding
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_engine_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_engine_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_engine_settings_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_engine_settings_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_engine_settings_set_cache_dir
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_engine_settings_set_max_input_length
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_engine_settings_set_num_threads
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_options_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_options_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_options_set_insert_special_tokens
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_options_set_normalize
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_options_set_output_size
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_response_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_response_get_size
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_embedding_response_get_values
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_engine_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_engine_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_engine_settings_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_engine_settings_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_engine_settings_set_cache_dir
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_engine_settings_set_max_num_tokens
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_engine_settings_set_num_threads
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_get_last_error_message
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_input_data_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_input_data_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_sampler_params_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_sampler_params_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_sampler_params_set_seed
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_sampler_params_set_temperature
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_sampler_params_set_top_k
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_sampler_params_set_top_p
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_session_config_create
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_session_config_delete
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_session_config_set_sampler_params
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_stream_chunk_get_error
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_stream_chunk_get_text
import io.github.johnrocky.litertlm.cinterop.dl_litert_lm_stream_chunk_is_final
import io.github.johnrocky.litertlm.cinterop.kLiteRtLmInputDataTypeText
import io.github.johnrocky.litertlm.cinterop.kLiteRtLmSamplerTypeTopP
import io.github.johnrocky.litertlm.cinterop.kLiteRtLmStatusOk
import io.github.johnrocky.litertlm.cinterop.litertlm_kmp_load_runtime
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.serialization.json.JsonArray
import platform.posix.size_tVar

internal actual fun platformRuntime(): NativeRuntime = CApiRuntime

private fun Backend.cName(): String = when (this) {
    is Backend.CPU -> "cpu"
    is Backend.GPU -> "gpu"
}

/** Throws the failure of a C call. The last error is thread-local, so this reads it on the calling thread. */
private fun check(status: LiteRtLmStatusCode) {
    if (status != kLiteRtLmStatusOk) {
        throw NativeFailure(status.toInt(), dl_litert_lm_get_last_error_message()?.toKString() ?: "Status code $status")
    }
}

/** FAILED_PRECONDITION in absl::StatusCode. */
private const val STATUS_FAILED_PRECONDITION = 9

/**
 * What is wrong with the runtime, or null. Once: the functions are found before the first call to any of them, and an
 * app does not gain a framework while it runs.
 */
private val runtimeProblem: String? by lazy { litertlm_kmp_load_runtime()?.toKString() }

private object CApiRuntime : NativeRuntime {
    override fun checkAvailable() {
        val problem = runtimeProblem ?: return
        // dlopen's text lists every folder it looked in, so it goes in the cause.
        throw LiteRtLmException.Internal(
            STATUS_FAILED_PRECONDITION,
            "This app does not have the iOS runtime of LiteRT-LM. In Xcode, add the Swift package " +
                "https://github.com/john-rocky/litert-lm-kmp, product CLiteRTLM, to the app target.",
            NativeFailure(null, problem),
        )
    }

    override fun createEngine(settings: NativeEngineSettings): NativeEngine = memScoped {
        val outSettings = alloc<CPointerVar<LiteRtLmEngineSettings>>()
        check(
            dl_litert_lm_engine_settings_create(
                settings.modelPath,
                settings.backend.cName(),
                settings.visionBackend?.cName(),
                null,
                outSettings.ptr,
            ),
        )
        val engineSettings = outSettings.value!!
        try {
            settings.maxNumTokens?.let { check(dl_litert_lm_engine_settings_set_max_num_tokens(engineSettings, it)) }
            (settings.backend as? Backend.CPU)?.threadCount?.let {
                check(dl_litert_lm_engine_settings_set_num_threads(engineSettings, it))
            }
            settings.cacheDir?.let { check(dl_litert_lm_engine_settings_set_cache_dir(engineSettings, it)) }
            val outEngine = alloc<CPointerVar<LiteRtLmEngine>>()
            check(dl_litert_lm_engine_create(engineSettings, outEngine.ptr))
            CApiEngine(outEngine.value!!)
        } finally {
            dl_litert_lm_engine_settings_delete(engineSettings)
        }
    }

    override fun createEmbeddingEngine(settings: NativeEmbeddingSettings): NativeEmbeddingEngine = memScoped {
        val outSettings = alloc<CPointerVar<LiteRtLmEmbeddingEngineSettings>>()
        check(
            dl_litert_lm_embedding_engine_settings_create(
                settings.modelPath,
                settings.backend.cName(),
                null,
                null,
                outSettings.ptr,
            ),
        )
        val engineSettings = outSettings.value!!
        try {
            (settings.backend as? Backend.CPU)?.threadCount?.let {
                check(dl_litert_lm_embedding_engine_settings_set_num_threads(engineSettings, it))
            }
            settings.cacheDir?.let { check(dl_litert_lm_embedding_engine_settings_set_cache_dir(engineSettings, it)) }
            settings.maxInputLength?.let {
                check(dl_litert_lm_embedding_engine_settings_set_max_input_length(engineSettings, it))
            }
            val outEngine = alloc<CPointerVar<LiteRtLmEmbeddingEngine>>()
            check(dl_litert_lm_embedding_engine_create(engineSettings, outEngine.ptr))
            CApiEmbeddingEngine(outEngine.value!!)
        } finally {
            dl_litert_lm_embedding_engine_settings_delete(engineSettings)
        }
    }
}

private class CApiEngine(private val handle: CPointer<LiteRtLmEngine>) : NativeEngine {
    override fun createConversation(settings: NativeConversationSettings): NativeConversation = memScoped {
        val outConfig = alloc<CPointerVar<LiteRtLmConversationConfig>>()
        check(dl_litert_lm_conversation_config_create(outConfig.ptr))
        val config = outConfig.value!!
        try {
            // The system instruction goes first in the messages, as in Google's Kotlin API.
            val messages = buildList {
                settings.systemInstruction?.let { add(Message.system(it).toJson()) }
                settings.messages.forEach { add(it.toJson()) }
            }
            if (messages.isNotEmpty()) {
                check(dl_litert_lm_conversation_config_set_messages(config, JsonArray(messages).toString()))
            }
            if (settings.tools.isNotEmpty()) {
                check(dl_litert_lm_conversation_config_set_tools(config, settings.tools.toToolsJson().toString()))
            }
            val sessionConfig = settings.sampler?.let { sampler ->
                val outSampler = alloc<CPointerVar<LiteRtLmSamplerParams>>()
                check(dl_litert_lm_sampler_params_create(kLiteRtLmSamplerTypeTopP, outSampler.ptr))
                val params = outSampler.value!!
                try {
                    check(dl_litert_lm_sampler_params_set_top_k(params, sampler.topK))
                    check(dl_litert_lm_sampler_params_set_top_p(params, sampler.topP.toFloat()))
                    check(dl_litert_lm_sampler_params_set_temperature(params, sampler.temperature.toFloat()))
                    check(dl_litert_lm_sampler_params_set_seed(params, sampler.seed))
                    val outSession = alloc<CPointerVar<LiteRtLmSessionConfig>>()
                    check(dl_litert_lm_session_config_create(outSession.ptr))
                    outSession.value!!.also { check(dl_litert_lm_session_config_set_sampler_params(it, params)) }
                } finally {
                    dl_litert_lm_sampler_params_delete(params)
                }
            }
            try {
                sessionConfig?.let { check(dl_litert_lm_conversation_config_set_session_config(config, it)) }
                val outConversation = alloc<CPointerVar<LiteRtLmConversation>>()
                check(dl_litert_lm_conversation_create(handle, config, outConversation.ptr))
                CApiConversation(outConversation.value!!)
            } finally {
                sessionConfig?.let { dl_litert_lm_session_config_delete(it) }
            }
        } finally {
            dl_litert_lm_conversation_config_delete(config)
        }
    }

    override fun close() {
        dl_litert_lm_engine_delete(handle)
    }
}

/**
 * What a stream callback needs. Owned by the native side from a successful start until the final chunk, so the
 * callback disposes of it. Chunks of one stream arrive one at a time.
 */
private class StreamContext(val sink: NativeStreamSink) {
    /** A chunk that could not be read. Reported at the end, in place of a normal finish. */
    var failure: NativeFailure? = null
}

/**
 * Runs on a thread of the library. It must return quickly, must not call into the library, and an exception escaping
 * it would end the process, so everything that can throw is inside a try.
 */
private fun onStreamChunk(data: COpaquePointer?, chunk: CPointer<LiteRtLmStreamChunk>?) {
    val ref = data!!.asStableRef<StreamContext>()
    val context = ref.get()
    memScoped {
        val outText = alloc<CPointerVar<ByteVar>>()
        val outError = alloc<CPointerVar<ByteVar>>()
        val outFinal = alloc<BooleanVar>()
        // A failed read leaves the pointer NULL. is_final is only written on success, so a failed read means final:
        // waiting for a chunk that may never come would hang the close that waits for this stream.
        outFinal.value = true
        dl_litert_lm_stream_chunk_get_text(chunk, outText.ptr)
        dl_litert_lm_stream_chunk_get_error(chunk, outError.ptr)
        dl_litert_lm_stream_chunk_is_final(chunk, outFinal.ptr)
        val text = outText.value?.toKString()
        if (text != null && context.failure == null) {
            try {
                context.sink.onChunk(parseMessageJson(text))
            } catch (t: Throwable) {
                context.failure = NativeFailure(null, "Unreadable reply chunk: ${t.message}", t)
            }
        }
        if (outFinal.value) {
            val error = outError.value?.toKString()
            when {
                error != null -> context.sink.onError(NativeFailure(null, error))
                context.failure != null -> context.sink.onError(context.failure!!)
                else -> context.sink.onDone()
            }
            ref.dispose()
        }
    }
}

private class CApiConversation(private val handle: CPointer<LiteRtLmConversation>) : NativeConversation {
    override fun sendMessageStream(message: Message, sink: NativeStreamSink) {
        val ref = StableRef.create(StreamContext(sink))
        val status = dl_litert_lm_conversation_send_message_stream(
            handle,
            message.toJson().toString(),
            null,
            null,
            staticCFunction { data, chunk -> onStreamChunk(data, chunk) },
            ref.asCPointer(),
        )
        if (status != kLiteRtLmStatusOk) {
            // A non-OK status means the stream did not start: no callback will run (c/conversation.cc).
            val text = dl_litert_lm_get_last_error_message()?.toKString() ?: "Status code $status"
            ref.dispose()
            throw NativeFailure(status.toInt(), text)
        }
    }

    override fun cancel() {
        check(dl_litert_lm_conversation_cancel_process(handle))
    }

    override fun tokenCount(): Int = memScoped {
        val out = alloc<size_tVar>()
        check(dl_litert_lm_conversation_get_token_count(handle, out.ptr))
        out.value.toInt()
    }

    override fun close() {
        dl_litert_lm_conversation_delete(handle)
    }
}

private class CApiEmbeddingEngine(private val handle: CPointer<LiteRtLmEmbeddingEngine>) : NativeEmbeddingEngine {
    override fun computeEmbedding(contents: List<InputData>, options: EmbeddingOptions): EmbeddingResponse = memScoped {
        val outOptions = alloc<CPointerVar<LiteRtLmEmbeddingOptions>>()
        check(dl_litert_lm_embedding_options_create(outOptions.ptr))
        val nativeOptions = outOptions.value!!
        val inputs = ArrayList<CPointer<LiteRtLmInputData>>(contents.size)
        try {
            options.normalize?.let { check(dl_litert_lm_embedding_options_set_normalize(nativeOptions, it)) }
            options.insertSpecialTokens?.let {
                check(dl_litert_lm_embedding_options_set_insert_special_tokens(nativeOptions, it))
            }
            options.outputSize?.let { check(dl_litert_lm_embedding_options_set_output_size(nativeOptions, it)) }
            for (input in contents) {
                when (input) {
                    is InputData.Text -> {
                        val bytes = input.text.cstr
                        val outInput = alloc<CPointerVar<LiteRtLmInputData>>()
                        // size - 1: the C string's size counts the terminator, the text does not have one.
                        check(
                            dl_litert_lm_input_data_create(
                                kLiteRtLmInputDataTypeText,
                                bytes.ptr,
                                (bytes.size - 1).convert(),
                                outInput.ptr,
                            ),
                        )
                        inputs += outInput.value!!
                    }
                }
            }
            val outResponse = alloc<CPointerVar<LiteRtLmEmbeddingResponse>>()
            check(
                dl_litert_lm_embedding_engine_compute_embedding(
                    handle,
                    allocArrayOf(inputs),
                    inputs.size.convert(),
                    nativeOptions,
                    outResponse.ptr,
                ),
            )
            val response = outResponse.value!!
            try {
                val outSize = alloc<size_tVar>()
                check(dl_litert_lm_embedding_response_get_size(response, outSize.ptr))
                val outValues = alloc<CPointerVar<FloatVar>>()
                check(dl_litert_lm_embedding_response_get_values(response, outValues.ptr))
                val values = outValues.value
                EmbeddingResponse(FloatArray(outSize.value.toInt()) { values!![it] })
            } finally {
                dl_litert_lm_embedding_response_delete(response)
            }
        } finally {
            inputs.forEach { dl_litert_lm_input_data_delete(it) }
            dl_litert_lm_embedding_options_delete(nativeOptions)
        }
    }

    override fun close() {
        dl_litert_lm_embedding_engine_delete(handle)
    }
}
