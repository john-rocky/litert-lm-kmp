package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/** Runs until something stops it. */
private class HangingClient : FeatureClient<String, String> {
    val started = CompletableDeferred<Unit>()
    var cancelled = false
    var closes = 0

    override val activeProvider: ActiveProvider? = null
    override val activeProviderReason: String? = null

    private suspend fun hang(): Nothing {
        started.complete(Unit)
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        }
    }

    override suspend fun checkFeatureStatus(): Int = hang()

    override suspend fun downloadFeature(callback: DownloadCallback): Unit = hang()

    override suspend fun prepareInferenceEngine(): Unit = hang()

    override suspend fun getBaseModelName(): String = hang()

    override suspend fun runInference(request: String, callback: StreamingCallback?): String = hang()

    override fun close() {
        closes++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GuardedClientTest {
    @Test
    fun aRequestDuringCloseFailsWithCancelledAndTheCallerGoesOn() = runTest {
        val inner = HangingClient()
        val client = GuardedClient(inner)
        val request = async {
            try {
                client.runInference("q", null)
                null
            } catch (e: GenAiException) {
                e
            }
        }
        inner.started.await()
        client.close()
        val failure = assertNotNull(request.await())
        assertEquals(ErrorCode.CANCELLED, failure.errorCode)
        assertTrue(inner.cancelled)
        yield()
    }

    @Test
    fun everythingAfterCloseFailsWithCancelled() = runTest {
        val inner = FakeFeature()
        val client = GuardedClient(inner)
        client.close()
        assertEquals(ErrorCode.CANCELLED, codeOf { client.runInference("q", null) })
        assertEquals(ErrorCode.CANCELLED, codeOf { client.checkFeatureStatus() })
        assertEquals(ErrorCode.CANCELLED, codeOf { client.prepareInferenceEngine() })
        assertEquals(ErrorCode.CANCELLED, codeOf { client.getBaseModelName() })
        assertEquals(0, inner.inferences)
    }

    @Test
    fun aDownloadAfterCloseReportsToItsCallback() = runTest {
        val client = GuardedClient(FakeFeature())
        client.close()
        var code: Int? = null
        client.downloadFeature(
            object : DownloadCallback {
                override fun onDownloadFailed(e: GenAiException) {
                    code = e.errorCode
                }
            },
        )
        assertEquals(ErrorCode.CANCELLED, code)
    }

    @Test
    fun aSecondCloseDoesNothing() {
        val inner = FakeFeature()
        val client = GuardedClient(inner)
        client.close()
        client.close()
        assertEquals(1, inner.closes)
    }

    @Test
    fun cancellingTheCallerIsStillACancellationOfTheCaller() = runTest {
        val inner = HangingClient()
        val client = GuardedClient(inner)
        val request = async { client.runInference("q", null) }
        inner.started.await()
        request.cancel()
        assertFailsWith<CancellationException> { request.await() }
        assertTrue(inner.cancelled)
        // The client is not closed by that.
        assertEquals(0, inner.closes)
    }

    @Test
    fun theProvidersFailuresPassThrough() = runTest {
        val inner = FakeFeature(failures = listOf(GenAiException("no", null, ErrorCode.BUSY)))
        assertEquals(ErrorCode.BUSY, codeOf { GuardedClient(inner).runInference("q", null) })
    }
}
