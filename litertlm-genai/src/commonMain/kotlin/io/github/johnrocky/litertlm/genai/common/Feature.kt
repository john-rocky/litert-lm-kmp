package io.github.johnrocky.litertlm.genai.common

/** Whether a feature can run now, with the same names and numbers as `com.google.mlkit.genai.common.FeatureStatus`. */
public object FeatureStatus {
    /** It cannot run on this device. */
    public const val UNAVAILABLE: Int = 0

    /** It can run after a download. */
    public const val DOWNLOADABLE: Int = 1

    /** The download is running. */
    public const val DOWNLOADING: Int = 2

    /** It is ready. */
    public const val AVAILABLE: Int = 3
}

/**
 * Follows a download. A callback runs on the context of the coroutine that called `downloadFeature`.
 *
 * With a local model the sizes count the whole file, so a resumed download starts its progress at what is already on
 * disk.
 */
public interface DownloadCallback {
    public fun onDownloadStarted(bytesToDownload: Long) {}

    public fun onDownloadFailed(e: GenAiException) {}

    public fun onDownloadProgress(totalBytesDownloaded: Long) {}

    public fun onDownloadCompleted() {}
}

/** One step of a download, for the `Flow` of `GenerativeModel.download()`. */
public sealed class DownloadStatus {
    public data class DownloadStarted(val bytesToDownload: Long) : DownloadStatus()

    public data class DownloadProgress(val totalBytesDownloaded: Long) : DownloadStatus()

    public data class DownloadFailed(val e: GenAiException) : DownloadStatus()

    public data object DownloadCompleted : DownloadStatus()
}

/**
 * Receives a reply as it is generated. Each call is the next piece, not the whole text so far.
 *
 * The pieces are for the screen. Take the text from the result, which is whole even when a piece from Gemini Nano is not.
 */
public fun interface StreamingCallback {
    public fun onNewText(text: String)
}
