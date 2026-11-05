@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.johnrocky.litertlm.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import io.github.johnrocky.litertlm.sample.cinterop.sample_available_memory
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.exit
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import platform.posix.memset

private fun directory(which: ULong): String =
    NSSearchPathForDirectoriesInDomains(which, NSUserDomainMask, true).first() as String

/**
 * The app, for the Swift app. [SampleLaunch] has its launch arguments; a relative path in them is under Documents.
 */
fun MainViewController(): UIViewController {
    val documents = directory(NSDocumentDirectory)
    val options = DeviceCheckArgs.parseCommandLine(NSProcessInfo.processInfo.arguments.map { it as String })
    val launch = SampleLaunch.from(options, baseDir = documents)
    return ComposeUIViewController {
        SampleRoot(modelsDir = documents, cacheDir = directory(NSCachesDirectory), launch = launch)
    }
}

/**
 * Runs DeviceCheck if the launch arguments ask for it, then ends the process:
 * `--deviceCheck --model PATH [--workdir DIR] [--embedding PATH] [--out FILE] [--backend gpu] ...`. A relative path is
 * under the app's Documents.
 */
fun startDeviceCheckIfRequested(arguments: List<String>) {
    val options = DeviceCheckArgs.parseCommandLine(arguments)
    val documents = directory(NSDocumentDirectory)
    options["photoIn"]?.let { input ->
        // What the image picker makes of a photo, into --photoOut: a launch cannot press the system's picker.
        val before = sample_available_memory().toLong()
        val jpeg = jpegForModel(UIImage(contentsOfFile = "$documents/$input"))
        println("PHOTOCHECK ${jpeg?.size} bytes, available $before -> ${sample_available_memory().toLong()}")
        if (jpeg != null) SystemFileSystem.sink(Path("$documents/${options.getValue("photoOut")}")).buffered().use { it.write(jpeg) }
        exit(if (jpeg != null) 0 else 1)
        return
    }
    if (options["deviceCheck"] != "true") return
    val args = DeviceCheckArgs.fromOptions(options, defaultWorkDir = "$documents/devicecheck", baseDir = documents)
    if (args == null) {
        println("DEVICECHECK {\"case\":\"_args\",\"ok\":false,\"exceptionMessage\":\"--model is required\"}")
        exit(2)
        return
    }
    CoroutineScope(Dispatchers.Default).launch {
        val ok = DeviceCheck(args) { println(it) }.run()
        exit(if (ok) 0 else 1)
    }
}

internal actual val mayHaveGeminiNano: Boolean = false

internal actual val gpuByDefault: Boolean = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] == null

internal actual fun sendAppToBackground(): Boolean = false

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

private const val PICKED_IMAGE_MAX_SIDE = 1536.0
private const val JPEG_QUALITY = 0.9

private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/**
 * A photo of any size and format as a JPEG a model can take, or `null` if it cannot be read: a library photo can be HEIC,
 * which the runtime does not read. Drawing it applies its orientation.
 */
private fun jpegForModel(photo: UIImage): ByteArray? {
    val (width, height) = photo.size.useContents { width to height }
    val scale = minOf(1.0, PICKED_IMAGE_MAX_SIDE / maxOf(width, height))
    val format = UIGraphicsImageRendererFormat().apply { setScale(1.0) }
    val upright = UIGraphicsImageRenderer(size = CGSizeMake(width * scale, height * scale), format = format).imageWithActions {
        photo.drawInRect(CGRectMake(0.0, 0.0, width * scale, height * scale))
    }
    return UIImageJPEGRepresentation(upright, JPEG_QUALITY)?.toByteArray()
}

private class PickerDelegate(private val onPicked: (ByteArray) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val item = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider ?: return
        item.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            val jpeg = data?.let { UIImage(data = it) }?.let(::jpegForModel)
            if (jpeg != null) dispatch_async(dispatch_get_main_queue()) { onPicked(jpeg) }
        }
    }
}

@Composable
internal actual fun rememberImagePicker(onPicked: (ByteArray) -> Unit): (() -> Unit)? {
    val controller = LocalUIViewController.current
    val delegate = remember { PickerDelegate(onPicked) }
    return {
        val configuration = PHPickerConfiguration().apply {
            setFilter(PHPickerFilter.imagesFilter)
            setSelectionLimit(1)
        }
        val picker = PHPickerViewController(configuration = configuration)
        picker.delegate = delegate
        controller.presentViewController(picker, animated = true, completion = null)
    }
}

/** os_proc_available_memory returns 0 on the simulator. */
internal actual fun memoryBallast(): MemoryBallast? = MallocBallast.takeIf { it.availableBytes() > 0 }

private object MallocBallast : MemoryBallast {
    private val regions = ArrayList<COpaquePointer>()

    override fun availableBytes(): Long = sample_available_memory().toLong()

    override fun add(bytes: Long) {
        val region = malloc(bytes.convert()) ?: throw IllegalStateException("malloc failed")
        // Touch every page: untouched memory does not count against this process.
        memset(region, 1, bytes.convert())
        regions += region
    }

    override fun release() {
        regions.forEach { free(it) }
        regions.clear()
    }
}
