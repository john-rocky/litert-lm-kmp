package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job

/**
 * Gives every provider the same `close`: it does not block, a second call does nothing, what is running stops and fails
 * with CANCELLED, and so does anything that comes later. Stopping is by coroutine cancellation, which both the
 * LiteRT-LM flow and the await of an ML Kit future turn into a real stop.
 */
internal class GuardedClient<Req, Res>(private val inner: FeatureClient<Req, Res>) : FeatureClient<Req, Res> {
    private val lock = SynchronizedObject()
    private var closed = false
    private val running = HashSet<Job>()

    override val activeProvider: ActiveProvider? get() = inner.activeProvider
    override val activeProviderReason: String? get() = inner.activeProviderReason

    private fun closedFailure(cause: Throwable? = null) =
        GenAiException("This client is closed.", cause, GenAiException.ErrorCode.CANCELLED)

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        coroutineScope {
            val job = coroutineContext.job
            synchronized(lock) {
                if (closed) throw closedFailure()
                running += job
            }
            try {
                block()
            } finally {
                synchronized(lock) { running -= job }
            }
        }
    } catch (e: CancellationException) {
        // Cancelled by close(), not by the caller: that is a failure of the request, and the caller carries on.
        if (currentCoroutineContext().isActive && synchronized(lock) { closed }) throw closedFailure(e)
        throw e
    }

    override suspend fun checkFeatureStatus(): Int = guarded { inner.checkFeatureStatus() }

    override suspend fun downloadFeature(callback: DownloadCallback) {
        // A closed client reports through the callback too, as the other download failures do.
        try {
            guarded { inner.downloadFeature(callback) }
        } catch (e: GenAiException) {
            callback.onDownloadFailed(e)
        }
    }

    override suspend fun prepareInferenceEngine() = guarded { inner.prepareInferenceEngine() }

    override suspend fun getBaseModelName(): String = guarded { inner.getBaseModelName() }

    override suspend fun runInference(request: Req, callback: StreamingCallback?): Res =
        guarded { inner.runInference(request, callback) }

    suspend fun <T> withChosen(block: suspend (FeatureClient<Req, Res>) -> T): T = guarded { block(inner.chosen()) }

    override fun close() {
        val jobs = synchronized(lock) {
            if (closed) return
            closed = true
            running.toList()
        }
        jobs.forEach { it.cancel() }
        inner.close()
    }
}
