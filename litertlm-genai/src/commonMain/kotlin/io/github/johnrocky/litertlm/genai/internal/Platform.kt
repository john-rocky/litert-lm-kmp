package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.genai.common.PlatformContext

/** Where a Hugging Face model goes unless the app says: a directory that backups leave out. */
internal expect fun defaultModelsDir(context: PlatformContext): String

/** Where the runtime's compiled caches go unless the app says. */
internal expect fun defaultCacheDir(context: PlatformContext): String

/** What can still be written under [directory], or a negative number if that cannot be read. */
internal expect fun freeBytes(directory: String): Long

/** In lower-case hex. */
internal expect suspend fun sha256OfFile(path: String): String

internal expect fun platformHttp(): HttpClient

/** Throws a [io.github.johnrocky.litertlm.genai.common.GenAiException] if the app has no runtime to load a model with. */
internal expect suspend fun checkRuntime()

/** A sentence on why this platform may have taken the network away from the app just now, or null. */
internal expect fun networkNote(): String?
