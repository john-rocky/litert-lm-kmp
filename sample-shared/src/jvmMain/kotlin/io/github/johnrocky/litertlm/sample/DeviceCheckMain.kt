package io.github.johnrocky.litertlm.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image

internal actual fun memoryBallast(): MemoryBallast? = null

internal actual val mayHaveGeminiNano: Boolean = false

internal actual val gpuByDefault: Boolean = false

internal actual fun sendAppToBackground(): Boolean = false

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

@Composable
internal actual fun rememberImagePicker(onPicked: (ByteArray) -> Unit): (() -> Unit)? = null

fun main(arguments: Array<String>) {
    val options = DeviceCheckArgs.parseCommandLine(arguments.toList())
    val args = DeviceCheckArgs.fromOptions(options) ?: run {
        System.err.println("Usage: --model PATH --workdir DIR [--embedding PATH] [--out FILE] [--backend cpu|gpu] [--suite raw|task|all] [--provider auto|nano|litertlm]")
        exitProcess(2)
    }
    val ok = runBlocking { DeviceCheck(args) { println(it) }.run() }
    exitProcess(if (ok) 0 else 1)
}
