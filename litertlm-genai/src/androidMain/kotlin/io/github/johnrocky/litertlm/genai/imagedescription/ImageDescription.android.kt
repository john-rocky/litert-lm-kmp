package io.github.johnrocky.litertlm.genai.imagedescription

import android.graphics.Bitmap
import io.github.johnrocky.litertlm.genai.internal.encoded

/** As in ML Kit. Gemini Nano takes [bitmap] as it is; a local model gets it encoded. */
public fun ImageDescriptionRequest.Companion.builder(bitmap: Bitmap): ImageDescriptionRequest.Builder =
    ImageDescriptionRequest.Builder(lazy { bitmap.encoded() }, bitmap)
