package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.genai.common.GenAiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private fun config(path: String = "/m.litertlm") = EngineConfig(modelPath = path, visionBackend = Backend.CPU())

private val NO_VISION_PART =
    LiteRtLmException.Internal(5, "Failed to create conversation: NOT_FOUND: tf_lite_vision_encoder not found in the model.")

@OptIn(ExperimentalCoroutinesApi::class)
class SharedEngineTest {
    private fun TestScope.pool(runtime: FakeRuntime) = EnginePool(runtime::factory, backgroundScope)

    @Test
    fun aModelWithoutAVisionPartFailsTheImageAndTextStillWorks() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        val failure = assertFailsWith<GenAiException> { engine.withEngine(needsVision = true) { throw NO_VISION_PART } }
        assertEquals(GenAiException.ErrorCode.NOT_SUPPORTED, failure.errorCode)
        engine.withEngine(needsVision = false) { assertNull(it.engineConfig.visionBackend) }
        assertEquals(listOf("load vision", "close vision", "load text"), runtime.events.all())
    }

    @Test
    fun theNextImageOnSuchAModelFailsWithoutLoadingAgain() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        assertFailsWith<GenAiException> { engine.withEngine(needsVision = true) { throw NO_VISION_PART } }
        val failure = assertFailsWith<GenAiException> { engine.withEngine(needsVision = true) {} }
        assertEquals(GenAiException.ErrorCode.NOT_SUPPORTED, failure.errorCode)
        assertEquals(listOf("load vision", "close vision"), runtime.events.all())
    }

    @Test
    fun aTextTurnOnAnEngineThatWasPreparedForImagesLoadsAgainAndGoesOn() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        engine.withEngine(needsVision = true) {}
        var tries = 0
        engine.withEngine(needsVision = false) {
            if (tries++ == 0) throw NO_VISION_PART
            assertNull(it.engineConfig.visionBackend)
        }
        assertEquals(2, tries)
        assertEquals(listOf("load vision", "close vision", "load text"), runtime.events.all())
    }

    @Test
    fun anotherInternalFailureLeavesTheEngineLoaded() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        assertFailsWith<LiteRtLmException.Internal> {
            engine.withEngine(needsVision = true) { throw LiteRtLmException.Internal(13, "Something else.") }
        }
        engine.withEngine(needsVision = true) {}
        assertEquals(listOf("load vision"), runtime.events.all())
    }

    @Test
    fun clientsThatComeAfterTheLastCloseLookAtTheFileAgain() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val engine = pool.acquire(config())
        assertFailsWith<GenAiException> { engine.withEngine(needsVision = true) { throw NO_VISION_PART } }
        engine.release()
        runCurrent()
        val again = pool.acquire(config())
        again.withEngine(needsVision = true) {}
        assertEquals(listOf("load vision", "close vision", "load vision"), runtime.events.all())
    }

    @Test
    fun clientsOfTheSameSettingsShareOneLoad() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val first = pool.acquire(config())
        val second = pool.acquire(config())
        assertSame(first, second)
        first.withEngine(needsVision = false) {}
        second.withEngine(needsVision = false) {}
        assertEquals(listOf("load text"), runtime.events.all())
    }

    @Test
    fun otherSettingsAreAnotherEngine() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        assertNotSame(pool.acquire(config()), pool.acquire(config("/other.litertlm")))
        assertNotSame(pool.acquire(config()), pool.acquire(config().copy(maxNumTokens = 1)))
        assertNotSame(pool.acquire(config()), pool.acquire(config().copy(backend = Backend.GPU())))
    }

    @Test
    fun nothingLoadsBeforeTheFirstTurn() = runTest {
        val runtime = FakeRuntime()
        pool(runtime).acquire(config())
        assertEquals(emptyList(), runtime.events.all())
    }

    @Test
    fun textTurnsLoadWithoutVision() = runTest {
        val runtime = FakeRuntime()
        pool(runtime).acquire(config()).withEngine(needsVision = false) {
            assertNull(it.engineConfig.visionBackend)
        }
    }

    @Test
    fun theFirstImageReplacesTheEngineAndTheOldOneClosesFirst() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        engine.withEngine(needsVision = false) {}
        engine.withEngine(needsVision = true) { assertEquals(Backend.CPU(), it.engineConfig.visionBackend) }
        assertEquals(listOf("load text", "close text", "load vision"), runtime.events.all())
    }

    @Test
    fun anEngineWithVisionStaysForTextToo() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        engine.withEngine(needsVision = true) {}
        engine.withEngine(needsVision = false) { assertEquals(Backend.CPU(), it.engineConfig.visionBackend) }
        engine.withEngine(needsVision = true) {}
        assertEquals(listOf("load vision"), runtime.events.all())
    }

    @Test
    fun turnsRunOneAtATimeInOrder() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        val proceed = CompletableDeferred<Unit>()
        val order = ArrayList<String>()
        val first = async {
            engine.withEngine(needsVision = false) {
                order += "first starts"
                proceed.await()
                order += "first ends"
            }
        }
        runCurrent()
        val second = async { engine.withEngine(needsVision = false) { order += "second" } }
        runCurrent()
        assertEquals(listOf("first starts"), order)
        proceed.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first starts", "first ends", "second"), order)
    }

    @Test
    fun theModelClosesOnlyWithItsLastClient() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        engine.retain()
        engine.withEngine(needsVision = false) {}
        engine.release()
        advanceUntilIdle()
        assertFalse(runtime.engines.single().closed.isCompleted)
        engine.withEngine(needsVision = false) {}
        assertEquals(listOf("load text"), runtime.events.all())
        engine.release()
        runtime.engines.single().closed.await()
        assertEquals(listOf("load text", "close text"), runtime.events.all())
    }

    @Test
    fun theLastCloseWaitsForTheRunningTurn() = runTest {
        val runtime = FakeRuntime()
        val engine = pool(runtime).acquire(config())
        val proceed = CompletableDeferred<Unit>()
        val turn = async { engine.withEngine(needsVision = false) { proceed.await() } }
        runCurrent()
        engine.release()
        advanceUntilIdle()
        assertFalse(runtime.engines.single().closed.isCompleted)
        proceed.complete(Unit)
        turn.await()
        runtime.engines.single().closed.await()
    }

    @Test
    fun aNewClientRacingTheCloseKeepsTheModel() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val engine = pool.acquire(config())
        val proceed = CompletableDeferred<Unit>()
        val turn = async { engine.withEngine(needsVision = false) { proceed.await() } }
        runCurrent()
        // The closer now waits for the turn...
        engine.release()
        // ...and meanwhile someone new comes.
        assertSame(engine, pool.acquire(config()))
        proceed.complete(Unit)
        turn.await()
        advanceUntilIdle()
        assertFalse(runtime.engines.single().closed.isCompleted)
        engine.withEngine(needsVision = false) {}
        assertEquals(listOf("load text"), runtime.events.all())
    }

    @Test
    fun aClientAfterTheCloseLoadsAgain() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val engine = pool.acquire(config())
        engine.withEngine(needsVision = false) {}
        engine.release()
        runtime.engines.single().closed.await()
        val again = pool.acquire(config())
        again.withEngine(needsVision = false) {}
        assertEquals(listOf("load text", "close text", "load text"), runtime.events.all())
    }

    @Test
    fun aLoadWaitsForTheCloseOfAnotherModel() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val first = pool.acquire(config("/a.litertlm"))
        val proceed = CompletableDeferred<Unit>()
        val turn = async { first.withEngine(needsVision = false) { proceed.await() } }
        runCurrent()
        first.release()
        val second = async { pool.acquire(config("/b.litertlm")).withEngine(needsVision = false) {} }
        runCurrent()
        assertEquals(listOf("load text"), runtime.events.all())
        proceed.complete(Unit)
        turn.await()
        second.await()
        assertEquals(listOf("load text", "close text", "load text"), runtime.events.all())
    }

    @Test
    fun anotherModelTakesTheRoomWhileTheClientOfTheFirstStaysOpen() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val first = pool.acquire(config("/a.litertlm"))
        val second = pool.acquire(config("/b.litertlm"))
        first.withEngine(needsVision = false) {}
        second.withEngine(needsVision = false) {}
        assertEquals(listOf("load text", "close text", "load text"), runtime.events.all())
        // The first client is still open, and its next turn loads its model again.
        first.withEngine(needsVision = false) { assertEquals("/a.litertlm", it.engineConfig.modelPath) }
        assertEquals(listOf("load text", "close text", "load text", "close text", "load text"), runtime.events.all())
    }

    @Test
    fun otherSettingsOfTheSameFileTakeTheRoomToo() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val first = pool.acquire(config())
        val second = pool.acquire(config().copy(backend = Backend.GPU()))
        first.withEngine(needsVision = true) {}
        second.withEngine(needsVision = false) {}
        assertEquals(listOf("load vision", "close vision", "load text"), runtime.events.all())
    }

    @Test
    fun aTurnOfAnotherModelWaitsForTheRunningTurn() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val first = pool.acquire(config("/a.litertlm"))
        val second = pool.acquire(config("/b.litertlm"))
        val proceed = CompletableDeferred<Unit>()
        val turn = async { first.withEngine(needsVision = false) { proceed.await() } }
        runCurrent()
        val waiting = async { second.withEngine(needsVision = false) {} }
        runCurrent()
        assertEquals(listOf("load text"), runtime.events.all())
        proceed.complete(Unit)
        turn.await()
        waiting.await()
        assertEquals(listOf("load text", "close text", "load text"), runtime.events.all())
    }

    @Test
    fun aCancelWhileWaitingForTheOtherModelsTurnLeavesItLoaded() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val first = pool.acquire(config("/a.litertlm"))
        val second = pool.acquire(config("/b.litertlm"))
        val proceed = CompletableDeferred<Unit>()
        val turn = async { first.withEngine(needsVision = false) { proceed.await() } }
        runCurrent()
        val waiting = launch { second.withEngine(needsVision = false) {} }
        runCurrent()
        waiting.cancelAndJoin()
        proceed.complete(Unit)
        turn.await()
        first.withEngine(needsVision = false) {}
        assertEquals(listOf("load text"), runtime.events.all())
    }

    @Test
    fun aTurnCancelledDuringTheOtherModelsCloseLeavesNoClosedEngineBehind() = runTest {
        val closing = CompletableDeferred<Unit>()
        val runtime = FakeRuntime()
        val pool = EnginePool(
            factory = { engineConfig ->
                val engine = runtime.factory(engineConfig)
                if (engineConfig.modelPath != "/a.litertlm") engine else object : Engine by engine {
                    override fun close() {
                        closing.complete(Unit)
                        engine.close()
                    }
                }
            },
            scope = backgroundScope,
        )
        val first = pool.acquire(config("/a.litertlm"))
        val second = pool.acquire(config("/b.litertlm"))
        first.withEngine(needsVision = false) {}
        val turn = launch { second.withEngine(needsVision = false) {} }
        closing.await()
        turn.cancelAndJoin()
        // Whether the second turn loaded or was cancelled first, the first model's next turn gets an engine that is open.
        first.withEngine(needsVision = false) { assertFalse(runtime.engines.last().closed.isCompleted) }
        assertEquals("/a.litertlm", runtime.engines.last().engineConfig.modelPath)
    }

    @Test
    fun theLastCloseOfAModelThatLostTheRoomClosesNothing() = runTest {
        val runtime = FakeRuntime()
        val pool = pool(runtime)
        val first = pool.acquire(config("/a.litertlm"))
        val second = pool.acquire(config("/b.litertlm"))
        first.withEngine(needsVision = false) {}
        second.withEngine(needsVision = false) {}
        first.release()
        advanceUntilIdle()
        assertEquals(listOf("load text", "close text", "load text"), runtime.events.all())
        assertFalse(runtime.engines.last().closed.isCompleted)
    }

    @Test
    fun aFailedLoadOfAnotherModelLeavesTheRoomEmptyAndTheFirstLoadsAgain() = runTest {
        val runtime = FakeRuntime(
            initFailure = { if (it.modelPath == "/b.litertlm") LiteRtLmException.InsufficientMemory(1, 2) else null },
        )
        val pool = pool(runtime)
        val first = pool.acquire(config("/a.litertlm"))
        val second = pool.acquire(config("/b.litertlm"))
        first.withEngine(needsVision = false) {}
        assertFailsWith<LiteRtLmException.InsufficientMemory> { second.withEngine(needsVision = false) {} }
        first.withEngine(needsVision = false) {}
        assertEquals(
            listOf("load text", "close text", "load text", "close text", "load text"),
            runtime.events.all(),
        )
    }

    @Test
    fun aFailedLoadClosesWhatItMadeAndTheNextTurnTriesAgain() = runTest {
        var failing = true
        val runtime = FakeRuntime(initFailure = { if (failing) LiteRtLmException.InsufficientMemory(1, 2) else null })
        val engine = pool(runtime).acquire(config())
        assertFailsWith<LiteRtLmException.InsufficientMemory> { engine.withEngine(needsVision = false) {} }
        assertEquals(listOf("load text", "close text"), runtime.events.all())
        failing = false
        engine.withEngine(needsVision = false) {}
        assertEquals(listOf("load text", "close text", "load text"), runtime.events.all())
    }
}
