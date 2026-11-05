package io.github.johnrocky.litertlm.genai.internal

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

private const val JPEG_QUALITY = 95

/** What a local model takes: PNG if the picture has transparency, otherwise JPEG. */
internal fun Bitmap.encoded(): ByteArray {
    val out = ByteArrayOutputStream()
    val format = if (hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    compress(format, JPEG_QUALITY, out)
    return out.toByteArray()
}
