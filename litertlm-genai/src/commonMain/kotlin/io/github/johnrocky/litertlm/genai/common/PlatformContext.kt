package io.github.johnrocky.litertlm.genai.common

/**
 * What the options builders take. On Android this is `android.content.Context`, so `builder(context)` is the same call
 * as in ML Kit. On iOS and the JVM nothing is needed: [platformContext] gives the only value there is.
 */
public expect abstract class PlatformContext

/** The application's context, for code in `commonMain` that has none to pass. */
public expect fun platformContext(): PlatformContext
