package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.Content as RawContent
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.Message
import io.github.johnrocky.litertlm.Role
import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.FeatureStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.LocalModel
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriberOptions
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescription
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionRequest
import io.github.johnrocky.litertlm.genai.prompt.Candidate
import io.github.johnrocky.litertlm.genai.prompt.Content
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentRequest
import io.github.johnrocky.litertlm.genai.prompt.Generation
import io.github.johnrocky.litertlm.genai.prompt.GenerativeModel
import io.github.johnrocky.litertlm.genai.prompt.ImagePart
import io.github.johnrocky.litertlm.genai.prompt.SystemInstruction
import io.github.johnrocky.litertlm.genai.prompt.TextPart
import io.github.johnrocky.litertlm.genai.prompt.content
import io.github.johnrocky.litertlm.genai.prompt.generateContentRequest
import io.github.johnrocky.litertlm.genai.proofreading.ProofreaderOptions
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingRequest
import io.github.johnrocky.litertlm.genai.rewriting.RewriterOptions
import io.github.johnrocky.litertlm.genai.rewriting.RewritingRequest
import io.github.johnrocky.litertlm.genai.summarization.SummarizationRequest
import io.github.johnrocky.litertlm.genai.summarization.Summarization
import io.github.johnrocky.litertlm.genai.summarization.Summarizer
import io.github.johnrocky.litertlm.genai.summarization.SummarizerOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

private val ARTICLE = "word ".repeat(100)

private fun pieces(vararg texts: String): Flow<Message> = flowOf(*texts.map { Message.model(it) }.toTypedArray())

private fun summaryOptions(
    type: Int = SummarizerOptions.InputType.ARTICLE,
    bullets: Int = SummarizerOptions.OutputType.THREE_BULLETS,
    truncate: Boolean = false,
) = SummarizerOptions.builder()
    .setInputType(type)
    .setOutputType(bullets)
    .setLongInputAutoTruncationEnabled(truncate)
    .build()

private fun Message.text(): String = contents.toString()

@OptIn(ExperimentalCoroutinesApi::class)
class LocalClientsTest {
    @Test
    fun anImageOnAModelWithoutAVisionPartIsNotSupportedAndTextGoesOn() = runTest {
        val runtime = FakeRuntime(
            conversationFailure = { config ->
                config.visionBackend?.let {
                    LiteRtLmException.Internal(5, "Failed to create conversation: NOT_FOUND: tf_lite_vision_encoder not found in the model.")
                }
            },
        )
        val runner = fakeRunner(runtime, backgroundScope)
        val failure = assertFailsWith<GenAiException> {
            LocalImageDescriber(runner).runInference(ImageDescriptionRequest.builder(ByteArray(4)).build(), null)
        }
        assertEquals(ErrorCode.NOT_SUPPORTED, failure.errorCode)
        assertEquals("* ok", runner.turn(Message.user("Hello.")).text)
    }

    private fun kotlinx.coroutines.test.TestScope.summarizer(
        runtime: FakeRuntime,
        options: SummarizerOptions = summaryOptions(),
    ) = Summarizer(GuardedClient(LocalSummarizer(fakeRunner(runtime, backgroundScope), options)))

    private fun kotlinx.coroutines.test.TestScope.generativeModel(runtime: FakeRuntime) =
        GenerativeModel(GuardedClient(LocalGenerativeModel(fakeRunner(runtime, backgroundScope))))

    @Test
    fun aSummaryComesInTheDocumentedFormFromTheLocalModel() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> pieces("Here you go:\n\n", "* first", " point\n- second\n", "3. third\n", "* fourth") })
        val received = StringBuilder()
        val result = summarizer(runtime).runInference(
            SummarizationRequest.builder(ARTICLE).build(),
            StreamingCallback { received.append(it) },
        )
        assertEquals("* first point\n* second\n* third", result.summary)
        assertEquals(result.summary, received.toString())
        assertEquals(ActiveProvider.LITERT_LM, result.activeProvider)
    }

    @Test
    fun theModelStopsOnceTheBulletsAreThere() = runTest {
        var stopped = false
        val runtime = FakeRuntime(
            reply = { _, _ ->
                flow {
                    emit(Message.model("* one\n* two\n* three\n"))
                    // The fourth is never asked for.
                    awaitCancellation()
                }.onCompletion { stopped = it is CancellationException || it != null }
            },
        )
        assertEquals("* one\n* two\n* three", summarizer(runtime).runInference(SummarizationRequest.builder(ARTICLE).build()).summary)
        assertTrue(stopped)
    }

    @Test
    fun theSummaryPromptSaysWhatTheOptionsSay() = runTest {
        val runtime = FakeRuntime()
        val options = SummarizerOptions.builder()
            .setInputType(SummarizerOptions.InputType.CONVERSATION)
            .setOutputType(SummarizerOptions.OutputType.TWO_BULLETS)
            .setLanguage(SummarizerOptions.Language.JAPANESE)
            .build()
        summarizer(runtime, options).runInference(SummarizationRequest.builder("Ann: hi\nBo: hi").build())
        val prompt = runtime.sentMessages.single().text()
        assertContains(prompt, "conversation")
        assertContains(prompt, "exactly two bullet points")
        assertContains(prompt, "Japanese")
        assertContains(prompt, "Ann: hi\nBo: hi")
    }

    @Test
    fun everyTurnIsANewConversationThatCloses() = runTest {
        val runtime = FakeRuntime()
        val client = summarizer(runtime)
        repeat(2) { client.runInference(SummarizationRequest.builder(ARTICLE).build()) }
        assertEquals(
            listOf("load text", "conversation", "conversation closed", "conversation", "conversation closed"),
            runtime.events.all(),
        )
        assertTrue(runtime.conversationConfigs.all { it.initialMessages.isEmpty() })
        assertEquals(TASK_SAMPLER, runtime.conversationConfigs.first().samplerConfig)
    }

    @Test
    fun aShortArticleIsRefusedBeforeLoadingAnything() = runTest {
        val runtime = FakeRuntime()
        val client = summarizer(runtime)
        assertEquals(ErrorCode.REQUEST_TOO_SMALL, codeOf { client.runInference(SummarizationRequest.builder("short").build()) })
        assertEquals(ErrorCode.REQUEST_TOO_SMALL, codeOf { client.runInference(SummarizationRequest.builder("  ").build()) })
        assertEquals(emptyList(), runtime.events.all())
    }

    @Test
    fun aShortConversationIsFineButABlankOneIsNot() = runTest {
        val client = summarizer(FakeRuntime(), summaryOptions(type = SummarizerOptions.InputType.CONVERSATION))
        client.runInference(SummarizationRequest.builder("Ann: hi").build())
        assertEquals(ErrorCode.REQUEST_TOO_SMALL, codeOf { client.runInference(SummarizationRequest.builder(" ").build()) })
    }

    private val tooLong: Flow<Message> = flow {
        throw LiteRtLmException.InputTooLong("Input token ids are too long. Exceeding the maximum number of tokens allowed: 13000 >= 4608")
    }

    @Test
    fun anInputThatIsTooLongIsReportedWithoutTruncation() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> tooLong })
        val client = summarizer(runtime)
        assertEquals(ErrorCode.REQUEST_TOO_LARGE, codeOf { client.runInference(SummarizationRequest.builder(ARTICLE).build()) })
        assertEquals(1, runtime.sentMessages.size)
    }

    @Test
    fun truncationKeepsTheStartAndRetries() = runTest {
        val long = (1..10_000).joinToString(" ") { "w$it" }
        val runtime = FakeRuntime(reply = { _, message -> if (message.text().length > 20_000) tooLong else pieces("* fits") })
        val client = summarizer(runtime, summaryOptions(truncate = true))
        assertEquals("* fits", client.runInference(SummarizationRequest.builder(long).build()).summary)
        val sent = runtime.sentMessages
        assertEquals(2, sent.size)
        val prompt = sent.last().text()
        assertContains(prompt, "w1 w2 w3 ")
        assertFalse("w9999" in prompt)
    }

    @Test
    fun truncationGivesUpAfterAFewTries() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> tooLong })
        val client = summarizer(runtime, summaryOptions(truncate = true))
        assertEquals(ErrorCode.REQUEST_TOO_LARGE, codeOf { client.runInference(SummarizationRequest.builder(ARTICLE).build()) })
        assertEquals(5, runtime.sentMessages.size)
    }

    @Test
    fun shorteningUsesWhatTheRuntimeSaid() {
        val text = "word ".repeat(2000).trim()
        val first = shortenedToFit(text, "Input token ids are too long. Exceeding the maximum number of tokens allowed: 4096 >= 2048")
        // (2048 - 512) / 4096 of it.
        assertEquals(text.length * 1536 / 4096, first.length, absoluteTolerance = 5)
        assertTrue(text.startsWith(first))
        assertTrue(first.endsWith("word"), "Cut at a word boundary: ...${first.takeLast(8)}")
        val second = shortenedToFit(
            text,
            "Not enough space left in the context window for the input: 12 tokens in the context + 8192 input tokens >= 4608 max number of tokens.",
        )
        assertEquals(text.length * 4096 / 8192, second.length, absoluteTolerance = 5)
    }

    @Test
    fun shorteningHalvesWhenTheRuntimeSaysNoNumbers() {
        val text = "word ".repeat(100).trim()
        assertEquals(text.length / 2, shortenedToFit(text, "Max number of tokens reached.").length, absoluteTolerance = 5)
        assertEquals(text.length / 2, shortenedToFit(text, null).length, absoluteTolerance = 5)
    }

    @Test
    fun shorteningAlwaysShortens() {
        assertTrue(shortenedToFit("ab", "1 >= 1000").length < 2)
        assertEquals("a", shortenedToFit("ab", null))
        assertEquals(1, shortenedToFit("nospacesatallinthistext", "9999 >= 2").length)
    }

    private fun assertEquals(expected: Int, actual: Int, absoluteTolerance: Int) {
        assertTrue(kotlin.math.abs(expected - actual) <= absoluteTolerance, "$actual is not within $absoluteTolerance of $expected")
    }

    @Test
    fun theSameClientAnswersAfterAFailure() = runTest {
        var failing = true
        val runtime = FakeRuntime(reply = { _, _ -> if (failing) tooLong else pieces("* fine") })
        val client = summarizer(runtime)
        assertEquals(ErrorCode.REQUEST_TOO_LARGE, codeOf { client.runInference(SummarizationRequest.builder(ARTICLE).build()) })
        failing = false
        assertEquals("* fine", client.runInference(SummarizationRequest.builder(ARTICLE).build()).summary)
    }

    @Test
    fun aReplyThatFillsTheWindowIsAReplyThatEndsEarly() = runTest {
        val runtime = FakeRuntime(
            reply = { _, _ ->
                flow {
                    emit(Message.model("* a whole bullet\n* half a bull"))
                    throw LiteRtLmException.InputTooLong("Max number of tokens reached, the context window is full.")
                }
            },
        )
        assertEquals("* a whole bullet\n* half a bull", summarizer(runtime).runInference(SummarizationRequest.builder(ARTICLE).build()).summary)
    }

    @Test
    fun aReplyWithNothingUsableIsAFailure() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> pieces("\n\n") })
        assertEquals(
            ErrorCode.RESPONSE_GENERATION_ERROR,
            codeOf { summarizer(runtime).runInference(SummarizationRequest.builder(ARTICLE).build()) },
        )
    }

    @Test
    fun rawFailuresBecomeTheirErrorCodesWithTheCauseKept() {
        val table = listOf(
            LiteRtLmException.ModelNotFound("/m") to ErrorCode.NOT_AVAILABLE,
            LiteRtLmException.InvalidModel("/m", "cut short") to ErrorCode.NOT_AVAILABLE,
            LiteRtLmException.InsufficientMemory(1, 2) to ErrorCode.NOT_AVAILABLE,
            LiteRtLmException.InputTooLong("too long") to ErrorCode.REQUEST_TOO_LARGE,
            LiteRtLmException.Cancelled() to ErrorCode.CANCELLED,
            LiteRtLmException.ClosedUse("Engine") to ErrorCode.CANCELLED,
            LiteRtLmException.BackendUnavailable("GPU", "no") to ErrorCode.NOT_SUPPORTED,
            LiteRtLmException.InvalidImage("bad") to ErrorCode.INVALID_INPUT_IMAGE,
            LiteRtLmException.Internal(13, "boom") to ErrorCode.UNKNOWN,
            LiteRtLmException.NotInitialized("Engine") to ErrorCode.UNKNOWN,
            LiteRtLmException.AlreadyInitialized("Engine") to ErrorCode.UNKNOWN,
        )
        for ((raw, code) in table) {
            val mapped = raw.toGenAiException()
            assertEquals(code, mapped.errorCode, raw::class.simpleName)
            assertSame(raw, mapped.cause)
            assertEquals(raw.message, mapped.message)
        }
    }

    @Test
    fun loadFailuresComeOutOfARequestWithTheirCause() = runTest {
        val runtime = FakeRuntime(initFailure = { LiteRtLmException.InsufficientMemory(1, 2) })
        val failure = assertFailsWith<GenAiException> {
            summarizer(runtime).runInference(SummarizationRequest.builder(ARTICLE).build())
        }
        assertEquals(ErrorCode.NOT_AVAILABLE, failure.errorCode)
        assertIs<LiteRtLmException.InsufficientMemory>(failure.cause)
    }

    @Test
    fun proofreadingAndRewritingGiveOneCleanSuggestion() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> pieces("Here is the text:\n\n", "\"Fixed", " text.\"") })
        val proofreader = LocalProofreader(fakeRunner(runtime, backgroundScope), ProofreaderOptions.builder().build())
        val proofed = proofreader.runInference(ProofreadingRequest.builder("Fxied txet.").build(), null)
        assertEquals(listOf("Fixed text."), proofed.results.map { it.text })
        assertEquals(ActiveProvider.LITERT_LM, proofed.activeProvider)
        val rewriter = LocalRewriter(fakeRunner(runtime, backgroundScope), RewriterOptions.builder().build())
        val rewritten = rewriter.runInference(RewritingRequest.builder("Some text.").build(), null)
        assertEquals(listOf("Fixed text."), rewritten.results.map { it.text })
        assertContains(runtime.sentMessages[0].text(), "Fxied txet.")
        assertContains(runtime.sentMessages[1].text(), "Some text.")
    }

    @Test
    fun blankTextIsTooSmallForEveryTextTask() = runTest {
        val runtime = FakeRuntime()
        val proofreader = LocalProofreader(fakeRunner(runtime, backgroundScope), ProofreaderOptions.builder().build())
        assertEquals(ErrorCode.REQUEST_TOO_SMALL, codeOf { proofreader.runInference(ProofreadingRequest.builder(" ").build(), null) })
        val rewriter = LocalRewriter(fakeRunner(runtime, backgroundScope), RewriterOptions.builder().build())
        assertEquals(ErrorCode.REQUEST_TOO_SMALL, codeOf { rewriter.runInference(RewritingRequest.builder("\n").build(), null) })
        assertEquals(emptyList(), runtime.events.all())
    }

    @Test
    fun theSixRewriteStylesAskForDifferentThings() {
        val styles = listOf(
            RewriterOptions.OutputType.ELABORATE,
            RewriterOptions.OutputType.EMOJIFY,
            RewriterOptions.OutputType.SHORTEN,
            RewriterOptions.OutputType.FRIENDLY,
            RewriterOptions.OutputType.PROFESSIONAL,
            RewriterOptions.OutputType.REPHRASE,
        )
        assertEquals(6, styles.map { rewritePrompt("x", it, RewriterOptions.Language.ENGLISH) }.toSet().size)
    }

    @Test
    fun anImageGoesWithItsBytesToAnEngineWithVision() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> pieces("A cat on a table.") })
        val describer = LocalImageDescriber(fakeRunner(runtime, backgroundScope))
        val bytes = byteArrayOf(1, 2, 3)
        val result = describer.runInference(ImageDescriptionRequest.builder(bytes).build(), null)
        assertEquals("A cat on a table.", result.description)
        assertEquals(listOf("load vision", "conversation", "conversation closed"), runtime.events.all())
        val contents = runtime.sentMessages.single().contents.contents
        assertEquals(RawContent.ImageBytes(bytes), contents.first())
    }

    @Test
    fun preparingADescriberLoadsVisionAndATextClientDoesNot() = runTest {
        val runtime = FakeRuntime()
        LocalImageDescriber(fakeRunner(runtime, backgroundScope)).prepareInferenceEngine()
        assertEquals(listOf("load vision"), runtime.events.all())
        val text = FakeRuntime()
        LocalRewriter(fakeRunner(text, backgroundScope), RewriterOptions.builder().build()).prepareInferenceEngine()
        assertEquals(listOf("load text"), text.events.all())
    }

    @Test
    fun aPromptPassesItsSettingsOn() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> pieces("Paris") })
        val response = generativeModel(runtime).generateContent(
            generateContentRequest(SystemInstruction("Be brief."), TextPart("Capital of France?")) {
                temperature = 0.5f
                topK = 7
                seed = 42
            },
        )
        val candidate = response.candidates.single()
        assertEquals("Paris", candidate.text)
        assertEquals(Candidate.FinishReason.STOP, candidate.finishReason)
        assertEquals(ActiveProvider.LITERT_LM, response.activeProvider)
        val config = runtime.conversationConfigs.single()
        assertEquals("Be brief.", config.systemInstruction.toString())
        assertEquals(7, config.samplerConfig?.topK)
        assertEquals(0.5, config.samplerConfig?.temperature)
        assertEquals(42, config.samplerConfig?.seed)
        assertEquals("Capital of France?", runtime.sentMessages.single().text())
    }

    @Test
    fun aPromptWithoutSettingsUsesMlKitsDefaultsAndANewSeed() = runTest {
        val runtime = FakeRuntime()
        val model = generativeModel(runtime)
        model.generateContent("Hello")
        model.generateContent("Hello")
        val samplers = runtime.conversationConfigs.map { assertNotNull(it.samplerConfig) }
        assertEquals(0.0, samplers[0].temperature)
        assertEquals(3, samplers[0].topK)
        assertTrue(samplers.all { it.seed != 0 }, "Seed 0 means a different one each time.")
        assertTrue(samplers[0].seed != samplers[1].seed)
    }

    @Test
    fun aPromptWithHistoryGivesTheEarlierTurnsAsHistory() = runTest {
        val runtime = FakeRuntime()
        generativeModel(runtime).generateContent(
            GenerateContentRequest.Builder(
                listOf(
                    content(Content.Role.USER) { text("Hi") },
                    content(Content.Role.MODEL) { text("Hello!") },
                    content { text("How are you?") },
                ),
            ).build(),
        )
        val earlier = runtime.conversationConfigs.single().initialMessages
        assertEquals(listOf(Role.USER, Role.MODEL), earlier.map { it.role })
        assertEquals(listOf("Hi", "Hello!"), earlier.map { it.text() })
        assertEquals("How are you?", runtime.sentMessages.single().text())
    }

    @Test
    fun aPromptWithAnImageLoadsVisionAndATextOneDoesNot() = runTest {
        val runtime = FakeRuntime()
        val model = generativeModel(runtime)
        model.generateContent(generateContentRequest(TextPart("Hi")))
        assertEquals("load text", runtime.events.all().first())
        val request = generateContentRequest(ImagePart(byteArrayOf(9)), TextPart("What is this?"))
        model.generateContent(request)
        assertTrue("load vision" in runtime.events.all())
        val parts = runtime.sentMessages.last().contents.contents
        assertEquals(listOf(RawContent.ImageBytes(byteArrayOf(9)), RawContent.Text("What is this?")), parts)
    }

    @Test
    fun manyImagesAreFineToo() = runTest {
        val runtime = FakeRuntime()
        generativeModel(runtime).generateContent(
            generateContentRequest(ImagePart(byteArrayOf(1)), ImagePart(byteArrayOf(2)), TextPart("Compare them.")),
        )
        assertEquals(3, runtime.sentMessages.single().contents.contents.size)
    }

    @Test
    fun maxOutputTokensEndsTheReplyAndSaysSo() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> pieces("a", "b", "c", "d") })
        val candidate = generativeModel(runtime).generateContent(
            generateContentRequest(TextPart("Go")) { maxOutputTokens = 2 },
        ).candidates.single()
        assertEquals("ab", candidate.text)
        assertEquals(Candidate.FinishReason.MAX_TOKENS, candidate.finishReason)
    }

    @Test
    fun aStreamGivesThePiecesThenWhyItEnded() = runTest {
        val runtime = FakeRuntime(reply = { _, _ -> pieces("Par", "is") })
        val items = generativeModel(runtime).generateContentStream("Capital of France?").toList()
        assertEquals(listOf("Par", "is", ""), items.map { it.candidates.single().text })
        assertEquals(listOf(null, null, Candidate.FinishReason.STOP), items.map { it.candidates.single().finishReason })
        assertTrue(items.all { it.activeProvider == ActiveProvider.LITERT_LM })
    }

    @Test
    fun aMalformedPromptIsRefused() = runTest {
        val model = generativeModel(FakeRuntime())
        assertEquals(
            ErrorCode.REQUEST_TOO_SMALL,
            codeOf { model.generateContent(GenerateContentRequest.Builder(emptyList<Content>()).build()) },
        )
        assertEquals(
            ErrorCode.REQUEST_PROCESSING_ERROR,
            codeOf { model.generateContent(generateContentRequest(content(Content.Role.MODEL) { text("Hi") })) },
        )
        assertEquals(
            ErrorCode.REQUEST_PROCESSING_ERROR,
            codeOf { model.generateContent(generateContentRequest(TextPart("Hi")) { topK = 0 }) },
        )
    }

    @Test
    fun theOtherPromptCallsAnswerForALocalModel() = runTest {
        val model = generativeModel(FakeRuntime())
        assertEquals(4608, model.getTokenLimit())
        assertEquals(ErrorCode.NOT_SUPPORTED, codeOf { model.countTokens(generateContentRequest(TextPart("Hi"))) })
        assertTrue(model.isSystemPromptAvailable())
        assertEquals("model", model.getBaseModelName())
    }

    @Test
    fun closingDuringAStreamFailsItWithCancelledAndReleasesTheModel() = runTest {
        val started = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(
            reply = { _, _ ->
                flow {
                    emit(Message.model("* first"))
                    started.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val client = summarizer(runtime)
        val request = async {
            try {
                client.runInference(SummarizationRequest.builder(ARTICLE).build())
                null
            } catch (e: GenAiException) {
                e
            }
        }
        started.await()
        client.close()
        assertEquals(ErrorCode.CANCELLED, assertNotNull(request.await()).errorCode)
        runtime.engines.single().closed.await()
        assertEquals("conversation closed", runtime.events.all()[2])
        assertEquals(ErrorCode.CANCELLED, codeOf { client.runInference(SummarizationRequest.builder(ARTICLE).build()) })
        client.close()
        assertEquals(1, runtime.events.all().count { it == "close text" })
    }

    @Test
    fun theStatusOfAFile() = runTest {
        val directory = temporaryDirectory()
        val path = Path(directory, "a.litertlm")
        val runtime = FakeRuntime()
        val client = LocalRewriter(
            fakeRunner(runtime, backgroundScope, path = path.toString(), model = LocalModel.file(path.toString())),
            RewriterOptions.builder().build(),
        )
        assertEquals(FeatureStatus.UNAVAILABLE, client.checkFeatureStatus())
        var failure: GenAiException? = null
        var completed = false
        val callback = object : DownloadCallback {
            override fun onDownloadFailed(e: GenAiException) {
                failure = e
            }

            override fun onDownloadCompleted() {
                completed = true
            }
        }
        client.downloadFeature(callback)
        assertEquals(ErrorCode.NOT_AVAILABLE, assertNotNull(failure).errorCode)
        assertFalse(completed)

        writeFile(path, ByteArray(4))
        failure = null
        assertEquals(FeatureStatus.AVAILABLE, client.checkFeatureStatus())
        client.downloadFeature(callback)
        assertTrue(completed, "Already there: complete at once.")
        assertNull(failure)
    }

    @Test
    fun aDirectoryIsNotAModelFile() = runTest {
        val directory = temporaryDirectory().toString()
        val client = LocalRewriter(
            fakeRunner(FakeRuntime(), backgroundScope, path = directory, model = LocalModel.file(directory)),
            RewriterOptions.builder().build(),
        )
        assertEquals(FeatureStatus.UNAVAILABLE, client.checkFeatureStatus())
    }

    @Test
    fun theBaseModelNameIsTheFileName() = runTest {
        val path = "/models/gemma-4-E2B-it.litertlm"
        val runner = fakeRunner(FakeRuntime(), backgroundScope, path = path)
        assertEquals("gemma-4-E2B-it", runner.baseModelName)
    }

    @Test
    fun explicitGeminiNanoWhereThereIsNoneSaysSo() = runTest {
        val client = Summarization.getClient(summaryOptions(), ModelProvider.GeminiNano)
        assertEquals(FeatureStatus.UNAVAILABLE, client.checkFeatureStatus())
        assertEquals(ErrorCode.NOT_AVAILABLE, codeOf { client.runInference(SummarizationRequest.builder(ARTICLE).build()) })
        assertEquals(ErrorCode.NOT_AVAILABLE, codeOf { client.prepareInferenceEngine() })
        assertEquals(ErrorCode.NOT_AVAILABLE, codeOf { client.getBaseModelName() })
        var failure: GenAiException? = null
        client.downloadFeature(
            object : DownloadCallback {
                override fun onDownloadFailed(e: GenAiException) {
                    failure = e
                }
            },
        )
        assertEquals(ErrorCode.NOT_AVAILABLE, failure?.errorCode)
        assertNull(client.activeProvider)
        val prompt = Generation.getClient(ModelProvider.GeminiNano)
        assertEquals(ErrorCode.NOT_AVAILABLE, codeOf { prompt.getTokenLimit() })
        client.close()
        prompt.close()
    }

    @Test
    fun theDefaultProviderOfTheseClientsIsAuto() = runTest {
        val cache = temporaryDirectory().toString()
        val missing = LocalModel.file("/no/such/file.litertlm")
        val client = Summarization.getClient(summaryOptions(), ModelProvider.Auto(missing, cacheDir = cache))
        assertEquals(FeatureStatus.UNAVAILABLE, client.checkFeatureStatus())
        assertEquals(ActiveProvider.LITERT_LM, client.activeProvider)
        val failure = assertFailsWith<GenAiException> { client.runInference(SummarizationRequest.builder(ARTICLE).build()) }
        assertEquals(ErrorCode.NOT_AVAILABLE, failure.errorCode)
        assertIs<LiteRtLmException.ModelNotFound>(failure.cause)
        client.close()
        ImageDescription.getClient(ImageDescriberOptions.builder().build(), ModelProvider.Auto(missing, cacheDir = cache)).close()
    }
}
