package io.github.johnrocky.litertlm.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import io.github.johnrocky.litertlm.Backend
import io.github.johnrocky.litertlm.Conversation
import io.github.johnrocky.litertlm.Engine
import io.github.johnrocky.litertlm.EngineConfig
import io.github.johnrocky.litertlm.LiteRtLmException
import io.github.johnrocky.litertlm.genai.common.DownloadStatus
import io.github.johnrocky.litertlm.genai.common.LocalModel
import io.github.johnrocky.litertlm.genai.common.ModelProvider
import io.github.johnrocky.litertlm.genai.prompt.Generation
import io.github.johnrocky.litertlm.stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_NUM_TOKENS = 4096

private data class ChatLine(val fromUser: Boolean, val text: String)

private class Status(val text: String, val isError: Boolean = false)

/** What a person would do on the screen, from launch arguments: for a check on a phone that nothing can tap. */
class ChatScript(
    val useGpu: Boolean = false,
    val modelPath: String? = null,
    val download: Boolean = false,
    val first: String? = null,
    val stopAfterChars: Int? = null,
    val next: String? = null,
)

/** The engine and the conversation behind the screen. Closing them can wait for a reply, so it runs off the main thread. */
private class ChatSession {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun stop() {
        conversation?.cancelProcess()
    }

    suspend fun conversationFor(config: EngineConfig): Conversation {
        val current = engine
        if (current == null || current.engineConfig != config) {
            close()
            engine = Engine(config).also { it.initialize() }
        }
        return conversation ?: engine!!.createConversation().also { conversation = it }
    }

    suspend fun newChat() {
        val old = conversation
        conversation = null
        withContext(Dispatchers.Default) { old?.close() }
    }

    suspend fun close() {
        val old = engine
        engine = null
        conversation = null
        withContext(Dispatchers.Default) { old?.close() }
    }
}

/** What the screen says about a failure. */
private fun describe(error: Throwable): String = when (error) {
    is LiteRtLmException.ModelNotFound -> "There is no model at this path. Download it, or type another path."
    is LiteRtLmException.InvalidModel -> "This file cannot be used: ${error.reason}."
    is LiteRtLmException.InsufficientMemory -> "There is not enough free memory for this model. Close other apps and try again."
    is LiteRtLmException.BackendUnavailable -> "The ${error.backend} backend cannot run this model on this device. Try CPU."
    is LiteRtLmException.InputTooLong -> "That message does not fit in the context window. Shorten it, or start a new chat."
    is LiteRtLmException.Cancelled -> "Stopped."
    else -> error.message ?: error.toString()
}

/** A chat with a local model. [modelsDir] is where downloads go; [cacheDir] is where the runtime's compiled caches go. */
@Composable
fun ChatScreen(modelsDir: String, cacheDir: String, downloadEndpoint: String = HUGGING_FACE, script: ChatScript? = null) {
    val scope = rememberCoroutineScope()
    val session = remember { ChatSession() }
    val lines = remember { mutableStateListOf<ChatLine>() }
    var modelPath by remember { mutableStateOf("$modelsDir/$GEMMA_FILE_NAME") }
    var useGpu by remember { mutableStateOf(gpuByDefault) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Status?>(null) }
    var generating by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadedFraction by remember { mutableStateOf<Float?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Keeps the end of the reply in view while it streams.
    LaunchedEffect(lines.size, lines.lastOrNull()?.text?.length) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex, Int.MAX_VALUE)
    }

    DisposableEffect(session) {
        onDispose { CoroutineScope(Dispatchers.Default).launch { session.close() } }
    }

    fun download() {
        downloading = true
        downloadedFraction = null
        status = Status("Downloading Gemma 4 E2B...")
        val model = LocalModel.huggingFace(GEMMA_REPO, GEMMA_FILE_NAME, directory = modelsDir, endpoint = downloadEndpoint)
        scope.launch {
            try {
                Generation.getClient(ModelProvider.LiteRtLm(model, cacheDir = cacheDir)).use { client ->
                    var total = 0L
                    client.download().collect { step ->
                        when (step) {
                            is DownloadStatus.DownloadStarted -> total = step.bytesToDownload
                            is DownloadStatus.DownloadProgress ->
                                downloadedFraction = step.totalBytesDownloaded.toFloat() / total.coerceAtLeast(1)
                            is DownloadStatus.DownloadFailed ->
                                status = Status(step.e.message ?: "The download failed.", isError = true)
                            DownloadStatus.DownloadCompleted -> {
                                modelPath = "$modelsDir/$GEMMA_FILE_NAME"
                                status = Status("Downloaded.")
                            }
                        }
                    }
                }
            } finally {
                downloading = false
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        focusManager.clearFocus()
        status = null
        generating = true
        lines += ChatLine(fromUser = true, text = text)
        val reply = lines.size
        lines += ChatLine(fromUser = false, text = "")
        val config = EngineConfig(
            modelPath = modelPath.trim(),
            backend = if (useGpu) Backend.GPU() else Backend.CPU(),
            maxNumTokens = MAX_NUM_TOKENS,
            cacheDir = cacheDir,
        )
        scope.launch {
            try {
                status = Status("Loading the model...")
                val conversation = session.conversationFor(config)
                status = null
                conversation.stream(text).collect { chunk ->
                    lines[reply] = lines[reply].copy(text = lines[reply].text + chunk)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = Status(describe(e), isError = e !is LiteRtLmException.Cancelled)
            } finally {
                generating = false
            }
        }
    }

    LaunchedEffect(script) {
        val steps = script ?: return@LaunchedEffect
        useGpu = steps.useGpu
        steps.modelPath?.let { modelPath = it }
        if (steps.download) {
            download()
            snapshotFlow { downloading }.first { !it }
        }
        steps.first?.let {
            input = it
            send()
        }
        steps.stopAfterChars?.let { chars ->
            snapshotFlow { lines.lastOrNull()?.text?.length ?: 0 }.first { it >= chars }
            session.stop()
        }
        snapshotFlow { generating }.first { !it }
        steps.next?.let {
            // Long enough to read what the first turn left on the screen.
            delay(4_000)
            input = it
            send()
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = modelPath,
                    onValueChange = { modelPath = it },
                    label = { Text("Model path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { download() }, enabled = !downloading && !generating) {
                        Text("Download Gemma 4 E2B")
                    }
                    FilterChip(selected = !useGpu, onClick = { useGpu = false }, label = { Text("CPU") })
                    FilterChip(selected = useGpu, onClick = { useGpu = true }, label = { Text("GPU") })
                }
                if (downloading) {
                    val fraction = downloadedFraction
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                status?.let {
                    Text(
                        text = it.text,
                        color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            text = if (line.fromUser) "You: ${line.text}" else line.text,
                            color = if (line.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (generating) {
                        Button(onClick = { session.stop() }) { Text("Stop") }
                    } else {
                        Button(onClick = { send() }, enabled = input.isNotBlank()) { Text("Send") }
                    }
                    OutlinedButton(
                        onClick = {
                            lines.clear()
                            status = null
                            scope.launch { session.newChat() }
                        },
                        enabled = !generating,
                    ) { Text("New chat") }
                }
            }
        }
    }
}
