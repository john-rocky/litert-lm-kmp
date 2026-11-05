package io.github.johnrocky.litertlm.genai.common

import io.github.johnrocky.litertlm.genai.internal.ContextProvider

public actual typealias PlatformContext = android.content.Context

public actual fun platformContext(): PlatformContext = ContextProvider.applicationContext()
