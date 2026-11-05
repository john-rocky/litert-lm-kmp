package io.github.johnrocky.litertlm.genai.internal

import io.github.johnrocky.litertlm.SamplerConfig
import io.github.johnrocky.litertlm.genai.proofreading.ProofreaderOptions
import io.github.johnrocky.litertlm.genai.rewriting.RewriterOptions
import io.github.johnrocky.litertlm.genai.summarization.SummarizerOptions

/**
 * What a local model is asked, the same on every platform. Tasks want the most likely reply, not a varied one: these are
 * the defaults of ML Kit's prompt API, and top-P is what the runtime requires on top of them.
 */
internal val TASK_SAMPLER = SamplerConfig(topK = 3, topP = 0.95, temperature = 0.0, seed = 1)

/** The language codes of the three option classes are the same numbers. */
private fun languageName(language: Int): String = when (language) {
    ProofreaderOptions.Language.JAPANESE -> "Japanese"
    ProofreaderOptions.Language.KOREAN -> "Korean"
    ProofreaderOptions.Language.GERMAN -> "German"
    ProofreaderOptions.Language.FRENCH -> "French"
    ProofreaderOptions.Language.ITALIAN -> "Italian"
    ProofreaderOptions.Language.SPANISH -> "Spanish"
    else -> "English"
}

internal fun bulletCount(outputType: Int): Int = when (outputType) {
    SummarizerOptions.OutputType.TWO_BULLETS -> 2
    SummarizerOptions.OutputType.THREE_BULLETS -> 3
    else -> 1
}

internal fun summarizePrompt(text: String, inputType: Int, outputType: Int, language: Int): String {
    val count = when (bulletCount(outputType)) {
        1 -> "exactly one bullet point"
        2 -> "exactly two bullet points"
        else -> "exactly three bullet points"
    }
    val what = if (inputType == SummarizerOptions.InputType.CONVERSATION) "conversation" else "article"
    return """
        |Summarize the $what below in $count, written in ${languageName(language)}.
        |Each bullet point is one sentence on its own line and starts with "* ".
        |Write only the bullet points: no title, no introduction and no closing remark.
        |
        |${what.replaceFirstChar { it.uppercase() }}:
    """.trimMargin() + "\n" + text
}

internal fun proofreadPrompt(text: String, inputType: Int, language: Int): String {
    val source = if (inputType == ProofreaderOptions.InputType.VOICE) {
        "It came from speech recognition, so also look for words that sound alike."
    } else {
        "It was typed on a keyboard, so also look for slips of nearby keys."
    }
    return """
        |Correct the spelling and grammar of the ${languageName(language)} text below. $source
        |Change nothing else: keep the words, the meaning and the style.
        |Write only the corrected text: no introduction, no quotes and no explanation.
        |
        |Text:
    """.trimMargin() + "\n" + text
}

internal fun rewritePrompt(text: String, outputType: Int, language: Int): String {
    val how = when (outputType) {
        RewriterOptions.OutputType.ELABORATE -> "Rewrite the text below with more detail and description, adding at most two sentences."
        RewriterOptions.OutputType.EMOJIFY -> "Repeat the text below and add emoji that fit its meaning."
        RewriterOptions.OutputType.SHORTEN -> "Rewrite the text below to be much shorter, keeping its main point."
        RewriterOptions.OutputType.FRIENDLY -> "Rewrite the text below in a friendly, casual tone."
        RewriterOptions.OutputType.PROFESSIONAL -> "Rewrite the text below in a formal, professional tone."
        else -> "Say the text below again in other words, with the same meaning."
    }
    return """
        |$how
        |Write in ${languageName(language)}. Write only one rewritten text: no introduction, no options, no quotes and no explanation.
        |
        |Text:
    """.trimMargin() + "\n" + text
}

internal const val DESCRIBE_IMAGE_PROMPT: String =
    "Describe this image in one or two short sentences. Write only the description."
