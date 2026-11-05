package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.EmbeddingEngine
import io.github.johnrocky.litertlm.EmbeddingEngineConfig
import io.github.johnrocky.litertlm.EmbeddingOptions
import io.github.johnrocky.litertlm.EmbeddingResponse
import io.github.johnrocky.litertlm.InputData
import io.github.johnrocky.litertlm.LiteRtLmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DefaultEmbeddingEngine(
    override val config: EmbeddingEngineConfig,
    private val runtime: NativeRuntime,
) : EmbeddingEngine {
    /** Calls on the handle must not overlap, and it must not be deleted during one. */
    private val calls = Mutex()
    private var native: NativeEmbeddingEngine? = null
    private var closed = false

    override fun isInitialized(): Boolean = runBlocking { calls.withLock { native != null } }

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        calls.withLock {
            if (closed) throw LiteRtLmException.ClosedUse("EmbeddingEngine")
            if (native != null) throw LiteRtLmException.AlreadyInitialized("EmbeddingEngine")
            runtime.checkAvailable()
            val fileSize = preflightModelFile(config.modelPath)
            checkAvailableMemory(config.minAvailableMemoryBytes, fileSize)
            native = try {
                runtime.createEmbeddingEngine(
                    NativeEmbeddingSettings(
                        modelPath = config.modelPath,
                        backend = config.backend,
                        cacheDir = config.cacheDir,
                        maxInputLength = config.maxInputLength,
                    ),
                )
            } catch (f: NativeFailure) {
                throw f.toLoadException(config.modelPath, config.backend)
            }
        }
    }

    override suspend fun computeEmbedding(contents: List<InputData>, options: EmbeddingOptions): EmbeddingResponse =
        withContext(Dispatchers.IO) {
            calls.withLock {
                if (closed) throw LiteRtLmException.ClosedUse("EmbeddingEngine")
                val engine = native ?: throw LiteRtLmException.NotInitialized("EmbeddingEngine")
                try {
                    engine.computeEmbedding(contents, options)
                } catch (f: NativeFailure) {
                    throw f.toTurnException()
                }
            }
        }

    override fun close() {
        runBlocking {
            calls.withLock {
                closed = true
                native.also { native = null }?.close()
            }
        }
    }
}
