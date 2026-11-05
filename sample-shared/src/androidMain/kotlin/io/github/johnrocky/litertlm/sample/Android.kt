package io.github.johnrocky.litertlm.sample

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SharedMemory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val PAGE_BYTES = 4096
private const val PICKED_IMAGE_MAX_SIDE = 1536
private const val JPEG_QUALITY = 90

private var deviceCheckActivity = WeakReference<Activity>(null)

internal actual val mayHaveGeminiNano: Boolean = true

internal actual val gpuByDefault: Boolean = true

internal actual fun sendAppToBackground(): Boolean = deviceCheckActivity.get()?.moveTaskToBack(true) ?: false

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/**
 * A photo of any size and format as a JPEG a model can take, or `null` if it cannot be read. ImageDecoder applies the
 * photo's orientation, which BitmapFactory leaves out and the runtime does not read.
 */
private fun readPhoto(context: Context, uri: Uri): ByteArray? =
    try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = minOf(1f, PICKED_IMAGE_MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    } catch (e: IOException) {
        null
    }

@Composable
internal actual fun rememberImagePicker(onPicked: (ByteArray) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) readPhoto(context, uri)?.let(onPicked)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

internal actual fun memoryBallast(): MemoryBallast? = SharedMemoryBallast

private object SharedMemoryBallast : MemoryBallast {
    private val regions = ArrayList<Pair<SharedMemory, ByteBuffer>>()

    override fun availableBytes(): Long =
        File("/proc/meminfo").useLines { lines ->
            lines.first { it.startsWith("MemAvailable:") }.split(Regex("\\s+"))[1].toLong() * 1024
        }

    override fun add(bytes: Long) {
        val memory = SharedMemory.create("ballast", bytes.toInt())
        val buffer = memory.mapReadWrite()
        var position = 0
        while (position < bytes) {
            buffer.put(position, 1)
            position += PAGE_BYTES
        }
        regions += memory to buffer
    }

    override fun release() {
        regions.forEach { (memory, buffer) ->
            SharedMemory.unmap(buffer)
            memory.close()
        }
        regions.clear()
    }
}

/**
 * Runs DeviceCheck if the launching intent asks for it:
 * `am start -n <package>/.MainActivity --es deviceCheck true --es model <path> [--es backend gpu] ...`
 * Options are the same as on the command line, as string extras. It prints to logcat, tag DEVICECHECK.
 */
fun startDeviceCheckIfRequested(context: Context, extras: Map<String, String>) {
    val baseDir = (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath
    extras["photoIn"]?.let { input ->
        // What the image picker makes of a photo, into photoOut: a launch cannot press the system's picker.
        val jpeg = readPhoto(context, Uri.fromFile(File(baseDir, input)))
        Log.i("DEVICECHECK", "PHOTOCHECK ${jpeg?.size} bytes")
        if (jpeg != null) File(baseDir, extras.getValue("photoOut")).writeBytes(jpeg)
        return
    }
    if (extras["deviceCheck"] != "true") return
    deviceCheckActivity = WeakReference(context as? Activity)
    val args = DeviceCheckArgs.fromOptions(extras, defaultWorkDir = "$baseDir/devicecheck", baseDir = baseDir)
    if (args == null) {
        Log.e("DEVICECHECK", "DEVICECHECK {\"case\":\"_args\",\"ok\":false,\"exceptionMessage\":\"--es model is required\"}")
        return
    }
    CoroutineScope(Dispatchers.Default).launch {
        DeviceCheck(args) { Log.i("DEVICECHECK", it) }.run()
    }
}
