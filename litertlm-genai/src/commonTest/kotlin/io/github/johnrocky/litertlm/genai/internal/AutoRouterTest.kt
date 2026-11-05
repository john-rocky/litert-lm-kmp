package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.FeatureStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

private fun failure(code: Int, retryDelay: Duration? = null) = GenAiException("nano says $code", null, code, retryDelay)

private val FALLBACK_CODES = listOf(
    ErrorCode.BACKGROUND_USE_BLOCKED,
    ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
    ErrorCode.NOT_AVAILABLE,
    ErrorCode.NOT_SUPPORTED,
    ErrorCode.AICORE_INCOMPATIBLE,
    ErrorCode.NEEDS_SYSTEM_UPDATE,
    ErrorCode.NOT_ENOUGH_DISK_SPACE,
)

/** Codes about the request or the reply: the local model would fail on them too, or the app must see them. */
private val OTHER_CODES = listOf(
    ErrorCode.UNKNOWN,
    ErrorCode.REQUEST_PROCESSING_ERROR,
    ErrorCode.CANCELLED,
    ErrorCode.RESPONSE_PROCESSING_ERROR,
    ErrorCode.REQUEST_TOO_LARGE,
    ErrorCode.REQUEST_TOO_SMALL,
    ErrorCode.RESPONSE_GENERATION_ERROR,
    ErrorCode.INVALID_INPUT_IMAGE,
)

private val NOT_FETCHED = listOf(FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING)

private val NOT_ON_THE_DEVICE = listOf(FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING, FeatureStatus.UNAVAILABLE)

@OptIn(ExperimentalCoroutinesApi::class)
class AutoRouterTest {
    private fun router(nano: FakeFeature?, local: FakeFeature, busyRetries: Int = 3) =
        AutoRouter(nano, local, busyRetries)

    @Test
    fun withoutGeminiNanoTheLocalModelAnswers() = runTest {
        val local = FakeFeature("local")
        val router = router(null, local)
        assertEquals("local", router.runInference("q", null))
        assertEquals(ActiveProvider.LITERT_LM, router.activeProvider)
        assertContains(router.activeProviderReason.orEmpty(), "Android")
    }

    @Test
    fun withoutTheArtifactTheReasonSaysWhatToAdd() = runTest {
        val local = FakeFeature("local")
        val router = AutoRouter(null, local, busyRetries = 3, noNano = NO_NANO_ARTIFACT)
        assertEquals(FeatureStatus.AVAILABLE, router.checkFeatureStatus())
        assertEquals("local", router.runInference("q", null))
        assertEquals(ActiveProvider.LITERT_LM, router.activeProvider)
        assertEquals(NO_NANO_ARTIFACT, router.activeProviderReason)
    }

    @Test
    fun unavailableGeminiNanoGoesToTheLocalModel() = runTest {
        val nano = FakeFeature("nano", status = FeatureStatus.UNAVAILABLE)
        val router = router(nano, FakeFeature("local"))
        assertEquals("local", router.runInference("q", null))
        assertEquals(0, nano.inferences)
        assertEquals(ActiveProvider.LITERT_LM, router.activeProvider)
    }

    @Test
    fun anAvailableGeminiNanoAnswersWhateverTheLocalModelIs() = runTest {
        for (localStatus in listOf(FeatureStatus.AVAILABLE) + NOT_ON_THE_DEVICE) {
            val local = FakeFeature("local", status = localStatus)
            val router = router(FakeFeature("nano"), local)
            assertEquals("nano", router.runInference("q", null), "local status $localStatus")
            assertEquals(0, local.inferences)
            assertEquals(ActiveProvider.GEMINI_NANO, router.activeProvider)
            assertNotNull(router.activeProviderReason)
        }
    }

    @Test
    fun whatGeminiNanoHasNotFetchedGoesToALocalModelThatIsThere() = runTest {
        for (status in NOT_FETCHED) {
            val nano = FakeFeature("nano", status = status)
            val local = FakeFeature("local")
            val router = router(nano, local)
            assertEquals("local", router.runInference("q", null), "status $status")
            assertEquals(0, nano.inferences, "status $status")
            assertEquals(0, nano.downloads, "status $status")
            assertEquals(0, local.downloads, "status $status")
            assertEquals(ActiveProvider.LITERT_LM, router.activeProvider)
            assertContains(router.activeProviderReason.orEmpty(), "not fetched")
        }
    }

    @Test
    fun whatGeminiNanoHasNotFetchedGoesToGeminiNanoWithoutALocalModel() = runTest {
        for (status in NOT_FETCHED) {
            for (localStatus in NOT_ON_THE_DEVICE) {
                val nano = FakeFeature("nano", status = status)
                val local = FakeFeature("local", status = localStatus)
                val router = router(nano, local)
                assertEquals("nano", router.runInference("q", null), "status $status, local status $localStatus")
                assertEquals(0, local.inferences)
                assertEquals(0, local.downloads)
                assertEquals(ActiveProvider.GEMINI_NANO, router.activeProvider)
            }
        }
    }

    @Test
    fun geminiNanoAnswersFromTheFirstRequestAfterItHasFetched() = runTest {
        val nano = FakeFeature("nano", status = FeatureStatus.DOWNLOADABLE)
        val router = router(nano, FakeFeature("local"))
        assertEquals("local", router.runInference("q", null))
        nano.status = FeatureStatus.AVAILABLE
        assertEquals("nano", router.runInference("q", null))
        assertEquals(ActiveProvider.GEMINI_NANO, router.activeProvider)
    }

    @Test
    fun aFailedStatusCheckCountsAsUnavailable() = runTest {
        val nano = FakeFeature("nano", statusFailure = failure(ErrorCode.UNKNOWN))
        val router = router(nano, FakeFeature("local"))
        assertEquals("local", router.runInference("q", null))
        assertEquals(0, nano.inferences)
        assertContains(router.activeProviderReason.orEmpty(), "status check failed")
    }

    @Test
    fun theStatusIsAskedOnEveryRequest() = runTest {
        val nano = FakeFeature("nano", status = FeatureStatus.UNAVAILABLE)
        val router = router(nano, FakeFeature("local"))
        assertEquals("local", router.runInference("q", null))
        nano.status = FeatureStatus.AVAILABLE
        assertEquals("nano", router.runInference("q", null))
        assertEquals(ActiveProvider.GEMINI_NANO, router.activeProvider)
    }

    @Test
    fun theseFailuresFallBackToALocalModelThatIsThere() = runTest {
        for (code in FALLBACK_CODES) {
            val nano = FakeFeature("nano", failures = listOf(failure(code)))
            val local = FakeFeature("local")
            val router = router(nano, local)
            assertEquals("local", router.runInference("q", null), "code $code")
            assertEquals(1, nano.inferences, "code $code: one try")
            assertEquals(ActiveProvider.LITERT_LM, router.activeProvider, "code $code")
            assertContains(router.activeProviderReason.orEmpty(), "$code")
        }
    }

    @Test
    fun aFallbackNeverStartsADownload() = runTest {
        for (code in FALLBACK_CODES + ErrorCode.BUSY) {
            for (status in listOf(FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING, FeatureStatus.UNAVAILABLE)) {
                val nano = FakeFeature("nano", failures = List(4) { failure(code) })
                val local = FakeFeature("local", status = status)
                assertEquals(code, codeOf { router(nano, local).runInference("q", null) }, "code $code, local status $status")
                assertEquals(0, local.inferences)
                assertEquals(0, local.downloads)
            }
        }
    }

    @Test
    fun otherFailuresAreReportedAsTheyAre() = runTest {
        for (code in OTHER_CODES) {
            val nano = FakeFeature("nano", failures = listOf(failure(code)))
            val local = FakeFeature("local")
            assertEquals(code, codeOf { router(nano, local).runInference("q", null) }, "code $code")
            assertEquals(1, nano.inferences, "code $code: one try")
            assertEquals(0, local.inferences, "code $code")
        }
    }

    @Test
    fun busyBacksOffThreeTimesThenFallsBack() = runTest {
        val nano = FakeFeature("nano", failures = List(4) { failure(ErrorCode.BUSY) })
        val local = FakeFeature("local")
        val router = router(nano, local)
        assertEquals("local", router.runInference("q", null))
        assertEquals(4, nano.inferences, "the first try and three retries")
        assertEquals((1 + 2 + 4).seconds.inWholeMilliseconds, currentTime, "1 s, 2 s, 4 s")
        assertEquals(ActiveProvider.LITERT_LM, router.activeProvider)
    }

    @Test
    fun busyStopsAsSoonAsGeminiNanoAnswers() = runTest {
        val nano = FakeFeature("nano", failures = List(2) { failure(ErrorCode.BUSY) })
        val local = FakeFeature("local")
        val router = router(nano, local)
        assertEquals("nano", router.runInference("q", null))
        assertEquals(3, nano.inferences)
        assertEquals(0, local.inferences)
        assertEquals(ActiveProvider.GEMINI_NANO, router.activeProvider)
        assertEquals((1 + 2).seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun busyHonoursTheDelayGeminiNanoAsksFor() = runTest {
        val nano = FakeFeature("nano", failures = listOf(failure(ErrorCode.BUSY, retryDelay = 30.seconds)))
        assertEquals("nano", router(nano, FakeFeature("local")).runInference("q", null))
        assertEquals(30.seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun theNumberOfBusyRetriesCanBeChanged() = runTest {
        val nano = FakeFeature("nano", failures = List(10) { failure(ErrorCode.BUSY) })
        assertEquals("local", router(nano, FakeFeature("local"), busyRetries = 0).runInference("q", null))
        assertEquals(1, nano.inferences)
        assertEquals(0, currentTime)
    }

    @Test
    fun onceTextIsDeliveredThereIsNoFallback() = runTest {
        for (code in FALLBACK_CODES + ErrorCode.BUSY) {
            val nano = FakeFeature("nano", failures = listOf(failure(code)), pieces = listOf("partial"))
            val local = FakeFeature("local")
            val received = StringBuilder()
            val callback = StreamingCallback { received.append(it) }
            assertEquals(code, codeOf { router(nano, local).runInference("q", callback) }, "code $code")
            assertEquals(1, nano.inferences, "code $code: no retry either")
            assertEquals(0, local.inferences, "code $code")
            assertEquals("partial", received.toString())
        }
    }

    @Test
    fun aFailureBeforeAnyTextStillFallsBackWithACallback() = runTest {
        val nano = FakeFeature("nano", failures = listOf(failure(ErrorCode.BACKGROUND_USE_BLOCKED)))
        val local = FakeFeature("local", pieces = listOf("whole"))
        val received = StringBuilder()
        val answer = router(nano, local).runInference("q", StreamingCallback { received.append(it) })
        assertEquals("local", answer)
        assertEquals("whole", received.toString())
    }

    @Test
    fun theDownloadGoesToWhoeverWouldAnswer() = runTest {
        val callback = object : DownloadCallback {}

        val nano = FakeFeature("nano", status = FeatureStatus.DOWNLOADABLE)
        val absent = FakeFeature("local", status = FeatureStatus.DOWNLOADABLE)
        router(nano, absent).downloadFeature(callback)
        assertEquals(1, nano.downloads)
        assertEquals(0, absent.downloads)

        val missing = FakeFeature("nano", status = FeatureStatus.UNAVAILABLE)
        val local = FakeFeature("local")
        router(missing, local).downloadFeature(callback)
        assertEquals(0, missing.downloads)
        assertEquals(1, local.downloads)

        // With the local model there, Auto does not fetch what Gemini Nano is missing.
        val notFetched = FakeFeature("nano", status = FeatureStatus.DOWNLOADABLE)
        val there = FakeFeature("local")
        router(notFetched, there).downloadFeature(callback)
        assertEquals(0, notFetched.downloads)
        assertEquals(1, there.downloads)
    }

    @Test
    fun theStatusIsTheOneOfWhoeverWouldAnswer() = runTest {
        val local = FakeFeature("local", status = FeatureStatus.DOWNLOADABLE)
        assertEquals(FeatureStatus.DOWNLOADABLE, router(null, local).checkFeatureStatus())
        val nano = FakeFeature("nano", status = FeatureStatus.DOWNLOADING)
        val router = router(nano, local)
        assertEquals(FeatureStatus.DOWNLOADING, router.checkFeatureStatus())
        assertEquals(ActiveProvider.GEMINI_NANO, router.activeProvider)
        assertSame(nano, router.chosen())

        val there = FakeFeature("local")
        assertEquals(FeatureStatus.AVAILABLE, router(nano, there).checkFeatureStatus())
    }

    @Test
    fun theBaseModelNameIsTheOneOfWhoeverWouldAnswer() = runTest {
        assertEquals("nano", router(FakeFeature("nano"), FakeFeature("local")).getBaseModelName())
        val missing = FakeFeature("nano", status = FeatureStatus.UNAVAILABLE)
        assertEquals("local", router(missing, FakeFeature("local")).getBaseModelName())
    }

    @Test
    fun afterAFallbackTheBaseModelNameAndTheReasonAreTheLocalModels() = runTest {
        val nano = FakeFeature("nano", failures = listOf(failure(ErrorCode.BACKGROUND_USE_BLOCKED)))
        val router = router(nano, FakeFeature("local"))
        assertEquals("local", router.runInference("q", null))
        val reason = router.activeProviderReason
        assertEquals("local", router.getBaseModelName())
        assertEquals(ActiveProvider.LITERT_LM, router.activeProvider)
        assertEquals(reason, router.activeProviderReason)

        assertEquals("nano", router.runInference("q", null), "The next request asks again.")
        assertEquals("nano", router.getBaseModelName())
    }

    @Test
    fun prepareFallsBackLikeARequest() = runTest {
        val nano = FakeFeature("nano", failures = listOf(failure(ErrorCode.BACKGROUND_USE_BLOCKED)))
        val local = FakeFeature("local")
        router(nano, local).prepareInferenceEngine()
        assertEquals(1, local.prepares)
    }

    @Test
    fun closeClosesBoth() {
        val nano = FakeFeature("nano")
        val local = FakeFeature("local")
        router(nano, local).close()
        assertEquals(1, nano.closes)
        assertEquals(1, local.closes)
    }

    /** What ModelProvider.GeminiNano is: Gemini Nano behind the guard, with no router. */
    @Test
    fun explicitGeminiNanoReportsItsOwnCodeWithoutRetryOrFallback() = runTest {
        for (code in FALLBACK_CODES + ErrorCode.BUSY + OTHER_CODES) {
            val nano = FakeFeature("nano", failures = listOf(failure(code)))
            assertEquals(code, codeOf { GuardedClient(nano).runInference("q", null) }, "code $code")
            assertEquals(1, nano.inferences, "code $code")
            assertEquals(0, currentTime, "code $code: no backoff")
        }
    }
}
