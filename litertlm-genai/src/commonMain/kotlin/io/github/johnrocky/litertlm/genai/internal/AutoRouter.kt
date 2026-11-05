package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.FeatureStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay

/** What Gemini Nano answers when it cannot do this now, but the local model might. */
private val FALL_BACK_ON = setOf(
    ErrorCode.BACKGROUND_USE_BLOCKED,
    ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
    ErrorCode.NOT_AVAILABLE,
    ErrorCode.NOT_SUPPORTED,
    ErrorCode.AICORE_INCOMPATIBLE,
    ErrorCode.NEEDS_SYSTEM_UPDATE,
    ErrorCode.NOT_ENOUGH_DISK_SPACE,
)

private fun statusName(status: Int): String = when (status) {
    FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
    FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
    FeatureStatus.DOWNLOADING -> "DOWNLOADING"
    FeatureStatus.AVAILABLE -> "AVAILABLE"
    else -> "status $status"
}

/** See [io.github.johnrocky.litertlm.genai.common.ModelProvider.Auto] for the rules. */
internal class AutoRouter<Req, Res>(
    private val nano: FeatureClient<Req, Res>?,
    private val local: FeatureClient<Req, Res>,
    private val busyRetries: Int,
    private val firstBackoff: Duration = 1.seconds,
    private val noNano: String = noNanoReason,
) : FeatureClient<Req, Res> {
    private val lock = SynchronizedObject()
    private var provider: ActiveProvider? = null
    private var reason: String? = null

    override val activeProvider: ActiveProvider? get() = synchronized(lock) { provider }
    override val activeProviderReason: String? get() = synchronized(lock) { reason }

    private fun note(provider: ActiveProvider, reason: String) = synchronized(lock) {
        this.provider = provider
        this.reason = reason
    }

    /** Asked on every request: the status can change, as when AICore fetches its configuration. */
    override suspend fun chosen(): FeatureClient<Req, Res> {
        if (nano == null) {
            note(ActiveProvider.LITERT_LM, noNano)
            return local
        }
        val status = try {
            nano.checkFeatureStatus()
        } catch (e: GenAiException) {
            note(ActiveProvider.LITERT_LM, "Gemini Nano's status check failed: ${e.message}")
            return local
        }
        if (status == FeatureStatus.UNAVAILABLE) {
            note(ActiveProvider.LITERT_LM, "Gemini Nano is UNAVAILABLE on this device.")
            return local
        }
        // A request to Gemini Nano would first wait for this download.
        val notFetched = status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING
        if (notFetched && local.checkFeatureStatus() == FeatureStatus.AVAILABLE) {
            note(
                ActiveProvider.LITERT_LM,
                "Gemini Nano has not fetched this feature yet (${statusName(status)}), so the local model on this device answers.",
            )
            return local
        }
        note(ActiveProvider.GEMINI_NANO, "Gemini Nano's status is ${statusName(status)}.")
        return nano
    }

    private suspend fun <T> answer(mayFallBack: () -> Boolean = { true }, call: suspend (FeatureClient<Req, Res>) -> T): T {
        val first = chosen()
        if (first === local) return call(local)
        var busyTries = 0
        while (true) {
            val failure = try {
                return call(first)
            } catch (e: GenAiException) {
                e
            }
            if (!mayFallBack()) throw failure
            when (failure.errorCode) {
                ErrorCode.BUSY -> if (busyTries < busyRetries) {
                    delay(failure.retryDelay ?: (firstBackoff * (1 shl busyTries)))
                    busyTries++
                    continue
                }
                in FALL_BACK_ON -> Unit
                else -> throw failure
            }
            // A fallback does not start a 2 GB download behind the app's back.
            if (local.checkFeatureStatus() != FeatureStatus.AVAILABLE) throw failure
            note(ActiveProvider.LITERT_LM, "Gemini Nano failed with error code ${failure.errorCode}: ${failure.message}")
            return call(local)
        }
    }

    override suspend fun checkFeatureStatus(): Int = chosen().checkFeatureStatus()

    override suspend fun downloadFeature(callback: DownloadCallback) = chosen().downloadFeature(callback)

    override suspend fun prepareInferenceEngine() = answer { it.prepareInferenceEngine() }

    /** After a fallback this is the local model's, so that it reads as a pair with [activeProvider]. */
    override suspend fun getBaseModelName(): String =
        if (activeProvider == ActiveProvider.LITERT_LM) local.getBaseModelName() else answer { it.getBaseModelName() }

    override suspend fun runInference(request: Req, callback: StreamingCallback?): Res {
        var delivered = false
        val counting = callback?.let {
            StreamingCallback { text ->
                delivered = true
                it.onNewText(text)
            }
        }
        return answer(mayFallBack = { !delivered }) { it.runInference(request, counting) }
    }

    override fun close() {
        nano?.close()
        local.close()
    }
}
