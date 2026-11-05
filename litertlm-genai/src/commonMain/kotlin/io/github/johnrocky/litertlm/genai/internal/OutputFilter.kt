package io.github.johnrocky.litertlm.genai.internal

/**
 * Turns what the model writes into what the task promises, while it is being written. What the filter returns from
 * [accept] and [finish], put together, is the whole result: a streaming callback and the final result always agree.
 */
internal interface OutputFilter {
    /** The rest of the model's reply is not needed, so generating it can stop. */
    val isDone: Boolean

    fun accept(chunk: String): String

    fun finish(): String
}

internal object PassThrough : OutputFilter {
    override val isDone: Boolean = false

    override fun accept(chunk: String): String = chunk

    override fun finish(): String = ""
}

private const val SPACE_FREE_MARKERS = "•·‣・●○◦"
private const val SPACED_MARKERS = "*-–—+"

/**
 * Keeps at most [maxBullets] bullets, each `* text` on its own line with no line break after the last: the format of
 * ML Kit's summaries (reference of SummarizerOptions.OutputType). An introduction, a title and a closing remark are
 * dropped, and other bullet markers and `**bold**` are normalized away. If the model wrote no bullets at all, its lines
 * become the bullets.
 */
internal class BulletFilter(private val maxBullets: Int) : OutputFilter {
    private var bullets = 0
    private val looseLines = ArrayList<String>()

    /** The start of a line, kept until it shows what kind of line it is. */
    private val head = StringBuilder()
    private var inBullet = false

    /** Whether the current bullet has written anything yet. */
    private var bulletStarted = false
    private val heldSpaces = StringBuilder()
    private var heldStar = false

    override val isDone: Boolean get() = bullets >= maxBullets

    override fun accept(chunk: String): String {
        val out = StringBuilder()
        for (c in chunk) {
            if (isDone) break
            if (c == '\n' || c == '\r') endLine(out) else if (inBullet) bulletChar(c, out) else headChar(c, out)
        }
        return out.toString()
    }

    private fun headChar(c: Char, out: StringBuilder) {
        if (head.isEmpty() && c.isWhitespace()) return
        head.append(c)
        when (classify(head, lineEnded = false)) {
            LineKind.BULLET -> {
                inBullet = true
                val rest = markerEnd(head)
                val text = head.substring(rest)
                head.clear()
                text.forEach { bulletChar(it, out) }
            }
            LineKind.LOOSE, LineKind.UNDECIDED -> Unit
        }
    }

    private fun bulletChar(c: Char, out: StringBuilder) {
        if (c == '*') {
            if (heldStar) {
                heldStar = false // `**`: bold, which a plain-text bullet does not have.
            } else {
                heldStar = true
            }
            return
        }
        flushStar(out)
        if (c.isWhitespace()) {
            if (bulletStarted) heldSpaces.append(c)
            return
        }
        startBullet(out)
        out.append(heldSpaces)
        heldSpaces.clear()
        out.append(c)
    }

    private fun flushStar(out: StringBuilder) {
        if (!heldStar) return
        heldStar = false
        startBullet(out)
        out.append(heldSpaces)
        heldSpaces.clear()
        out.append('*')
    }

    private fun startBullet(out: StringBuilder) {
        if (bulletStarted) return
        bulletStarted = true
        if (bullets > 0) out.append('\n')
        out.append("* ")
    }

    private fun endLine(out: StringBuilder) {
        if (inBullet) {
            flushStar(out)
            heldSpaces.clear()
            if (bulletStarted) bullets++
            bulletStarted = false
            inBullet = false
        } else if (head.isNotEmpty()) {
            if (classify(head, lineEnded = true) == LineKind.BULLET) {
                // A marker alone on the line: nothing to keep.
            } else {
                looseLines += head.toString().trim()
            }
            head.clear()
        }
    }

    override fun finish(): String {
        val out = StringBuilder()
        if (!isDone) endLine(out)
        if (bullets == 0) {
            looseLines.map { it.replace("**", "").trim() }
                .filter { it.isNotEmpty() && !it.endsWith(":") && !it.startsWith("#") }
                .take(maxBullets)
                .forEachIndexed { index, line ->
                    if (index > 0) out.append('\n')
                    out.append("* ").append(line)
                }
        }
        return out.toString()
    }

    private enum class LineKind { BULLET, LOOSE, UNDECIDED }

    private fun classify(line: CharSequence, lineEnded: Boolean): LineKind {
        val first = line[0]
        if (first in SPACE_FREE_MARKERS) return LineKind.BULLET
        if (first in SPACED_MARKERS) {
            if (line.length == 1) return if (lineEnded) LineKind.BULLET else LineKind.UNDECIDED
            return if (line[1] == ' ' || line[1] == '\t') LineKind.BULLET else LineKind.LOOSE
        }
        if (first.isDigit()) {
            var i = 0
            while (i < line.length && line[i].isDigit()) i++
            if (i == line.length) return if (lineEnded) LineKind.LOOSE else LineKind.UNDECIDED
            if (line[i] != '.' && line[i] != ')') return LineKind.LOOSE
            if (i + 1 == line.length) return if (lineEnded) LineKind.BULLET else LineKind.UNDECIDED
            return if (line[i + 1] == ' ' || line[i + 1] == '\t') LineKind.BULLET else LineKind.LOOSE
        }
        return LineKind.LOOSE
    }

    /** Where the text of a line that is a bullet starts. */
    private fun markerEnd(line: CharSequence): Int {
        var i = 0
        if (line[0].isDigit()) {
            while (line[i].isDigit()) i++
        }
        return i + 1
    }
}

/** How an introduction starts. Only a first line that also ends with a colon is dropped. */
private val INTRODUCTION_STARTS = listOf(
    "here", "sure", "okay", "ok,", "ok.", "certainly", "of course", "absolutely", "below",
    "corrected", "rewritten", "revised", "rephrased", "shortened", "elaborated", "proofread", "friendly", "professional",
    "the corrected", "the rewritten", "the revised", "the proofread", "the rephrased",
)

private const val QUOTES = "\"\u201C\u201D"

/**
 * For a reply that is plain text: drops a first line like `Here is the corrected text:`, the quotes around the whole
 * reply, and the space at both ends.
 */
internal class ProseFilter : OutputFilter {
    override val isDone: Boolean = false

    private enum class State {
        /** Before the first character of a line that may be an introduction. */
        START,

        /** In a first line that still looks like an introduction: nothing of it is written yet. */
        FIRST_LINE,

        /** In the text. */
        TEXT,

        /** The reply starts with a quote. Only its end shows whether the quotes wrap all of it, so it is all held. */
        QUOTED,
    }

    private var state = State.START
    private var droppedIntroduction = false
    private val line = StringBuilder()

    /** Space at the end of what was read so far: it is written only if more text follows. */
    private val space = StringBuilder()

    override fun accept(chunk: String): String {
        val out = StringBuilder()
        chunk.forEach { read(it, out) }
        return out.toString()
    }

    private fun read(c: Char, out: StringBuilder) {
        when (state) {
            State.START -> when {
                c.isWhitespace() -> Unit
                c in QUOTES -> {
                    state = State.QUOTED
                    line.append(c)
                }
                else -> {
                    state = State.FIRST_LINE
                    read(c, out)
                }
            }
            State.FIRST_LINE -> when {
                c == '\n' -> {
                    val text = line.toString().trim()
                    line.clear()
                    if (!droppedIntroduction && text.endsWith(":") && startsLikeIntroduction(text)) {
                        droppedIntroduction = true
                        state = State.START
                    } else {
                        state = State.TEXT
                        text.forEach { read(it, out) }
                        read(c, out)
                    }
                }
                else -> {
                    line.append(c)
                    if (droppedIntroduction || !mayBecomeIntroduction(line)) {
                        val text = line.toString()
                        line.clear()
                        state = State.TEXT
                        text.forEach { read(it, out) }
                    }
                }
            }
            State.TEXT -> {
                if (c.isWhitespace()) {
                    space.append(c)
                } else {
                    out.append(space)
                    space.clear()
                    out.append(c)
                }
            }
            State.QUOTED -> line.append(c)
        }
    }

    private fun mayBecomeIntroduction(text: CharSequence): Boolean {
        val lower = text.toString().lowercase()
        return INTRODUCTION_STARTS.any { it.startsWith(lower) || lower.startsWith(it) }
    }

    private fun startsLikeIntroduction(text: String): Boolean {
        val lower = text.lowercase()
        return INTRODUCTION_STARTS.any { lower.startsWith(it) }
    }

    override fun finish(): String {
        val text = line.toString().trim()
        line.clear()
        val finished = state
        state = State.TEXT
        return when (finished) {
            // The whole reply was one line that looked like an introduction, so it is the text after all.
            State.FIRST_LINE -> text
            State.QUOTED -> {
                val inner = text.substring(1, text.length.coerceAtLeast(1))
                // Wrapped only if the quote at the end is the only other one.
                if (inner.isNotEmpty() && inner.last() in QUOTES && inner.dropLast(1).none { it in QUOTES }) {
                    inner.dropLast(1).trim()
                } else {
                    text
                }
            }
            else -> ""
        }
    }
}
