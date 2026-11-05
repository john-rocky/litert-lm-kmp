package io.github.johnrocky.litertlm

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

internal expect fun environmentVariable(name: String): String?

/**
 * Runs against a real model when LITERTLM_TEST_MODEL names one, and is skipped otherwise. The full check of every
 * failure path is DeviceCheck in :sample-shared.
 */
class ModelTest {
    @Test
    fun chatWithARealModel() {
        val path = environmentVariable("LITERTLM_TEST_MODEL")
        if (path.isNullOrEmpty()) {
            println("ModelTest skipped: LITERTLM_TEST_MODEL is not set.")
            return
        }
        // runBlocking, not runTest: a real model takes longer than runTest's virtual-time limit allows.
        runBlocking {
            Engine(EngineConfig(modelPath = path, maxNumTokens = 2048, cacheDir = environmentVariable("LITERTLM_TEST_CACHE")))
                .use { engine ->
                    engine.initialize()
                    engine.createConversation().use { conversation ->
                        assertTrue(conversation.sendMessage("Reply with one word: ready.").toString().isNotEmpty())
                        assertTrue(conversation.stream("Reply with one word: done.").toList().isNotEmpty())
                    }
                }
        }
    }
}
