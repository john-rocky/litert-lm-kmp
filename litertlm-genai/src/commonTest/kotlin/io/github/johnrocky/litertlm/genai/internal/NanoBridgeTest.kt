@file:OptIn(NanoAdapterApi::class)

package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.ActiveProvider
import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.FeatureStatus
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.LocalModel
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.common.StreamingCallback
import io.github.johnrocky.litertlm.genai.common.platformContext
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriberOptions
import io.github.johnrocky.litertlm.genai.imagedescription.ImageDescriptionRequest
import io.github.johnrocky.litertlm.genai.prompt.Candidate
import io.github.johnrocky.litertlm.genai.prompt.GenerateContentRequest
import io.github.johnrocky.litertlm.genai.prompt.Generation
import io.github.johnrocky.litertlm.genai.prompt.GenerationConfig
import io.github.johnrocky.litertlm.genai.prompt.ImagePart
import io.github.johnrocky.litertlm.genai.prompt.TextPart
import io.github.johnrocky.litertlm.genai.prompt.generateContentRequest
import io.github.johnrocky.litertlm.genai.prompt.modelConfig
import io.github.johnrocky.litertlm.genai.proofreading.ProofreaderOptions
import io.github.johnrocky.litertlm.genai.proofreading.ProofreadingRequest
import io.github.johnrocky.litertlm.genai.rewriting.RewriterOptions
import io.github.johnrocky.litertlm.genai.rewriting.RewritingRequest
import io.github.johnrocky.litertlm.genai.spi.NanoAdapter
import io.github.johnrocky.litertlm.genai.spi.NanoAdapterApi
import io.github.johnrocky.litertlm.genai.spi.NanoCandidate
import io.github.johnrocky.litertlm.genai.spi.NanoClient
import io.github.johnrocky.litertlm.genai.spi.NanoImage
import io.github.johnrocky.litertlm.genai.spi.NanoPromptClient
import io.github.johnrocky.litertlm.genai.spi.toNanoImage
import io.github.johnrocky.litertlm.genai.summarization.SummarizationRequest
import io.github.johnrocky.litertlm.genai.summarization.SummarizerOptions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/** One feature of a fake Gemini Nano: what it writes, and what was asked of it. */
private open class FakeNanoClient<Req, Res>(
    private val answer: Res,
    private val pieces: List<String> = emptyList(),
    private val status: Int = FeatureStatus.AVAILABLE,
) : NanoClient<Req, Res> {
    var request: Req? = null
    var downloads = 0
    var prepares = 0
    var closes = 0

    override suspend fun checkFeatureStatus(): Int = status

    override suspend fun downloadFeature(callback: DownloadCallback) {
        downloads++
        callback.onDownloadCompleted()
    }

    override suspend fun prepareInferenceEngine() {
        prepares++
    }

    override suspend fun getBaseModelName(): String = "nano-test"

    override suspend fun runInference(request: Req, callback: StreamingCallback?): Res {
        this.request = request
        pieces.forEach { callback?.onNewText(it) }
        return answer
    }

    override fun close() {
        closes++
    }
}

private class FakeNanoPrompt(answer: List<NanoCandidate>) :
    FakeNanoClient<GenerateContentRequest, List<NanoCandidate>>(answer, pieces = listOf("a", "b")),
    NanoPromptClient {
    override suspend fun getTokenLimit(): Int = 12288

    override suspend fun countTokens(request: GenerateContentRequest): Int = 7

    override suspend fun getAvailableFeatures(): List<Int> = listOf(1, 3)
}

private class FakeNanoAdapter(
    private val summary: FakeNanoClient<String, String> = FakeNanoClient("* ok"),
    private val proofread: FakeNanoClient<String, List<String>> = FakeNanoClient(listOf("first", "second")),
    private val rewritten: FakeNanoClient<String, List<String>> = FakeNanoClient(listOf("one", "two", "three")),
    private val described: FakeNanoClient<NanoImage, String> = FakeNanoClient("A cat."),
    private val prompt: FakeNanoPrompt = FakeNanoPrompt(listOf(NanoCandidate("Paris", Candidate.FinishReason.STOP))),
) : NanoAdapter {
    val asked = ArrayList<List<Any?>>()
    var config: GenerationConfig? = null

    override fun summarizer(
        context: PlatformContext,
        inputType: Int,
        outputType: Int,
        language: Int,
        longInputAutoTruncationEnabled: Boolean,
    ): NanoClient<String, String> {
        asked += listOf(context, inputType, outputType, language, longInputAutoTruncationEnabled)
        return summary
    }

    override fun proofreader(context: PlatformContext, inputType: Int, language: Int): NanoClient<String, List<String>> {
        asked += listOf(context, inputType, language)
        return proofread
    }

    override fun rewriter(context: PlatformContext, outputType: Int, language: Int): NanoClient<String, List<String>> {
        asked += listOf(context, outputType, language)
        return rewritten
    }

    override fun imageDescriber(context: PlatformContext): NanoClient<NanoImage, String> {
        asked += listOf(context)
        return described
    }

    override fun generativeModel(config: GenerationConfig): NanoPromptClient {
        this.config = config
        return prompt
    }
}

private fun options(outputType: Int) = SummarizerOptions.builder().setOutputType(outputType).build()

private fun request(text: String = "An article.") = SummarizationRequest.builder(text).build()

private val noSuchFile = LocalModel.file("/no/such/litertlm-genai-test.litertlm")

class NanoBridgeTest {
    private fun clients(adapter: NanoAdapter = FakeNanoAdapter()) = AdapterNanoClients(adapter, platformContext())

    @Test
    fun aSummaryComesBackInTheFormatOfALocalModel() = runTest {
        val written = FakeNanoClient<String, String>("Here it is:\n1. first\n- second\n* third")
        val client = clients(FakeNanoAdapter(summary = written)).summarizer(options(SummarizerOptions.OutputType.THREE_BULLETS))
        val result = client.runInference(request("The text."), null)
        assertEquals("* first\n* second\n* third", result.summary)
        assertEquals(ActiveProvider.GEMINI_NANO, result.activeProvider)
        assertEquals("The text.", written.request)
    }

    @Test
    fun aSingleBulletWithoutItsMarkerGetsIt() = runTest {
        val written = FakeNanoClient<String, String>("One sentence without a marker.")
        val client = clients(FakeNanoAdapter(summary = written)).summarizer(options(SummarizerOptions.OutputType.ONE_BULLET))
        assertEquals("* One sentence without a marker.", client.runInference(request(), null).summary)
    }

    @Test
    fun aStreamedSummaryIsTheSameAsTheResultAndStopsAtItsBullets() = runTest {
        val written = FakeNanoClient<String, String>(
            "* one\n* two\n* three\n* four",
            pieces = listOf("* on", "e\n* tw", "o\n* three", "\n* four"),
        )
        val client = clients(FakeNanoAdapter(summary = written)).summarizer(options(SummarizerOptions.OutputType.TWO_BULLETS))
        val received = ArrayList<String>()
        val result = client.runInference(request(), StreamingCallback { received += it })
        assertEquals("* one\n* two", result.summary)
        assertEquals(result.summary, received.joinToString(""))
        assertEquals(false, received.any { it.isEmpty() }, "An empty piece reached the callback: $received")
    }

    @Test
    fun whatIsLeftInTheFilterReachesTheCallback() = runTest {
        val written = FakeNanoClient<String, String>("No marker at all.", pieces = listOf("No marker", " at all."))
        val client = clients(FakeNanoAdapter(summary = written)).summarizer(options(SummarizerOptions.OutputType.ONE_BULLET))
        val received = StringBuilder()
        val result = client.runInference(request(), StreamingCallback { received.append(it) })
        assertEquals("* No marker at all.", result.summary)
        assertEquals(result.summary, received.toString())
    }

    @Test
    fun theOptionsReachTheAdapterWithTheirNumbers() {
        val adapter = FakeNanoAdapter()
        val context = platformContext()
        val clients = AdapterNanoClients(adapter, context)
        clients.summarizer(
            SummarizerOptions.builder()
                .setInputType(SummarizerOptions.InputType.CONVERSATION)
                .setOutputType(SummarizerOptions.OutputType.TWO_BULLETS)
                .setLanguage(SummarizerOptions.Language.KOREAN)
                .setLongInputAutoTruncationEnabled(true)
                .build(),
        )
        clients.proofreader(
            ProofreaderOptions.builder()
                .setInputType(ProofreaderOptions.InputType.VOICE)
                .setLanguage(ProofreaderOptions.Language.GERMAN)
                .build(),
        )
        clients.rewriter(
            RewriterOptions.builder()
                .setOutputType(RewriterOptions.OutputType.SHORTEN)
                .setLanguage(RewriterOptions.Language.SPANISH)
                .build(),
        )
        clients.imageDescriber(ImageDescriberOptions.builder().build())
        assertEquals(
            listOf(
                listOf(context, 2, 2, 2, true),
                listOf(context, 2, 3),
                listOf(context, 3, 6),
                listOf<Any?>(context),
            ),
            adapter.asked,
        )
        val config = GenerationConfig.Builder().apply { modelConfig = modelConfig { preference = 2 } }.build()
        clients.generativeModel(config)
        assertSame(config, adapter.config)
    }

    @Test
    fun suggestionsKeepTheirOrderAndSayWhoAnswered() = runTest {
        val proofread = clients().proofreader(ProofreaderOptions.builder().build())
            .runInference(ProofreadingRequest.builder("x").build(), null)
        assertEquals(listOf("first", "second"), proofread.results.map { it.text })
        assertEquals(ActiveProvider.GEMINI_NANO, proofread.activeProvider)

        val rewritten = clients().rewriter(RewriterOptions.builder().build())
            .runInference(RewritingRequest.builder("x").build(), null)
        assertEquals(listOf("one", "two", "three"), rewritten.results.map { it.text })
        assertEquals(ActiveProvider.GEMINI_NANO, rewritten.activeProvider)
    }

    @Test
    fun theBytesOfAnImageReachTheAdapter() = runTest {
        val written = FakeNanoClient<NanoImage, String>("A cat.")
        val client = clients(FakeNanoAdapter(described = written)).imageDescriber(ImageDescriberOptions.builder().build())
        val bytes = byteArrayOf(1, 2, 3)
        val result = client.runInference(ImageDescriptionRequest.builder(bytes).build(), null)
        assertEquals("A cat.", result.description)
        assertEquals(ActiveProvider.GEMINI_NANO, result.activeProvider)
        val image = written.request ?: fail("No request.")
        assertNull(image.platformImage)
        assertContentEquals(bytes, image.bytes.value)
    }

    @Test
    fun anImagePartGivesTheAdapterWhatItHas() {
        val image = ImagePart(byteArrayOf(4, 5)).toNanoImage()
        assertNull(image.platformImage)
        assertContentEquals(byteArrayOf(4, 5), image.bytes.value)
    }

    @Test
    fun thePromptClientKeepsCandidatesAndItsOwnCalls() = runTest {
        val prompt = FakeNanoPrompt(listOf(NanoCandidate("Paris", Candidate.FinishReason.MAX_TOKENS), NanoCandidate("Lyon", null)))
        val client = clients(FakeNanoAdapter(prompt = prompt)).generativeModel(GenerationConfig.DEFAULT)
        val request = generateContentRequest(TextPart("Where?"))
        val received = StringBuilder()
        val response = client.runInference(request, StreamingCallback { received.append(it) })
        assertEquals(listOf("Paris", "Lyon"), response.candidates.map { it.text })
        assertEquals(listOf(Candidate.FinishReason.MAX_TOKENS, null), response.candidates.map { it.finishReason })
        assertEquals(ActiveProvider.GEMINI_NANO, response.activeProvider)
        assertEquals("ab", received.toString())
        assertSame(request, prompt.request)
        assertEquals(12288, client.getTokenLimit())
        assertEquals(7, client.countTokens(request))
        assertEquals(listOf(1, 3), client.getAvailableFeatures())
    }

    @Test
    fun theOtherCallsGoToTheAdapter() = runTest {
        val written = FakeNanoClient<String, String>("* ok", status = FeatureStatus.DOWNLOADING)
        val client = clients(FakeNanoAdapter(summary = written)).summarizer(options(SummarizerOptions.OutputType.ONE_BULLET))
        assertEquals(ActiveProvider.GEMINI_NANO, client.activeProvider)
        assertEquals("The app chose Gemini Nano.", client.activeProviderReason)
        assertEquals(FeatureStatus.DOWNLOADING, client.checkFeatureStatus())
        assertEquals("nano-test", client.getBaseModelName())
        client.prepareInferenceEngine()
        assertEquals(1, written.prepares)
        client.downloadFeature(object : DownloadCallback {})
        assertEquals(1, written.downloads)
        client.close()
        assertEquals(1, written.closes)
    }

    @Test
    fun withTheAdapterAutoAsksGeminiNano() = runTest {
        val options = options(SummarizerOptions.OutputType.ONE_BULLET)
        val client = clientFor(
            context = platformContext(),
            provider = ModelProvider.Auto(noSuchFile),
            nano = { it.summarizer(options) },
            local = { LocalSummarizer(it, options) },
            adapter = FakeNanoAdapter(),
            noAdapterReason = NO_NANO_ARTIFACT,
        )
        assertEquals(ActiveProvider.GEMINI_NANO, client.runInference(request(), null).activeProvider)
        assertEquals(ActiveProvider.GEMINI_NANO, client.activeProvider)
        assertEquals("Gemini Nano's status is AVAILABLE.", client.activeProviderReason)
        client.close()
    }

    @Test
    fun withTheAdapterAnExplicitGeminiNanoUsesIt() = runTest {
        val options = options(SummarizerOptions.OutputType.ONE_BULLET)
        val client = clientFor(
            context = platformContext(),
            provider = ModelProvider.GeminiNano,
            nano = { it.summarizer(options) },
            local = { fail("An explicit Gemini Nano has no local model.") },
            adapter = FakeNanoAdapter(),
            noAdapterReason = NO_NANO_ARTIFACT,
        )
        assertEquals("* ok", client.runInference(request(), null).summary)
        assertEquals(ActiveProvider.GEMINI_NANO, client.activeProvider)
        client.close()
    }

    @Test
    fun withTheAdapterAnExplicitLocalModelDoesNotAskIt() = runTest {
        val local = FakeFeature("local")
        val client = clientFor<String, String>(
            context = platformContext(),
            provider = ModelProvider.LiteRtLm(noSuchFile),
            nano = { fail("An explicit local model has no Gemini Nano.") },
            local = { local },
            adapter = FakeNanoAdapter(),
            noAdapterReason = NO_NANO_ARTIFACT,
        )
        assertEquals("local", client.runInference("q", null))
        client.close()
    }

    @Test
    fun withoutTheAdapterAutoSaysWhichArtifactIsMissing() = runTest {
        val local = FakeFeature("local")
        val client = clientFor<String, String>(
            context = platformContext(),
            provider = ModelProvider.Auto(noSuchFile),
            nano = { fail("There is no adapter.") },
            local = { local },
            adapter = null,
            noAdapterReason = NO_NANO_ARTIFACT,
        )
        assertEquals("local", client.runInference("q", null))
        assertEquals(ActiveProvider.LITERT_LM, client.activeProvider)
        assertEquals(NO_NANO_ARTIFACT, client.activeProviderReason)
        client.close()
        assertEquals(1, local.closes)
    }

    @Test
    fun withoutTheAdapterAnExplicitGeminiNanoSaysTheLineToAdd() = runTest {
        val client = clientFor<String, String>(
            context = platformContext(),
            provider = ModelProvider.GeminiNano,
            nano = { fail("There is no adapter.") },
            local = { fail("An explicit Gemini Nano has no local model.") },
            adapter = null,
            noAdapterReason = NO_NANO_ARTIFACT,
        )
        assertNull(client.activeProvider)
        assertEquals(NO_NANO_ARTIFACT, client.activeProviderReason)
        assertEquals(FeatureStatus.UNAVAILABLE, client.checkFeatureStatus())
        for (call in listOf<suspend () -> Unit>({ client.runInference("q", null) }, { client.prepareInferenceEngine() }, { client.getBaseModelName() })) {
            val failure = assertFailsWith<GenAiException> { call() }
            assertEquals(ErrorCode.NOT_AVAILABLE, failure.errorCode)
            assertEquals(NO_NANO_ARTIFACT, failure.message)
        }
        val failures = ArrayList<GenAiException>()
        client.downloadFeature(
            object : DownloadCallback {
                override fun onDownloadFailed(e: GenAiException) {
                    failures += e
                }

                override fun onDownloadCompleted() = fail("Nothing to complete.")
            },
        )
        assertEquals(listOf(ErrorCode.NOT_AVAILABLE), failures.map { it.errorCode })
        client.close()
        client.close()
    }

    @Test
    fun theSentenceNamesTheDependencyAsAnAppWritesIt() {
        assertContains(NO_NANO_ARTIFACT, "implementation(\"io.github.john-rocky.litertlm:litertlm-genai-nano:")
        assertContains(NO_NANO_ARTIFACT, "androidMain.dependencies")
    }

    /** What is true here on the JVM and iOS, through the public calls: there is no adapter to find. */
    @Test
    fun thisPlatformSaysItHasNoGeminiNano() = runTest {
        assertNull(findNanoAdapter())
        assertEquals(NO_NANO_ON_THIS_PLATFORM, noNanoReason)
        Generation.getClient(ModelProvider.GeminiNano).use { model ->
            assertEquals(FeatureStatus.UNAVAILABLE, model.checkStatus())
            val failure = assertFailsWith<GenAiException> { model.getTokenLimit() }
            assertEquals(ErrorCode.NOT_AVAILABLE, failure.errorCode)
            assertEquals(NO_NANO_ON_THIS_PLATFORM, failure.message)
            assertEquals(ErrorCode.NOT_AVAILABLE, assertFailsWith<GenAiException> { model.generateContent("q") }.errorCode)
        }
    }
}
