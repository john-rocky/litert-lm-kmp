package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.LiteRtLmException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** What is at a path. */
internal sealed class FileProbe {
    data object Missing : FileProbe()

    data object NotAFile : FileProbe()

    data class Unreadable(val reason: String) : FileProbe()

    data class Regular(val size: Long) : FileProbe()
}

internal expect fun probeFile(path: String): FileProbe

/** Up to [count] bytes from the start of the file, or `null` if it cannot be read. */
internal expect fun readFilePrefix(path: String, count: Int): ByteArray?

/**
 * How much memory this process can still use. Android: MemAvailable. iOS: os_proc_available_memory, or the physical
 * memory where that returns 0 (the simulator). JVM: the free physical memory of OperatingSystemMXBean.
 */
internal expect fun availableMemoryBytes(): Long

/**
 * How much address space this process can still map, counted up to [limit], so that nothing else is short of it even for a
 * moment. Holes under 64 MiB do not count. Only iOS is limited: elsewhere this returns [limit].
 */
internal expect fun freeAddressSpaceBytes(limit: Long): Long

/**
 * Whether a config that sets no [io.github.johnrocky.litertlm.EngineConfig.minAvailableMemoryBytes] is checked:
 * phones kill a process that runs out; a desktop pages, and its free figure leaves out the reclaimable file cache.
 */
internal expect val memoryCheckByDefault: Boolean

private const val MAGIC = "LITERTLM"
private const val FILE_PREFIX_SIZE = 32
private const val HEADER_END_OFFSET_POSITION = 24
private const val MAX_HEADER_SIZE = 16 * 1024 * 1024
private const val ADDRESS_SPACE_RETRIES = 10
private val ADDRESS_SPACE_PAUSE = 200.milliseconds

/**
 * Throws if the runtime should not be handed this file, and returns its size.
 *
 * The native loader does not verify the metadata at the start of the file: a garbled one is a SIGSEGV that nothing
 * can catch (measured on C API 1.0.0, LiteRT-LM v0.19.0). A file cut short reaches the loader as a misleading
 * "not found in the model". Both are answered here. The bytes inside the sections are the loader's to judge: it
 * reported random damage there as an error, without crashing.
 */
internal fun preflightModelFile(path: String): Long {
    val size = when (val probe = probeFile(path)) {
        FileProbe.Missing -> throw LiteRtLmException.ModelNotFound(path)
        FileProbe.NotAFile -> throw LiteRtLmException.InvalidModel(path, "not a regular file")
        is FileProbe.Unreadable -> throw LiteRtLmException.InvalidModel(path, probe.reason)
        is FileProbe.Regular -> probe.size
    }
    val prefix = readFilePrefix(path, FILE_PREFIX_SIZE)
        ?: throw LiteRtLmException.InvalidModel(path, "cannot be read")
    if (prefix.size < FILE_PREFIX_SIZE || prefix.decodeToString(0, MAGIC.length) != MAGIC) {
        throw LiteRtLmException.InvalidModel(path, "no LITERTLM header")
    }
    val headerEnd = prefix.readLong(HEADER_END_OFFSET_POSITION)
    if (headerEnd <= FILE_PREFIX_SIZE || headerEnd > size || headerEnd > MAX_HEADER_SIZE) {
        throw LiteRtLmException.InvalidModel(path, "bad header size $headerEnd for a file of $size bytes")
    }
    val header = readFilePrefix(path, headerEnd.toInt())
        ?: throw LiteRtLmException.InvalidModel(path, "cannot be read")
    if (header.size.toLong() != headerEnd) throw LiteRtLmException.InvalidModel(path, "cut short")
    val sectionsEnd = readSectionsEnd(header)
        ?: throw LiteRtLmException.InvalidModel(path, "garbled header")
    if (sectionsEnd > size) {
        throw LiteRtLmException.InvalidModel(path, "cut short: $size bytes, the sections need $sectionsEnd")
    }
    return size
}

/**
 * Throws [LiteRtLmException.InsufficientMemory] unless [required] (or the default for this file) is available, and the
 * address space for the file too. A [required] of 0 turns both off.
 */
internal fun checkAvailableMemory(required: Long?, modelFileSize: Long, loadsVision: Boolean = false) {
    if (required == 0L) return
    requiredMemory(required, modelFileSize, loadsVision)?.let { needed ->
        val available = availableMemoryBytes()
        if (available < needed) throw LiteRtLmException.InsufficientMemory(available, needed)
    }
    checkAddressSpace(requiredAddressSpace(modelFileSize, loadsVision))
}

/**
 * Throws unless [needed] bytes of address space are free. On the way there it waits a little: a task client's close does not
 * block, and on an iPhone 18 Pro its model was gone 7 to 34 ms later, in 8 runs of 9.
 */
internal fun checkAddressSpace(
    needed: Long,
    free: (Long) -> Long = ::freeAddressSpaceBytes,
    pause: () -> Unit = { runBlocking { delay(ADDRESS_SPACE_PAUSE) } },
) {
    var left = free(needed)
    repeat(ADDRESS_SPACE_RETRIES) {
        if (left >= needed) return
        pause()
        left = free(needed)
    }
    if (left < needed) throw LiteRtLmException.InsufficientMemory.ofAddressSpace(left, needed)
}

/**
 * A floor, under which a load does not fit. A load that does not fit does not fail cleanly: the runtime reports it as a
 * missing part of the model, leaves part of it mapped, and has ended the process with std::bad_alloc. A load maps the file
 * and its caches: with Gemma 4 E2B, 2,468 MiB, on an iPhone 18 Pro, 3,514 to 4,250 MiB for text and 5,192 to 5,305 MiB with
 * images. The floor stays under those, so that it refuses no load that fits, and a load just above it can still fail.
 */
internal fun requiredAddressSpace(modelFileSize: Long, loadsVision: Boolean): Long =
    if (loadsVision) modelFileSize * 2 else modelFileSize / 5 * 7

/** What the app set, or else the default where this platform checks. `null` is no check. */
internal fun requiredMemory(
    required: Long?,
    modelFileSize: Long,
    loadsVision: Boolean,
    checkByDefault: Boolean = memoryCheckByDefault,
): Long? = required ?: if (checkByDefault) defaultRequiredMemory(modelFileSize, loadsVision) else null

/**
 * Half the file, or three quarters of it when images can come: rough lines, not a promise either way. An image takes more
 * than text: with Gemma 4 E2B on an iPhone 18 Pro and an empty cache, up to 1,634 MiB against 523 MiB on the CPU. The app
 * goes on using memory after this check, and the system ends a process that runs out with nothing to catch. What a model
 * needs depends on the device, the backend and the context size, so an app that knows better sets
 * EngineConfig.minAvailableMemoryBytes.
 */
internal fun defaultRequiredMemory(modelFileSize: Long, loadsVision: Boolean): Long =
    if (loadsVision) modelFileSize / 4 * 3 else modelFileSize / 2

/**
 * Walks the FlatBuffer that starts at byte 32 (schema/core/litertlm_header_schema.fbs: LiteRTLMMetaData →
 * section_metadata → objects → end_offset) and returns the largest end_offset. Every read is checked against the
 * buffer, so a garbled header gives `null` here instead of a wild read in the native loader.
 */
internal fun readSectionsEnd(header: ByteArray): Long? {
    val reader = FlatReader(header)
    val root = reader.rootTable(FILE_PREFIX_SIZE) ?: return null
    val sectionMetadata = reader.tableField(root, 1) ?: return null
    val objects = reader.vectorField(sectionMetadata, 0) ?: return null
    if (objects.count < 0) return null
    var end = 0L
    for (i in 0 until objects.count) {
        val section = reader.vectorTable(objects, i) ?: return null
        val begin = reader.longField(section, 1) ?: return null
        val sectionEnd = reader.longField(section, 2) ?: return null
        if (begin < 0 || sectionEnd < begin) return null
        if (sectionEnd > end) end = sectionEnd
    }
    return end
}

private class FlatVector(val start: Int, val count: Int)

/** The reads of a FlatBuffer that [readSectionsEnd] needs, little-endian and bounds-checked. */
private class FlatReader(private val bytes: ByteArray) {
    private fun u16(pos: Int): Int? {
        if (pos < 0 || pos + 2 > bytes.size) return null
        return (bytes[pos].toInt() and 0xff) or ((bytes[pos + 1].toInt() and 0xff) shl 8)
    }

    private fun i32(pos: Int): Int? {
        if (pos < 0 || pos + 4 > bytes.size) return null
        var value = 0
        for (i in 3 downTo 0) value = (value shl 8) or (bytes[pos + i].toInt() and 0xff)
        return value
    }

    /** The position of a table given a uoffset stored at [pos], or `null` if it points outside the buffer. */
    private fun indirect(pos: Int): Int? {
        val offset = i32(pos) ?: return null
        if (offset <= 0 || offset > bytes.size - pos) return null
        return pos + offset
    }

    fun rootTable(base: Int): Int? = indirect(base)

    /** The position of field [index] of [table], or `null` if it is absent or the vtable is out of bounds. */
    private fun field(table: Int, index: Int): Int? {
        val vtableOffset = i32(table) ?: return null
        val vtable = table.toLong() - vtableOffset
        if (vtable < 0 || vtable >= bytes.size) return null
        val vtablePos = vtable.toInt()
        val vtableSize = u16(vtablePos) ?: return null
        val slot = 4 + 2 * index
        if (slot + 2 > vtableSize) return null
        val fieldOffset = u16(vtablePos + slot) ?: return null
        return if (fieldOffset == 0) null else table + fieldOffset
    }

    fun tableField(table: Int, index: Int): Int? = field(table, index)?.let { indirect(it) }

    fun vectorField(table: Int, index: Int): FlatVector? {
        val vector = field(table, index)?.let { indirect(it) } ?: return null
        val count = i32(vector) ?: return null
        if (count < 0 || count > (bytes.size - vector) / 4) return null
        return FlatVector(vector + 4, count)
    }

    fun vectorTable(vector: FlatVector, index: Int): Int? = indirect(vector.start + 4 * index)

    fun longField(table: Int, index: Int): Long? {
        val pos = field(table, index) ?: return null
        if (pos + 8 > bytes.size) return null
        return bytes.readLong(pos)
    }
}

private fun ByteArray.readLong(pos: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) value = (value shl 8) or (this[pos + i].toLong() and 0xff)
    return value
}
