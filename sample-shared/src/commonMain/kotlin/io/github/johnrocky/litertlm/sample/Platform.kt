package io.github.johnrocky.litertlm.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Takes memory away from the rest of the system, for checking that a model is refused when memory is short. */
internal interface MemoryBallast {
    /** The same figure the library checks: what this process can still use. */
    fun availableBytes(): Long

    /** Allocates [bytes] and touches every page. */
    fun add(bytes: Long)

    fun release()
}

/** `null` on the JVM and the simulator: nothing there runs out of memory the way a phone does. */
internal expect fun memoryBallast(): MemoryBallast?

/** Only Android can have Gemini Nano. */
internal expect val mayHaveGeminiNano: Boolean

/** The backend the screens start with: the GPU on a phone, the CPU on the iOS simulator, where the GPU cannot run. */
internal expect val gpuByDefault: Boolean

/** Puts the app behind others, for checking what Gemini Nano does then. `false` where that cannot be done. */
internal expect fun sendAppToBackground(): Boolean

/** `null` if the bytes are not an image. */
internal expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Something that opens the system's photo picker and hands over a JPEG. `null` where there is no picker. */
@Composable
internal expect fun rememberImagePicker(onPicked: (ByteArray) -> Unit): (() -> Unit)?
