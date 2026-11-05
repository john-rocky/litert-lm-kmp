@file:OptIn(NanoAdapterApi::class)

package io.github.johnrocky.litertlm.genai.internal

import android.app.ActivityManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import io.github.johnrocky.litertlm.genai.common.PlatformContext
import io.github.johnrocky.litertlm.genai.spi.NanoAdapter
import io.github.johnrocky.litertlm.genai.spi.NanoAdapterApi
import java.io.File

private const val NANO_ADAPTER_CLASS = "io.github.johnrocky.litertlm.genai.nano.MlKitNanoAdapter"

/**
 * By name, so that this artifact needs nothing of that one, and looked for once, at the first client. A consumer rule of
 * `litertlm-genai-nano` keeps the name in a release build.
 */
private val nanoAdapter: NanoAdapter? by lazy {
    try {
        Class.forName(NANO_ADAPTER_CLASS).getDeclaredConstructor().newInstance() as NanoAdapter
    } catch (e: ReflectiveOperationException) {
        null
    }
}

internal actual fun findNanoAdapter(): NanoAdapter? = nanoAdapter

internal actual val noNanoReason: String = NO_NANO_ARTIFACT

/** Runs when the app starts, as a provider does, and keeps the application's context. It serves nothing. */
internal class ContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        stored = context?.applicationContext
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0

    companion object {
        @Volatile
        private var stored: Context? = null

        fun applicationContext(): Context =
            stored ?: error("The library's ContextProvider did not run. Is it removed from the merged manifest?")
    }
}

internal actual fun defaultModelsDir(context: PlatformContext): String =
    File(context.noBackupFilesDir, "litertlm-genai/models").path

internal actual fun defaultCacheDir(context: PlatformContext): String = File(context.cacheDir, "litertlm-genai").path

// With the screen off a Pixel 8a on Android 16 blocked the app's network (APP_BACKGROUND), and the host was "not found".
internal actual fun networkNote(): String? {
    val process = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(process)
    return if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
        "This app was not on screen, and Android can stop the network of such an app: keep the screen on during a download."
    } else {
        null
    }
}
