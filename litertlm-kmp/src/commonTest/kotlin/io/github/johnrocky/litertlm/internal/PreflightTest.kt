package io.github.johnrocky.litertlm.internal

import io.github.johnrocky.litertlm.LiteRtLmException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreflightTest {
    @Test
    fun theDefaultMemoryLineIsHalfTheFileForTextAndFiveEighthsWithVision() {
        // Gemma 4 E2B: 1,234 MiB and 1,851 MiB.
        assertEquals(1_294_073_856L, requiredMemory(null, 2_588_147_712L, loadsVision = false, checkByDefault = true))
        assertEquals(1_941_110_784L, requiredMemory(null, 2_588_147_712L, loadsVision = true, checkByDefault = true))
    }

    @Test
    fun whatTheAppSetsWinsOverTheDefaultMemoryLine() {
        assertEquals(5L, requiredMemory(5L, 2_000L, loadsVision = true, checkByDefault = true))
        assertEquals(5L, requiredMemory(5L, 2_000L, loadsVision = false, checkByDefault = false))
        assertEquals(0L, requiredMemory(0L, 2_000L, loadsVision = true, checkByDefault = true))
    }

    @Test
    fun thereIsNoMemoryLineWhereThePlatformDoesNotCheckByDefault() {
        assertNull(requiredMemory(null, 2_000L, loadsVision = true, checkByDefault = false))
    }

    @Test
    fun theAddressSpaceFloorForGemma4E2BIsUnderWhatItsLoadsMapped() {
        val mib = 1024L * 1024
        assertTrue(requiredAddressSpace(2_588_147_712L, loadsVision = false) < 3_514 * mib)
        assertTrue(requiredAddressSpace(2_588_147_712L, loadsVision = true) < 5_192 * mib)
    }

    @Test
    fun enoughAddressSpaceDoesNotWait() {
        var pauses = 0
        checkAddressSpace(100, free = { it }, pause = { pauses++ })
        assertEquals(0, pauses)
    }

    @Test
    fun aCloseThatFinishesInTimeLetsTheLoadGoOn() {
        var pauses = 0
        checkAddressSpace(100, free = { if (pauses < 3) 50 else it }, pause = { pauses++ })
        assertEquals(3, pauses)
    }

    @Test
    fun tooLittleAddressSpaceSaysSoInTheEnd() {
        var pauses = 0
        val failure = assertFailsWith<LiteRtLmException.InsufficientMemory> {
            checkAddressSpace(100, free = { 50 }, pause = { pauses++ })
        }
        assertEquals(50, failure.availableBytes)
        assertEquals(100, failure.requiredBytes)
        assertEquals(10, pauses)
        assertTrue("address space" in failure.message.orEmpty())
    }

    @Test
    fun theAddressSpaceThatIsFreeIsCountedUpToTheLimit() {
        val mib = 1024L * 1024
        // Every platform has this much, and on iOS the blocks are given back: asking twice gives the same answer.
        assertEquals(256 * mib, freeAddressSpaceBytes(256 * mib))
        assertEquals(256 * mib, freeAddressSpaceBytes(256 * mib))
    }

    @Test
    fun readsTheLargestSectionEnd() {
        assertEquals(5000L, readSectionsEnd(header(sections = listOf(1000L to 2000L, 3000L to 5000L))))
    }

    @Test
    fun theFixedLayoutIsWhereTheOtherTestsExpectIt() {
        val bytes = header(sections = listOf(1000L to 2000L))
        assertEquals(1, bytes[VECTOR_POSITION].toInt())
    }

    @Test
    fun aHeaderWithNoSectionsEndsAtZero() {
        assertEquals(0L, readSectionsEnd(header(sections = emptyList())))
    }

    @Test
    fun aSectionThatEndsBeforeItBeginsIsGarbled() {
        assertNull(readSectionsEnd(header(sections = listOf(2000L to 1000L))))
    }

    @Test
    fun aRootOffsetOutsideTheBufferIsGarbled() {
        val bytes = header(sections = listOf(1000L to 2000L))
        bytes.putInt(ROOT_POSITION, Int.MAX_VALUE)
        assertNull(readSectionsEnd(bytes))
    }

    @Test
    fun aHugeVectorCountIsGarbled() {
        val bytes = header(sections = listOf(1000L to 2000L))
        bytes.putInt(VECTOR_POSITION, Int.MAX_VALUE)
        assertNull(readSectionsEnd(bytes))
    }

    @Test
    fun aHeaderCutAnywhereGivesNullOrAnAnswerButNeverThrows() {
        val whole = header(sections = listOf(1000L to 2000L))
        for (size in 0 until whole.size) readSectionsEnd(whole.copyOf(size))
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = Random(581)
        repeat(2_000) {
            val bytes = random.nextBytes(random.nextInt(0, 4096))
            readSectionsEnd(bytes)
        }
    }

    @Test
    fun randomDamageToAGoodHeaderNeverThrows() {
        val random = Random(582)
        val good = header(sections = listOf(1000L to 2000L, 3000L to 5000L))
        repeat(2_000) {
            val bytes = good.copyOf()
            repeat(random.nextInt(1, 8)) { bytes[random.nextInt(bytes.size)] = random.nextInt().toByte() }
            readSectionsEnd(bytes)
        }
    }

    private companion object {
        const val ROOT_POSITION = 32

        /** The prefix, the root offset, and the two tables with their vtables, whose sizes do not vary. */
        const val VECTOR_POSITION = ROOT_POSITION + 4 + (8 + 8) + (6 + 2 + 8)

        /**
         * A minimal header, laid out by hand: the 32-byte prefix, then a FlatBuffer with the tables readSectionsEnd
         * walks. Offsets in FlatBuffers point forward from where they are stored; a table's first field points back
         * to its vtable.
         */
        fun header(sections: List<Pair<Long, Long>>): ByteArray {
            val out = ByteBuilder()
            repeat(ROOT_POSITION) { out.byte(0) }
            val rootOffset = out.reserveInt()

            // LiteRTLMMetaData: field 1 is section_metadata.
            val rootVtable = out.size
            out.short(8)
            out.short(8)
            out.short(0)
            out.short(4)
            val root = out.size
            out.int(root - rootVtable)
            val sectionMetadataOffset = out.reserveInt()

            // SectionMetadata: field 0 is objects. Padded so the table starts on 4 bytes.
            val metadataVtable = out.size
            out.short(6)
            out.short(8)
            out.short(4)
            out.short(0)
            val metadata = out.size
            out.int(metadata - metadataVtable)
            val objectsOffset = out.reserveInt()

            // The objects vector comes before the tables: an offset points forward.
            val vector = out.size
            out.int(sections.size)
            val entries = sections.map { out.reserveInt() }

            // SectionObject: field 1 is begin_offset, field 2 is end_offset. One vtable for all of them.
            val sectionVtable = out.size
            out.short(10)
            out.short(20)
            out.short(0)
            out.short(4)
            out.short(12)
            out.short(0)
            val tables = sections.map { (begin, end) ->
                val table = out.size
                out.int(table - sectionVtable)
                out.long(begin)
                out.long(end)
                table
            }

            out.patchOffset(rootOffset, root)
            out.patchOffset(sectionMetadataOffset, metadata)
            out.patchOffset(objectsOffset, vector)
            entries.zip(tables).forEach { (position, table) -> out.patchOffset(position, table) }
            return out.toByteArray()
        }
    }

    private class ByteBuilder {
        private val bytes = ArrayList<Byte>()
        val size: Int get() = bytes.size

        fun byte(value: Int) {
            bytes += value.toByte()
        }

        fun short(value: Int) {
            for (i in 0..1) byte(value shr (8 * i))
        }

        fun int(value: Int) {
            for (i in 0..3) byte(value shr (8 * i))
        }

        fun long(value: Long) {
            for (i in 0..7) byte((value shr (8 * i)).toInt())
        }

        /** Keeps room for an offset and returns its position. */
        fun reserveInt(): Int = size.also { int(0) }

        fun patchOffset(position: Int, target: Int) {
            val value = target - position
            for (i in 0..3) bytes[position + i] = (value shr (8 * i)).toByte()
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}

private fun ByteArray.putInt(position: Int, value: Int) {
    for (i in 0..3) this[position + i] = (value shr (8 * i)).toByte()
}
