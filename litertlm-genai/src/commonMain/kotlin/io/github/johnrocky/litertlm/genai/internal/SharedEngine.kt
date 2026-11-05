package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the runtime says (LiteRT-LM v0.19.0) when an engine asks a model for a vision part it does not have. */
private const val NO_VISION_PART_TEXT = "tf_lite_vision_encoder not found"

/** One [SharedEngine] for each file and set of settings, however many clients use it. */
internal class EnginePool(
    private val factory: (EngineConfig) -> Engine = { Engine(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = SynchronizedObject()
    private val engines = HashMap<EngineConfig, SharedEngine>()
    private val room = ModelRoom()

    /** Counts a client in. It must [SharedEngine.release] once. Loads nothing yet. */
    fun acquire(config: EngineConfig): SharedEngine = synchronized(lock) {
        engines.getOrPut(config) { SharedEngine(config, factory, scope, room) }.also { it.retain() }
    }

    companion object {
        val shared: EnginePool by lazy { EnginePool() }
    }
}

/**
 * The room for one loaded model, which all the models of a pool share. A second one does not fit on a phone: on an
 * iPhone 18 Pro a process can map about 7,800 MiB and one load of Gemma 4 E2B mapped up to 5,305 MiB, and a load that does
 * not fit fails with misleading errors or ends the process.
 */
internal class ModelRoom {
    /** Turns run one at a time, in the order they ask, whichever model they use. */
    val turns = Mutex()

    /** The last model that loaded. Read and written with [turns] held. */
    var occupant: SharedEngine? = null
}

/**
 * A model loaded at most once, for all the clients that share it. When the first image arrives the engine is replaced by
 * one that has the vision part, which costs memory only once something needs it. When a client of another model or other
 * settings has a turn, the engine is closed, though its clients stay open, and their next turn loads it again.
 *
 * [config] has the vision backend to use when one is needed.
 */
internal class SharedEngine(
    private val config: EngineConfig,
    private val factory: (EngineConfig) -> Engine,
    private val scope: CoroutineScope,
    private val room: ModelRoom = ModelRoom(),
) {
    private val lock = SynchronizedObject()
    private var clients = 0

    /** Read and written with the turn held. */
    private var engine: Engine? = null

    /** Whether the file turned out to have no vision part. Read and written with the turn held. */
    private var noVisionPart = false

    fun retain() {
        synchronized(lock) { clients++ }
    }

    /** Does not block: the last client's close releases the model once the turn in progress has stopped. */
    fun release() {
        val last = synchronized(lock) { --clients == 0 }
        if (!last) return
        scope.launch {
            room.turns.withLock {
                // A new client may have come in the meantime. It keeps what is loaded.
                if (synchronized(lock) { clients } == 0) {
                    closeEngine()
                    noVisionPart = false
                }
            }
        }
    }

    suspend fun <T> withEngine(needsVision: Boolean, block: suspend (Engine) -> T): T = room.turns.withLock {
        if (needsVision && noVisionPart) throw noVisionPartFailure(null)
        try {
            block(loaded(needsVision))
        } catch (e: LiteRtLmException.Internal) {
            if (NO_VISION_PART_TEXT !in e.message.orEmpty()) throw e
            // Every conversation of such an engine fails, text too, and this one failed before it wrote anything.
            noVisionPart = true
            closeEngine()
            if (needsVision) throw noVisionPartFailure(e)
            block(loaded(needsVision = false))
        }
    }

    private fun noVisionPartFailure(cause: Throwable?) = GenAiException(
        "This model has no vision part, so it cannot take images. Choose a model that has one, such as Gemma 4 E2B.",
        cause,
        ErrorCode.NOT_SUPPORTED,
    )

    private suspend fun closeEngine() {
        val closing = engine ?: return
        engine = null
        // Blocks until a reply has stopped, so off the caller's thread. A cancelled turn still finishes the close.
        withContext(NonCancellable + Dispatchers.IO) { closing.close() }
    }

    private suspend fun loaded(needsVision: Boolean): Engine {
        engine?.let { if (!needsVision || it.engineConfig.visionBackend != null) return it }
        closeEngine()
        // Loading checks what memory is free now, so the model in the room goes first.
        room.occupant?.closeEngine()
        val created = factory(if (needsVision) config else config.copy(visionBackend = null))
        try {
            created.initialize()
        } catch (t: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { created.close() }
            throw t
        }
        engine = created
        room.occupant = this
        return created
    }
}
