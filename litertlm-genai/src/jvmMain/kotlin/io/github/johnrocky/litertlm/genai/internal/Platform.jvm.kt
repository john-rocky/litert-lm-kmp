@file:OptIn(NanoAdapterApi::class)

package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.spi.NanoAdapter
import io.github.johnrocky.litertlm.genai.spi.NanoAdapterApi
import java.io.File

internal actual fun findNanoAdapter(): NanoAdapter? = null

internal actual val noNanoReason: String = NO_NANO_ON_THIS_PLATFORM

private val cacheRoot = File(System.getProperty("user.home"), ".cache/litertlm-genai")

internal actual fun defaultModelsDir(context: PlatformContext): String = File(cacheRoot, "models").path

internal actual fun defaultCacheDir(context: PlatformContext): String = File(cacheRoot, "cache").path

internal actual fun networkNote(): String? = null
