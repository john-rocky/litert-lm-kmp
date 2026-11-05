@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, NanoAdapterApi::class)

package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.spi.NanoAdapter
import io.github.johnrocky.litertlm.genai.spi.NanoAdapterApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseCancel
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataTaskWithURL
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.posix.memcpy

private const val BUFFER_BYTES = 1 shl 20

internal actual fun findNanoAdapter(): NanoAdapter? = null

internal actual val noNanoReason: String = NO_NANO_ON_THIS_PLATFORM

private fun directory(which: ULong): String =
    NSSearchPathForDirectoriesInDomains(which, NSUserDomainMask, true).first() as String

private val modelsDirectory: String by lazy {
    val path = "${directory(NSApplicationSupportDirectory)}/litertlm-genai/models"
    NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    // Application Support goes into backups unless told not to, and a model can be fetched again.
    NSURL.fileURLWithPath(path).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
    path
}

internal actual fun defaultModelsDir(context: PlatformContext): String = modelsDirectory

internal actual fun defaultCacheDir(context: PlatformContext): String = "${directory(NSCachesDirectory)}/litertlm-genai"

internal actual fun freeBytes(directory: String): Long {
    val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(directory, null)
    return (attributes?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: -1
}

internal actual suspend fun sha256OfFile(path: String): String = withContext(Dispatchers.IO) {
    memScoped {
        val context = alloc<CC_SHA256_CTX>()
        CC_SHA256_Init(context.ptr)
        val buffer = ByteArray(BUFFER_BYTES)
        SystemFileSystem.source(Path(path)).buffered().use { source ->
            while (true) {
                ensureActive()
                val read = source.readAtMostTo(buffer)
                if (read <= 0) break
                buffer.usePinned { CC_SHA256_Update(context.ptr, it.addressOf(0), read.convert()) }
            }
        }
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { CC_SHA256_Final(it.addressOf(0), context.ptr) }
        digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private fun urlOf(url: String): NSURL = NSURL.URLWithString(url) ?: throw NetworkFailure("Not a URL: $url")

private fun NSError.toFailure() = NetworkFailure(localizedDescription)

/** Writes on the session's own serial queue, which keeps up with what arrives only as fast as the disk does. */
private class BodyDelegate(
    private val onStatus: (Int) -> Unit,
    private val onBytes: (ByteArray, Int) -> Unit,
    private val onFinished: (Throwable?) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private var failure: Throwable? = null

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        try {
            onStatus((didReceiveResponse as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0)
            completionHandler(NSURLSessionResponseAllow)
        } catch (t: Throwable) {
            failure = t
            completionHandler(NSURLSessionResponseCancel)
        }
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (failure != null) return
        try {
            val bytes = didReceiveData.toByteArray()
            onBytes(bytes, bytes.size)
        } catch (t: Throwable) {
            failure = t
            dataTask.cancel()
        }
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        session.finishTasksAndInvalidate()
        onFinished(failure ?: didCompleteWithError?.toFailure())
    }
}

private object SessionHttp : HttpClient {
    override suspend fun getText(url: String): HttpText = suspendCancellableCoroutine { continuation ->
        val task = NSURLSession.sharedSession.dataTaskWithURL(urlOf(url)) { data, response, error ->
            if (!continuation.isActive) return@dataTaskWithURL
            if (error != null) {
                continuation.resumeWithException(error.toFailure())
            } else {
                val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                continuation.resume(HttpText(status, data?.toByteArray()?.decodeToString().orEmpty()))
            }
        }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    override suspend fun getBytes(url: String, from: Long, onStatus: (Int) -> Unit, onBytes: (ByteArray, Int) -> Unit) {
        val finished = CompletableDeferred<Unit>()
        val request = NSMutableURLRequest.requestWithURL(urlOf(url))
        if (from > 0) request.setValue("bytes=$from-", forHTTPHeaderField = "Range")
        val delegate = BodyDelegate(onStatus, onBytes) { failure ->
            if (failure == null) finished.complete(Unit) else finished.completeExceptionally(failure)
        }
        val configuration = NSURLSessionConfiguration.defaultSessionConfiguration
        // A model does not belong in the URL cache.
        configuration.setURLCache(null)
        configuration.setRequestCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        val session = NSURLSession.sessionWithConfiguration(configuration, delegate, null)
        val task = session.dataTaskWithRequest(request)
        task.resume()
        try {
            finished.await()
        } catch (e: CancellationException) {
            // A callback can be running on the session's queue right now, and the caller closes what it writes to as soon
            // as this returns. The task's end is the last thing the session calls.
            task.cancel()
            withContext(NonCancellable) { finished.join() }
            throw e
        }
    }
}

internal actual fun platformHttp(): HttpClient = SessionHttp

/** An engine with no model file says what it says about the file only if the runtime is there. */
internal actual suspend fun checkRuntime() {
    val probe = Engine(EngineConfig(modelPath = ""))
    try {
        probe.initialize()
    } catch (e: LiteRtLmException.ModelNotFound) {
        // The runtime is there.
    } catch (e: LiteRtLmException) {
        throw e.toGenAiException()
    } finally {
        probe.close()
    }
}

internal actual fun networkNote(): String? = null
