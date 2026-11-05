package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.LiteRtLmException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The texts are what the C API and the JNI layer returned on LiteRT-LM v0.19.0. */
class ErrorMappingTest {
    @Test
    fun cancelledFromTheCApi() {
        assertIs<LiteRtLmException.Cancelled>(NativeFailure(null, "CANCELLED: Task cancelled").toTurnException())
    }

    @Test
    fun cancelledFromGoogleKotlinApi() {
        assertIs<LiteRtLmException.Cancelled>(NativeFailure(1, "Task cancelled").toTurnException())
    }

    @Test
    fun inputTooLongFromTheCApi() {
        val failure = NativeFailure(
            null,
            "INVALID_ARGUMENT: Input token ids are too long. Exceeding the maximum number of tokens allowed: 10009 >= 2048",
        )
        assertIs<LiteRtLmException.InputTooLong>(failure.toTurnException())
    }

    @Test
    fun inputTooLongFromGoogleKotlinApi() {
        val failure = NativeFailure(
            3,
            "Status Code: 3. Message: Input token ids are too long. Exceeding the maximum number of tokens allowed: 10009 >= 2048",
        )
        assertIs<LiteRtLmException.InputTooLong>(failure.toTurnException())
    }

    @Test
    fun aFullContextWindowIsInputTooLong() {
        val failure = NativeFailure(null, "RESOURCE_EXHAUSTED: Max number of tokens reached, the context window is full.")
        assertIs<LiteRtLmException.InputTooLong>(failure.toTurnException())
    }

    @Test
    fun anImageThatCannotBeDecoded() {
        val failure = NativeFailure(3, "INVALID_ARGUMENT: Failed to decode image. Reason: unknown image type")
        assertIs<LiteRtLmException.InvalidImage>(failure.toTurnException())
    }

    @Test
    fun anImageWithoutAVisionBackend() {
        val failure = NativeFailure(
            null,
            "INVALID_ARGUMENT: Vision executor should not be null, please TryLoadingVisionExecutor() first.",
        )
        val exception = failure.toTurnException()
        assertIs<LiteRtLmException.BackendUnavailable>(exception)
        assertEquals("vision", exception.backend)
    }

    @Test
    fun otherTurnFailuresKeepCodeAndText() {
        val exception = NativeFailure(null, "INTERNAL: Task failed with state: 7").toTurnException()
        assertIs<LiteRtLmException.Internal>(exception)
        assertEquals(13, exception.code)
        assertEquals("INTERNAL: Task failed with state: 7", exception.message)
    }

    @Test
    fun missingFile() {
        val failure = NativeFailure(5, "NOT_FOUND: open() failed: /x.litertlm: No such file or directory")
        assertIs<LiteRtLmException.ModelNotFound>(failure.toLoadException("/x.litertlm", Backend.CPU()))
    }

    @Test
    fun missingFileFromGoogleKotlinApi() {
        val failure = NativeFailure(null, "Model file not found: /x.litertlm")
        assertIs<LiteRtLmException.ModelNotFound>(failure.toLoadException("/x.litertlm", Backend.CPU()))
    }

    @Test
    fun aBadFileStaysABadFileOnTheGpu() {
        val failure = NativeFailure(3, "INVALID_ARGUMENT: Invalid magic number or failed to read: x")
        assertIs<LiteRtLmException.InvalidModel>(failure.toLoadException("/x.litertlm", Backend.GPU()))
    }

    @Test
    fun damagedSectionsAreABadFile() {
        for (text in listOf("INTERNAL: Failed to parse LlmMetadata", "INVALID_ARGUMENT: ERROR: Failed to load model from buffer\nInvalid flatbuffer")) {
            assertIs<LiteRtLmException.InvalidModel>(NativeFailure(null, text).toLoadException("/x.litertlm", Backend.CPU()))
        }
    }

    @Test
    fun aGpuThatCannotLoadTheModelIsUnavailable() {
        val failure = NativeFailure(2, "UNKNOWN: ERROR: Failed to modify graph with delegate")
        assertIs<LiteRtLmException.BackendUnavailable>(failure.toLoadException("/x.litertlm", Backend.GPU()))
    }

    @Test
    fun otherLoadFailuresOnTheCpuAreInternal() {
        val failure = NativeFailure(13, "INTERNAL: something else")
        assertIs<LiteRtLmException.Internal>(failure.toLoadException("/x.litertlm", Backend.CPU()))
    }
}
