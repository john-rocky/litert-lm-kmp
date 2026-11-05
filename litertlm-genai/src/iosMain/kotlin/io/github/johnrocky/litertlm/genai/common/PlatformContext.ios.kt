package io.github.johnrocky.litertlm.genai.common

public actual abstract class PlatformContext private constructor() {
    internal object Instance : PlatformContext()
}

public actual fun platformContext(): PlatformContext = PlatformContext.Instance
