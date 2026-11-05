package io.github.johnrocky.litertlm.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * What launch arguments ask the app to do by itself, for a check on a phone that nothing can tap. iOS takes them as
 * `--key value` and Android as string extras, with the same keys:
 * - `taskDemo` runs one task (`summarize`, `proofread`, `rewrite`, `describe` or `prompt`) with `taskProvider` (`auto`,
 *   `nano` or `litertlm`, the default) and `model`. `taskStopAfterChars` presses Stop once the answer has that many
 *   characters.
 * - `downloadEndpoint` replaces the Hugging Face Hub, to see a download fail.
 * - `uiGpu`, `uiModelPath`, `uiDownload`, `uiSay`, `uiStopAfterChars` and `uiThen` do on the chat screen what a person
 *   would, and open on it.
 */
class SampleLaunch(
    val demo: TaskDemo? = null,
    val script: ChatScript? = null,
    val downloadEndpoint: String = HUGGING_FACE,
) {
    companion object {
        /** @param baseDir What a relative path is under. */
        fun from(options: Map<String, String>, baseDir: String): SampleLaunch {
            fun path(value: String) = if (value.startsWith("/")) value else "$baseDir/$value"
            return SampleLaunch(
                demo = options["taskDemo"]?.let {
                    TaskDemo(
                        it,
                        options["model"]?.let(::path),
                        options["taskProvider"] ?: "litertlm",
                        options["taskStopAfterChars"]?.toIntOrNull(),
                        options["taskBackend"]?.let { backend -> backend == "gpu" } ?: gpuByDefault,
                    )
                },
                script = if (options.keys.any { it.startsWith("ui") }) {
                    ChatScript(
                        useGpu = options["uiGpu"] == "true",
                        modelPath = options["uiModelPath"]?.let(::path),
                        download = options["uiDownload"] == "true",
                        first = options["uiSay"],
                        stopAfterChars = options["uiStopAfterChars"]?.toIntOrNull(),
                        next = options["uiThen"],
                    )
                } else {
                    null
                },
                downloadEndpoint = options["downloadEndpoint"] ?: HUGGING_FACE,
            )
        }
    }
}

/**
 * The app: the tasks, and a chat. Only the open tab has a model loaded, because leaving a tab closes what it loaded.
 *
 * @param modelsDir Where downloads go.
 * @param cacheDir Where the runtime's compiled caches go.
 */
@Composable
fun SampleRoot(modelsDir: String, cacheDir: String, launch: SampleLaunch = SampleLaunch()) {
    var chat by remember { mutableStateOf(launch.script != null) }
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                PrimaryTabRow(selectedTabIndex = if (chat) 1 else 0) {
                    Tab(selected = !chat, onClick = { chat = false }, text = { Text("Tasks") })
                    Tab(selected = chat, onClick = { chat = true }, text = { Text("Chat") })
                }
                if (chat) {
                    ChatScreen(modelsDir, cacheDir, launch.downloadEndpoint, launch.script)
                } else {
                    TaskScreen(modelsDir, cacheDir, launch.downloadEndpoint, launch.demo)
                }
            }
        }
    }
}
