package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.LocalModel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

private const val SIZE = 100_000
private val CONTENT = Random(7).nextBytes(SIZE)

/** A small server: it answers from [content], and its behavior on each GET can be scripted. */
private class FakeHub(
    private val content: ByteArray = CONTENT,
    sha256: String? = null,
    private val listing: () -> HttpText = {
        val lfs = sha256?.let { ""","lfs":{"oid":"$it","size":${content.size}}""" }.orEmpty()
        HttpText(200, """[{"path":"README.md","size":1},{"path":"m.litertlm","size":${content.size}$lfs}]""")
    },
    /** Decides each GET: (the number of the GET, the byte asked for) to what to do. */
    private val script: (Int, Long) -> Answer = { _, _ -> Answer.Normal },
) : HttpClient {
    sealed interface Answer {
        data object Normal : Answer

        data class Status(val code: Int) : Answer

        data object WholeFile : Answer

        data class BreakAfter(val bytes: Int) : Answer

        data class OtherBytes(val bytes: ByteArray) : Answer

        data class WaitAfter(val bytes: Int, val reached: CompletableDeferred<Unit>) : Answer
    }

    private val lock = SynchronizedObject()
    private val gets = ArrayList<Long>()

    val requests: List<Long> get() = synchronized(lock) { gets.toList() }

    override suspend fun getText(url: String): HttpText = listing()

    override suspend fun getBytes(url: String, from: Long, onStatus: (Int) -> Unit, onBytes: (ByteArray, Int) -> Unit) {
        val number = synchronized(lock) {
            gets += from
            gets.size
        }
        suspend fun send(start: Int, end: Int) {
            var at = start
            while (at < end) {
                val piece = content.copyOfRange(at, minOf(at + 8192, end))
                onBytes(piece, piece.size)
                at += piece.size
                delay(1)
            }
        }
        when (val answer = script(number, from)) {
            Answer.Normal -> {
                onStatus(if (from > 0) 206 else 200)
                send(from.toInt(), content.size)
            }
            is Answer.Status -> onStatus(answer.code)
            Answer.WholeFile -> {
                onStatus(200)
                send(0, content.size)
            }
            is Answer.BreakAfter -> {
                onStatus(if (from > 0) 206 else 200)
                send(from.toInt(), minOf(from.toInt() + answer.bytes, content.size))
                throw NetworkFailure("connection reset")
            }
            is Answer.OtherBytes -> {
                onStatus(200)
                onBytes(answer.bytes, answer.bytes.size)
            }
            is Answer.WaitAfter -> {
                onStatus(if (from > 0) 206 else 200)
                send(from.toInt(), from.toInt() + answer.bytes)
                answer.reached.complete(Unit)
                awaitCancellation()
            }
        }
    }
}

/** Records what a callback hears. */
private class Heard : DownloadCallback {
    var started: Long? = null
    var startedTimes = 0
    val progress = ArrayList<Long>()
    var completed = 0
    val failures = ArrayList<GenAiException>()

    override fun onDownloadStarted(bytesToDownload: Long) {
        started = bytesToDownload
        startedTimes++
    }

    override fun onDownloadProgress(totalBytesDownloaded: Long) {
        progress += totalBytesDownloaded
    }

    override fun onDownloadCompleted() {
        completed++
    }

    override fun onDownloadFailed(e: GenAiException) {
        failures += e
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val directory = temporaryDirectory()
    private val destination = Path(directory, "m.litertlm")
    private val target = DownloadTarget(
        LocalModel.huggingFace("org/repo", "m.litertlm", endpoint = "https://hub.example") as LocalModel.HuggingFace,
        destination.toString(),
    )

    private fun downloads(hub: HttpClient) = ModelDownloads(hub, scope, firstRetryDelay = 1.milliseconds)

    private fun part(name: String = "m.litertlm.$SIZE.part") = Path(directory, name)

    private fun bytesOf(path: Path) = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    private fun exists(path: Path) = SystemFileSystem.exists(path)

    /** The real clock: a download runs on real threads. */
    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default, block) }

    @Test
    fun theFileAppearsWholeAndTheCallbackHearsItAll() = realTime {
        val hub = FakeHub()
        val heard = Heard()
        downloads(hub).follow(target, heard)
        assertContentEquals(CONTENT, bytesOf(destination))
        assertFalse(exists(part()), "The part file is gone.")
        assertEquals(SIZE.toLong(), heard.started)
        assertEquals(1, heard.startedTimes)
        assertEquals(1, heard.completed)
        assertEquals(emptyList(), heard.failures)
        assertEquals(heard.progress.sorted(), heard.progress, "Progress only goes up.")
        assertTrue(heard.progress.last() <= SIZE)
        assertEquals(listOf(0L), hub.requests)
    }

    @Test
    fun aDownloadResumesFromWhatIsOnDisk() = realTime {
        writeFile(part(), CONTENT.copyOf(30_000))
        val hub = FakeHub()
        val heard = Heard()
        downloads(hub).follow(target, heard)
        assertContentEquals(CONTENT, bytesOf(destination))
        assertEquals(listOf(30_000L), hub.requests)
        assertEquals(SIZE.toLong(), heard.started, "The whole size, not what is left.")
        assertTrue(heard.progress.first() >= 30_000, "Progress counts what was there: ${heard.progress.first()}")
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsOver() = realTime {
        writeFile(part(), CONTENT.copyOf(30_000))
        val hub = FakeHub(script = { _, _ -> FakeHub.Answer.WholeFile })
        downloads(hub).follow(target, Heard())
        assertContentEquals(CONTENT, bytesOf(destination))
    }

    @Test
    fun aServerWithNothingFromThatByteOnStartsOver() = realTime {
        writeFile(part(), CONTENT.copyOf(30_000))
        val hub = FakeHub(script = { number, _ -> if (number == 1) FakeHub.Answer.Status(416) else FakeHub.Answer.Normal })
        downloads(hub).follow(target, Heard())
        assertContentEquals(CONTENT, bytesOf(destination))
        assertEquals(listOf(30_000L, 0L), hub.requests)
    }

    @Test
    fun aPartFileLargerThanTheModelIsThrownAway() = realTime {
        writeFile(part(), ByteArray(SIZE + 1))
        val hub = FakeHub()
        downloads(hub).follow(target, Heard())
        assertContentEquals(CONTENT, bytesOf(destination))
        assertEquals(listOf(0L), hub.requests)
    }

    @Test
    fun aPartFileOfAnotherVersionIsThrownAway() = realTime {
        val stale = part("m.litertlm.0123456789ab.part")
        writeFile(stale, ByteArray(10))
        val unrelated = Path(directory, "other.litertlm.part")
        writeFile(unrelated, ByteArray(10))
        downloads(FakeHub()).follow(target, Heard())
        assertFalse(exists(stale))
        assertTrue(exists(unrelated))
    }

    @Test
    fun aBrokenConnectionGoesOnFromWhereItBroke() = realTime {
        val hub = FakeHub(script = { number, _ -> if (number <= 3) FakeHub.Answer.BreakAfter(20_000) else FakeHub.Answer.Normal })
        val heard = Heard()
        downloads(hub).follow(target, heard)
        assertContentEquals(CONTENT, bytesOf(destination))
        assertEquals(listOf(0L, 20_000L, 40_000L, 60_000L), hub.requests)
        assertEquals(1, heard.startedTimes)
    }

    @Test
    fun aConnectionThatBringsNothingGivesUpAndKeepsTheBytes() = realTime {
        writeFile(part(), CONTENT.copyOf(30_000))
        val hub = FakeHub(script = { _, _ -> FakeHub.Answer.BreakAfter(0) })
        val heard = Heard()
        val downloads = downloads(hub)
        downloads.follow(target, heard)
        val failure = heard.failures.single()
        assertEquals(ErrorCode.NOT_AVAILABLE, failure.errorCode)
        assertContains(failure.message.orEmpty(), "hub.example")
        assertContains(failure.message.orEmpty(), "connection reset")
        assertEquals(3, hub.requests.size)
        assertFalse(exists(destination))
        assertEquals(30_000L, SystemFileSystem.metadataOrNull(part())?.size)
        assertFalse(downloads.isDownloading(target.destination))
        assertEquals(0, heard.completed)
    }

    @Test
    fun aServerErrorIsRetriedLikeABrokenConnection() = realTime {
        val hub = FakeHub(script = { number, _ -> if (number == 1) FakeHub.Answer.Status(503) else FakeHub.Answer.Normal })
        downloads(hub).follow(target, Heard())
        assertContentEquals(CONTENT, bytesOf(destination))
    }

    @Test
    fun aFileThatComesOutWrongIsDeletedAndReported() = realTime {
        // Right size, wrong bytes.
        val sha = "0".repeat(64)
        val hub = FakeHub(sha256 = sha, script = { _, _ -> FakeHub.Answer.OtherBytes(ByteArray(SIZE)) })
        val heard = Heard()
        downloads(hub).follow(target, heard)
        val failure = heard.failures.single()
        assertEquals(ErrorCode.NOT_AVAILABLE, failure.errorCode)
        assertContains(failure.message.orEmpty(), "damaged")
        assertFalse(exists(destination))
        assertFalse(exists(part("m.litertlm.${sha.take(12)}.part")))
    }

    @Test
    fun aFileLongerThanListedIsDeletedAndReported() = realTime {
        val hub = FakeHub(script = { _, _ -> FakeHub.Answer.OtherBytes(ByteArray(SIZE + 5)) })
        val heard = Heard()
        downloads(hub).follow(target, heard)
        assertContains(heard.failures.single().message.orEmpty(), "damaged")
        assertFalse(exists(destination))
    }

    @Test
    fun theHashIsChecked() = realTime {
        val sha = run {
            val file = Path(directory, "for-hash")
            writeFile(file, CONTENT)
            sha256OfFile(file.toString())
        }
        val heard = Heard()
        downloads(FakeHub(sha256 = sha)).follow(target, heard)
        assertEquals(emptyList(), heard.failures)
        assertContentEquals(CONTENT, bytesOf(destination))
    }

    @Test
    fun theHashIsTheStandardOne() = realTime {
        val file = Path(directory, "abc")
        writeFile(file, "abc".encodeToByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256OfFile(file.toString()))
    }

    private suspend fun failureFrom(hub: HttpClient): GenAiException {
        val heard = Heard()
        downloads(hub).follow(target, heard)
        assertEquals(0, heard.completed)
        assertFalse(exists(destination))
        return heard.failures.single()
    }

    @Test
    fun anUnreachableHubNamesTheHost() = realTime {
        val failure = failureFrom(
            object : HttpClient {
                override suspend fun getText(url: String): HttpText = throw NetworkFailure("no route to host")

                override suspend fun getBytes(url: String, from: Long, onStatus: (Int) -> Unit, onBytes: (ByteArray, Int) -> Unit) =
                    error("Never reached.")
            },
        )
        assertEquals(ErrorCode.NOT_AVAILABLE, failure.errorCode)
        assertContains(failure.message.orEmpty(), "hub.example")
        assertContains(failure.message.orEmpty(), "no route to host")
    }

    @Test
    fun aMissingRepositoryOrFileSaysSo() = realTime {
        val status404 = failureFrom(FakeHub(listing = { HttpText(404, "") }))
        assertContains(status404.message.orEmpty(), "404")
        val notListed = failureFrom(FakeHub(listing = { HttpText(200, """[{"path":"other","size":1}]""") }))
        assertContains(notListed.message.orEmpty(), "m.litertlm")
        assertEquals(ErrorCode.NOT_AVAILABLE, notListed.errorCode)
    }

    @Test
    fun noAccessSaysWhatToDo() = realTime {
        // What the Hub really answers for a repository that does not exist too, without a login.
        val failure = failureFrom(FakeHub(listing = { HttpText(401, """{"error":"Invalid username or password."}""") }))
        assertContains(failure.message.orEmpty(), "401")
        assertContains(failure.message.orEmpty(), "LocalModel.file")
    }

    @Test
    fun aGatedFileStopsAtOnce() = realTime {
        val hub = FakeHub(script = { _, _ -> FakeHub.Answer.Status(403) })
        val failure = failureFrom(hub)
        assertContains(failure.message.orEmpty(), "403")
        assertEquals(1, hub.requests.size)
    }

    @Test
    fun aServerErrorAtTheListingSaysSo() = realTime {
        val failure = failureFrom(FakeHub(listing = { HttpText(503, "<html>Service Unavailable</html>") }))
        assertContains(failure.message.orEmpty(), "HTTP 503")
        assertEquals(ErrorCode.NOT_AVAILABLE, failure.errorCode)
    }

    @Test
    fun somethingThatIsNotTheHubSaysSo() = realTime {
        assertContains(failureFrom(FakeHub(listing = { HttpText(200, "<html>Log in</html>") })).message.orEmpty(), "did not answer")
        assertContains(failureFrom(FakeHub(listing = { HttpText(200, """{"a":1}""") })).message.orEmpty(), "did not answer")
        assertContains(failureFrom(FakeHub(listing = { HttpText(200, """[{"path":"m.litertlm"}]""") })).message.orEmpty(), "how large")
    }

    @Test
    fun aFullDiskSaysSoBeforeWriting() = realTime {
        val huge = 1L shl 60
        val hub = FakeHub(listing = { HttpText(200, """[{"path":"m.litertlm","size":$huge}]""") })
        val failure = failureFrom(hub)
        assertEquals(ErrorCode.NOT_ENOUGH_DISK_SPACE, failure.errorCode)
        assertContains(failure.message.orEmpty(), "disk space")
        assertEquals(emptyList(), hub.requests)
    }

    @Test
    fun aFailureAlsoComesOutOfEnsure() = realTime {
        val failure = assertFailsWith<GenAiException> { downloads(FakeHub(listing = { HttpText(404, "") })).ensure(target) }
        assertContains(failure.message.orEmpty(), "404")
    }

    @Test
    fun twoFollowersShareOneDownload() = realTime {
        val reached = CompletableDeferred<Unit>()
        var firstGet = true
        val hub = FakeHub(
            script = { _, _ ->
                if (firstGet) {
                    firstGet = false
                    FakeHub.Answer.BreakAfter(10_000)
                } else {
                    reached.complete(Unit)
                    FakeHub.Answer.Normal
                }
            },
        )
        val downloads = downloads(hub)
        val first = Heard()
        val second = Heard()
        val one = launch { downloads.follow(target, first) }
        val two = launch { downloads.follow(target, second) }
        reached.await()
        assertTrue(downloads.isDownloading(target.destination))
        one.join()
        two.join()
        assertEquals(2, hub.requests.size, "One download: the break and the rest.")
        assertEquals(1, first.completed)
        assertEquals(1, second.completed)
        assertContentEquals(CONTENT, bytesOf(destination))
        assertFalse(downloads.isDownloading(target.destination))
    }

    @Test
    fun oneFollowerLeavingDoesNotStopTheOther() = realTime {
        val reached = CompletableDeferred<Unit>()
        val going = CompletableDeferred<Unit>()
        val hub = FakeHub(
            script = { number, _ ->
                if (number == 1) {
                    FakeHub.Answer.WaitAfter(10_000, reached)
                } else {
                    going.complete(Unit)
                    FakeHub.Answer.Normal
                }
            },
        )
        val downloads = downloads(hub)
        val leaver = launch { downloads.follow(target, Heard()) }
        val stayer = launch { downloads.follow(target, Heard()) }
        reached.await()
        leaver.cancelAndJoin()
        assertTrue(downloads.isDownloading(target.destination))
        stayer.cancelAndJoin()
        assertFalse(downloads.isDownloading(target.destination), "Nobody follows it any more.")
    }

    @Test
    fun whenEveryoneLeavesItStopsAndTheNextOneResumes() = realTime {
        val reached = CompletableDeferred<Unit>()
        val hub = FakeHub(
            script = { number, _ -> if (number == 1) FakeHub.Answer.WaitAfter(30_000, reached) else FakeHub.Answer.Normal },
        )
        val downloads = downloads(hub)
        val follower = launch { downloads.follow(target, Heard()) }
        reached.await()
        follower.cancelAndJoin()
        assertFalse(downloads.isDownloading(target.destination))
        assertFalse(exists(destination))

        val heard = Heard()
        downloads.follow(target, heard)
        assertEquals(1, heard.completed)
        assertContentEquals(CONTENT, bytesOf(destination))
        assertEquals(2, hub.requests.size)
        assertEquals(30_000L, hub.requests.last(), "It went on from what the first one wrote.")
        assertTrue(heard.progress.first() >= 30_000)
    }

    @Test
    fun aFileInASubdirectoryIsListedThere() {
        val nested = DownloadTarget(
            LocalModel.huggingFace("org/repo", "sub/dir/m.litertlm", revision = "v1", endpoint = "https://h/") as LocalModel.HuggingFace,
            "/d/m.litertlm",
        )
        assertEquals("https://h/api/models/org/repo/tree/v1/sub/dir", nested.listingUrl)
        assertEquals("https://h/org/repo/resolve/v1/sub/dir/m.litertlm", nested.fileUrl)
        assertEquals("h", nested.host)
        assertEquals("https://hub.example/api/models/org/repo/tree/main", target.listingUrl)
    }

    /** A real file on a real file system moved over: what a phone needs after 2.6 GB. */
    @Test
    fun aDestinationDirectoryThatDoesNotExistYetIsMade() = realTime {
        val deep = Path(directory, "a/b/m.litertlm")
        val deepTarget = DownloadTarget(target.model, deep.toString())
        downloads(FakeHub()).follow(deepTarget, Heard())
        assertContentEquals(CONTENT, bytesOf(deep))
    }
}
