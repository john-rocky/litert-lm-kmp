package io.github.johnrocky.litertlm.internal

import java.lang.management.ManagementFactory

internal actual val memoryCheckByDefault: Boolean = false

internal actual fun freeAddressSpaceBytes(limit: Long): Long = limit

internal actual fun availableMemoryBytes(): Long {
    val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    return bean?.freeMemorySize ?: Runtime.getRuntime().maxMemory()
}
