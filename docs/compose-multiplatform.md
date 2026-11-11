# Compose Multiplatform

One composable for Android, iOS and the desktop:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import io.github.johnrocky.litertlm.genai.common.GenAiException
import io.github.johnrocky.litertlm.genai.summarization.*
import kotlinx.coroutines.launch

@Composable
fun SummaryButton(article: String) {
    val summarizer = remember { Summarization.getClient(SummarizerOptions.builder().build()) }
    DisposableEffect(Unit) { onDispose { summarizer.close() } }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    Column {
        Button(enabled = !running, onClick = {
            scope.launch {
                running = true
                text = ""
                try {
                    text = summarizer.runInference(SummarizationRequest.builder(article).build()) { piece -> text += piece }.summary
                } catch (e: GenAiException) {
                    text = e.message ?: "Error ${e.errorCode}"
                } finally {
                    running = false
                }
            }
        }) { Text("Summarize") }
        Text(text)
    }
}
```
