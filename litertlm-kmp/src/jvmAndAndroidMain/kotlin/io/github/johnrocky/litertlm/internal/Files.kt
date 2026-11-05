package io.github.johnrocky.litertlm.internal

import java.io.File
import java.io.IOException

internal actual fun probeFile(path: String): FileProbe {
    val file = File(path)
    return when {
        !file.exists() -> FileProbe.Missing
        !file.isFile -> FileProbe.NotAFile
        !file.canRead() -> FileProbe.Unreadable("permission denied")
        else -> FileProbe.Regular(file.length())
    }
}

internal actual fun readFilePrefix(path: String, count: Int): ByteArray? = try {
    File(path).inputStream().use { it.readNBytes(count) }
} catch (_: IOException) {
    null
}
