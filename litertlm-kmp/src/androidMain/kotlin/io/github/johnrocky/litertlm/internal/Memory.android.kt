package io.github.johnrocky.litertlm.internal

import java.io.File
import java.io.IOException

internal actual val memoryCheckByDefault: Boolean = true

private val MEM_AVAILABLE = Regex("^MemAvailable:\\s+(\\d+) kB")

internal actual fun freeAddressSpaceBytes(limit: Long): Long = limit

internal actual fun availableMemoryBytes(): Long {
    val kilobytes = try {
        File("/proc/meminfo").useLines { lines ->
            lines.firstNotNullOfOrNull { MEM_AVAILABLE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        }
    } catch (_: IOException) {
        null
    }
    return kilobytes?.times(1024) ?: Runtime.getRuntime().maxMemory()
}
