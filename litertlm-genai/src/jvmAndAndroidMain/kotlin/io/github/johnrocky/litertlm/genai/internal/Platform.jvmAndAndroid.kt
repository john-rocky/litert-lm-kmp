package io.github.johnrocky.litertlm.genai.internal

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val BUFFER_BYTES = 1 shl 20
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 60_000

internal actual fun freeBytes(directory: String): Long = File(directory).usableSpace

internal actual suspend fun sha256OfFile(path: String): String = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(path).use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private object UrlConnectionHttp : HttpClient {
    private fun open(url: String): HttpURLConnection = try {
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
    } catch (e: Exception) {
        throw NetworkFailure(e.message ?: e.toString(), e)
    }

    private inline fun <T> reading(block: () -> T): T = try {
        block()
    } catch (e: UnknownHostException) {
        // Its message is only the name of the host.
        throw NetworkFailure("the host was not found", e)
    } catch (e: IOException) {
        throw NetworkFailure(e.message ?: e.toString(), e)
    }

    override suspend fun getText(url: String): HttpText = withContext(Dispatchers.IO) {
        val connection = open(url)
        try {
            reading {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                HttpText(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
            }
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun getBytes(url: String, from: Long, onStatus: (Int) -> Unit, onBytes: (ByteArray, Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val connection = open(url)
            try {
                if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
                val status = reading { connection.responseCode }
                onStatus(status)
                if (status !in 200..299) return@withContext
                reading { connection.inputStream }.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        ensureActive()
                        val read = reading { input.read(buffer) }
                        if (read < 0) break
                        // Outside reading(): a failed write is not a network failure.
                        onBytes(buffer, read)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal actual fun platformHttp(): HttpClient = UrlConnectionHttp

/** Google's artifacts bring the runtime along. */
internal actual suspend fun checkRuntime() = Unit
