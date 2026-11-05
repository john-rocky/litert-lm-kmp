package io.github.johnrocky.litertlm.genai.prompt

import android.graphics.Bitmap
import android.net.Uri
import io.github.johnrocky.litertlm.genai.common.platformContext
import io.github.johnrocky.litertlm.genai.internal.encoded
import java.io.IOException

/** As in ML Kit. Gemini Nano takes [bitmap] as it is; a local model gets it encoded. */
public fun ImagePart(bitmap: Bitmap): ImagePart = ImagePart(lazy { bitmap.encoded() }, bitmap)

/** As in ML Kit. @throws IOException if the image cannot be read. */
public fun ImagePart(uri: Uri): ImagePart {
    val stream = platformContext().contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
    return ImagePart(stream.use { it.readBytes() })
}

public fun Content.Builder.image(bitmap: Bitmap): Content.Builder = addPart(ImagePart(bitmap))
