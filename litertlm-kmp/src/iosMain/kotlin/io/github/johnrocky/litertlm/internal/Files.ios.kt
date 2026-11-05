@file:OptIn(ExperimentalForeignApi::class)

package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.cinterop.litertlm_kmp_available_memory
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.MAP_ANON
import platform.posix.MAP_FAILED
import platform.posix.MAP_PRIVATE
import platform.posix.PROT_NONE
import platform.posix.R_OK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.stat

internal actual val memoryCheckByDefault: Boolean = true

internal actual fun probeFile(path: String): FileProbe = memScoped {
    val info = alloc<stat>()
    if (stat(path, info.ptr) != 0) {
        val code = errno
        return@memScoped if (code == ENOENT || code == ENOTDIR) {
            FileProbe.Missing
        } else {
            FileProbe.Unreadable("stat failed with errno $code")
        }
    }
    when {
        info.st_mode.toInt() and S_IFMT != S_IFREG -> FileProbe.NotAFile
        access(path, R_OK) != 0 -> FileProbe.Unreadable("permission denied")
        else -> FileProbe.Regular(info.st_size)
    }
}

internal actual fun readFilePrefix(path: String, count: Int): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        val bytes = ByteArray(count)
        val read = bytes.usePinned { pinned -> fread(pinned.addressOf(0), 1.convert(), count.convert(), file).toInt() }
        return bytes.copyOf(read)
    } finally {
        fclose(file)
    }
}

private const val SMALLEST_BLOCK = 64L * 1024 * 1024

/** Reserves blocks with no access until [limit] is reached, then gives them back. */
internal actual fun freeAddressSpaceBytes(limit: Long): Long {
    val blocks = ArrayList<Pair<CPointer<*>, Long>>()
    var total = 0L
    var size = limit
    while (total < limit && size >= SMALLEST_BLOCK) {
        val wanted = minOf(size, limit - total)
        val block = mmap(null, wanted.convert(), PROT_NONE, MAP_PRIVATE or MAP_ANON, -1, 0)
        if (block == null || block == MAP_FAILED) {
            size /= 2
        } else {
            blocks += block to wanted
            total += wanted
        }
    }
    blocks.forEach { (block, bytes) -> munmap(block, bytes.convert()) }
    return total
}

/** os_proc_available_memory returns 0 on the simulator (measured on iOS 27.0), where no per-process limit applies. */
internal actual fun availableMemoryBytes(): Long {
    val available = litertlm_kmp_available_memory().toLong()
    return if (available > 0) available else NSProcessInfo.processInfo.physicalMemory.toLong()
}
