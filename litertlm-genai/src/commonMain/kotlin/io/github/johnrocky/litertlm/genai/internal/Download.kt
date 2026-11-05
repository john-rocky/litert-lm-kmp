package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.DownloadCallback
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.common.GenAiException.ErrorCode
import io.github.johnrocky.litertlm.genai.common.LocalModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Nothing came back from the server, or the connection broke. */
internal class NetworkFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class HttpText(val status: Int, val body: String)

/** The two requests a download needs, each platform with its own HTTP stack. */
internal interface HttpClient {
    /** @throws NetworkFailure */
    suspend fun getText(url: String): HttpText

    /**
     * A GET, of the part from byte [from] on if that is not 0. [onStatus] gets the status once, then [onBytes] the body
     * if the status is 2xx. What the two callbacks throw comes out of this call. Returning does not promise the whole
     * body arrived: the caller compares sizes. Once this has returned or thrown, also when cancelled, neither callback
     * is running or will run: the caller closes what they write to.
     *
     * @throws NetworkFailure
     */
    suspend fun getBytes(url: String, from: Long, onStatus: (Int) -> Unit, onBytes: (ByteArray, Int) -> Unit)
}

/** A file on the Hub and where it goes. */
internal class DownloadTarget(val model: LocalModel.HuggingFace, val destination: String) {
    val host: String = model.endpoint.substringAfter("://").substringBefore('/')
    val fileUrl: String = "${model.endpoint}/${model.repo}/resolve/${model.revision}/${model.file}"

    /** The Hub lists one directory at a time. */
    val listingUrl: String = buildString {
        append("${model.endpoint}/api/models/${model.repo}/tree/${model.revision}")
        if ('/' in model.file) append("/${model.file.substringBeforeLast('/')}")
    }
}

private class RemoteFile(val size: Long, val sha256: String?)

private sealed interface Step {
    data object Preparing : Step

    data class Running(val total: Long, val done: Long) : Step

    data object Completed : Step

    data class Failed(val error: GenAiException) : Step
}

private class RunningDownload(val steps: MutableStateFlow<Step>) {
    lateinit var job: Job
    var followers = 0
}

/** Attempts in a row that bring no new bytes before a download gives up. */
private const val MAX_STALLS = 3

/**
 * The downloads of this process: one per destination, however many clients follow it. It stops when the last one stops
 * following, and what it wrote stays, for the next attempt to go on from.
 */
internal class ModelDownloads(
    private val http: HttpClient = platformHttp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val firstRetryDelay: Duration = 1.seconds,
) {
    private val lock = SynchronizedObject()
    private val running = HashMap<String, RunningDownload>()

    /** Stopped downloads that may still be writing: the next one on the same file waits for them. */
    private val stopping = HashMap<String, Job>()

    fun isDownloading(destination: String): Boolean = synchronized(lock) { destination in running }

    /** Follows the download of [target], starting it if needed, and reports to [callback] on the caller's context. */
    suspend fun follow(target: DownloadTarget, callback: DownloadCallback) = joined(target) { download ->
        var started = false
        download.steps.first { step ->
            when (step) {
                Step.Preparing -> false
                is Step.Running -> {
                    if (!started) {
                        started = true
                        callback.onDownloadStarted(step.total)
                    }
                    callback.onDownloadProgress(step.done)
                    false
                }
                Step.Completed -> {
                    callback.onDownloadCompleted()
                    true
                }
                is Step.Failed -> {
                    callback.onDownloadFailed(step.error)
                    true
                }
            }
        }
    }

    /** Waits for the download of [target], starting it if needed. */
    suspend fun ensure(target: DownloadTarget) {
        joined(target) { download ->
            val last = download.steps.first { it is Step.Completed || it is Step.Failed }
            if (last is Step.Failed) throw last.error
        }
    }

    private suspend fun joined(target: DownloadTarget, block: suspend (RunningDownload) -> Unit) {
        val download = synchronized(lock) {
            running.getOrPut(target.destination) { start(target) }.also { it.followers++ }
        }
        download.job.start()
        try {
            block(download)
        } finally {
            synchronized(lock) {
                if (--download.followers == 0 && running[target.destination] === download) {
                    running.remove(target.destination)
                    stopping[target.destination] = download.job
                    download.job.cancel()
                }
            }
        }
    }

    /** Call with [lock] held. */
    private fun start(target: DownloadTarget): RunningDownload {
        val download = RunningDownload(MutableStateFlow(Step.Preparing))
        val previous = stopping.remove(target.destination)
        download.job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            val last = try {
                fetch(target) { download.steps.value = it }
                Step.Completed
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenAiException) {
                Step.Failed(e)
            } catch (e: Exception) {
                Step.Failed(GenAiException(cannotWrite(target, e), e, writeFailureCode(target)))
            }
            // First: whoever sees the last step and then asks for the status must not hear DOWNLOADING.
            synchronized(lock) {
                if (running[target.destination] === download) running.remove(target.destination)
            }
            download.steps.value = last
        }
        return download
    }

    private suspend fun fetch(target: DownloadTarget, publish: (Step) -> Unit) {
        // An iOS app adds the runtime itself. One that has not should hear so before waiting for gigabytes.
        checkRuntime()
        val remote = remoteFile(target)
        val destination = Path(target.destination)
        val directory = destination.parent ?: Path(".")
        SystemFileSystem.createDirectories(directory)

        // Named after the file's hash: bytes of an older version of the file are never continued.
        val partName = "${destination.name}.${remote.sha256?.take(12) ?: remote.size}.part"
        val part = Path(directory, partName)
        SystemFileSystem.list(directory)
            .filter { it.name != partName && it.name.startsWith("${destination.name}.") && it.name.endsWith(".part") }
            .forEach { SystemFileSystem.delete(it, mustExist = false) }

        var done = sizeOf(part)
        if (done > remote.size) {
            SystemFileSystem.delete(part)
            done = 0
        }
        val free = freeBytes(directory.toString())
        val missing = remote.size - done
        if (free in 0 until missing) {
            throw GenAiException(
                "The model needs ${megabytes(missing)} MB more disk space, and ${megabytes(free)} MB is free.",
                null,
                ErrorCode.NOT_ENOUGH_DISK_SPACE,
            )
        }
        publish(Step.Running(remote.size, done))

        var stalls = 0
        while (done < remote.size) {
            val before = done
            var failure: NetworkFailure? = null
            try {
                fetchRest(target, part, remote.size, done, publish)
            } catch (e: NetworkFailure) {
                failure = e
            }
            done = sizeOf(part)
            if (done >= remote.size) break
            stalls = if (done > before) 0 else stalls + 1
            if (stalls >= MAX_STALLS) throw unreachable(target, failure?.message ?: "the connection keeps ending early")
            delay(firstRetryDelay * (1 shl stalls.coerceAtMost(4)))
        }

        verify(target, part, remote)
        SystemFileSystem.atomicMove(part, destination)
    }

    private suspend fun remoteFile(target: DownloadTarget): RemoteFile {
        val response = try {
            http.getText(target.listingUrl)
        } catch (e: NetworkFailure) {
            throw unreachable(target, e.message ?: "no answer")
        }
        checkStatus(target, response.status)
        if (response.status !in 200..299) throw unreachable(target, "HTTP ${response.status}")
        val entry = try {
            Json.parseToJsonElement(response.body).jsonArray
                .map { it.jsonObject }
                .firstOrNull { it["path"]?.jsonPrimitive?.content == target.model.file }
        } catch (e: SerializationException) {
            throw GenAiException("${target.host} did not answer like the Hugging Face Hub.", e, ErrorCode.NOT_AVAILABLE)
        } catch (e: IllegalArgumentException) {
            throw GenAiException("${target.host} did not answer like the Hugging Face Hub.", e, ErrorCode.NOT_AVAILABLE)
        } ?: throw notFound(target, "not listed")
        val lfs = entry["lfs"]?.jsonObject
        val size = (lfs?.get("size") ?: entry["size"])?.jsonPrimitive?.long
            ?: throw GenAiException("${target.host} does not say how large the file is.", null, ErrorCode.NOT_AVAILABLE)
        return RemoteFile(size, lfs?.get("oid")?.jsonPrimitive?.content)
    }

    private suspend fun fetchRest(
        target: DownloadTarget,
        part: Path,
        total: Long,
        from: Long,
        publish: (Step) -> Unit,
    ) {
        var sink: Sink? = null
        var done = from
        try {
            http.getBytes(
                target.fileUrl,
                from,
                onStatus = { status ->
                    when (status) {
                        // The server goes on from the byte asked for.
                        206 -> sink = SystemFileSystem.sink(part, append = true).buffered()
                        // The server sends the whole file instead.
                        200 -> {
                            sink = SystemFileSystem.sink(part).buffered()
                            done = 0
                            publish(Step.Running(total, 0))
                        }
                        // The server has nothing from that byte on: what is on disk cannot be right.
                        416 -> SystemFileSystem.delete(part, mustExist = false)
                        else -> checkStatus(target, status).also {
                            throw NetworkFailure("HTTP $status")
                        }
                    }
                },
                onBytes = { bytes, count ->
                    sink?.write(bytes, 0, count)
                    done += count
                    publish(Step.Running(total, done))
                },
            )
        } finally {
            sink?.close()
        }
    }

    private suspend fun verify(target: DownloadTarget, part: Path, remote: RemoteFile) {
        val size = sizeOf(part)
        val actual = if (size == remote.size && remote.sha256 != null) sha256OfFile(part.toString()) else remote.sha256
        if (size != remote.size || actual != remote.sha256) {
            SystemFileSystem.delete(part, mustExist = false)
            throw GenAiException(
                "The file from ${target.host} is damaged, so it was deleted: expected ${remote.size} bytes with " +
                    "SHA-256 ${remote.sha256}, got $size bytes with $actual. Try again.",
                null,
                ErrorCode.NOT_AVAILABLE,
            )
        }
    }

    /** Throws if [status] means the file cannot be had at all. */
    private fun checkStatus(target: DownloadTarget, status: Int) {
        when (status) {
            in 200..299 -> Unit
            404 -> throw notFound(target, "HTTP 404")
            401, 403 -> throw GenAiException(
                "${target.host} gives no access to ${target.model.repo} (HTTP $status): it does not exist, or it needs " +
                    "a login. For a gated model, get the file yourself and use LocalModel.file(path).",
                null,
                ErrorCode.NOT_AVAILABLE,
            )
            in 400..499 -> throw GenAiException(
                "${target.host} refused the download of ${target.model.file} (HTTP $status).",
                null,
                ErrorCode.NOT_AVAILABLE,
            )
        }
    }

    private fun notFound(target: DownloadTarget, detail: String) = GenAiException(
        "${target.host} has no ${target.model.file} in ${target.model.repo} at ${target.model.revision} ($detail). " +
            "Check the repository, the file name and the revision.",
        null,
        ErrorCode.NOT_AVAILABLE,
    )

    private fun unreachable(target: DownloadTarget, detail: String) = GenAiException(
        "Could not download from ${target.host}: ${detail.trimEnd('.')}. Check the connection and try again: the download goes on " +
            "from where it stopped." + networkNote()?.let { " $it" }.orEmpty(),
        null,
        ErrorCode.NOT_AVAILABLE,
    )

    private fun cannotWrite(target: DownloadTarget, e: Exception) =
        "Could not write the model to ${target.destination}: ${e.message ?: e}"

    /** A failed write on a full disk reads the same as any other, so the free space tells them apart. */
    private fun writeFailureCode(target: DownloadTarget): Int {
        val directory = Path(target.destination).parent?.toString() ?: "."
        return if (freeBytes(directory) in 0 until LOW_DISK_BYTES) ErrorCode.NOT_ENOUGH_DISK_SPACE else ErrorCode.NOT_AVAILABLE
    }

    private fun sizeOf(path: Path): Long = SystemFileSystem.metadataOrNull(path)?.size?.coerceAtLeast(0) ?: 0

    private fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)

    companion object {
        private const val LOW_DISK_BYTES = 16L * 1024 * 1024

        val shared: ModelDownloads by lazy { ModelDownloads() }
    }
}
