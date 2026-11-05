package io.github.johnrocky.litertlm.genai.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The result of [make] on [reply] cut into pieces of every size: a stream must not change what comes out. */
private fun filtered(reply: String, make: () -> OutputFilter): String {
    val results = (1..reply.length.coerceAtLeast(1)).map { size ->
        val filter = make()
        val out = StringBuilder()
        for (piece in reply.chunked(size)) {
            out.append(filter.accept(piece))
            if (filter.isDone) break
        }
        out.append(filter.finish())
        out.toString()
    }
    assertEquals(1, results.toSet().size, "Depends on how the reply is cut: ${results.toSet()}")
    return results.first()
}

private fun bullets(n: Int, reply: String) = filtered(reply) { BulletFilter(n) }

private fun prose(reply: String) = filtered(reply) { ProseFilter() }

class OutputFilterTest {
    @Test
    fun bulletsInTheRightFormStayAsTheyAre() {
        assertEquals("* a b\n* c d\n* e f", bullets(3, "* a b\n* c d\n* e f"))
        assertEquals("* one", bullets(1, "* one"))
        assertEquals("* one", bullets(1, "* one\n"))
    }

    @Test
    fun anIntroductionAndAClosingRemarkAreDropped() {
        assertEquals("* a\n* b", bullets(2, "Here are two points:\n\n* a\n* b\n"))
        assertEquals("* a\n* b", bullets(3, "**Summary**\n* a\nA loose line.\n* b\nHope this helps!"))
    }

    @Test
    fun onlyAsManyBulletsAsAskedForAreKept() {
        assertEquals("* a\n* b", bullets(2, "* a\n* b\n* c\n* d"))
        assertEquals("* a", bullets(1, "* a\n* b"))
    }

    @Test
    fun fewerBulletsAreLeftAsTheyAre() {
        assertEquals("* a", bullets(3, "* a"))
    }

    @Test
    fun otherMarkersBecomeTheSameMarker() {
        assertEquals("* a\n* b", bullets(2, "- a\n- b"))
        assertEquals("* a\n* b", bullets(2, "• a\n• b"))
        assertEquals("* a\n* b", bullets(2, "•a\n•b"))
        assertEquals("* a\n* b", bullets(2, "・a\n・b"))
        assertEquals("* a\n* b", bullets(2, "1. a\n2. b"))
        assertEquals("* a\n* b", bullets(2, "1) a\n2) b"))
        assertEquals("* a\n* b", bullets(2, "– a\n— b"))
        assertEquals("* a\n* b", bullets(2, "  * a\n\t* b"))
    }

    @Test
    fun boldGoesAway() {
        assertEquals("* Key point: it matters", bullets(1, "* **Key point:** it matters"))
        assertEquals("* a\n* b", bullets(2, "* **a**\n* **b**"))
    }

    @Test
    fun aStarInsideTheTextStays() {
        assertEquals("* 2*3 is six", bullets(1, "* 2*3 is six"))
        assertEquals("* ends with *", bullets(1, "* ends with *"))
    }

    @Test
    fun blankLinesAndSpaceDoNotCount() {
        assertEquals("* a\n* b", bullets(2, "* a  \n\n\n*    b   \n\n"))
        assertEquals("* a\n* b", bullets(2, "* a\r\n* b\r\n"))
        assertEquals("* b", bullets(1, "*\n*   \n* b"))
    }

    @Test
    fun numbersThatAreNotAListStayOut() {
        assertEquals("* a", bullets(1, "1863 was a year.\n* a"))
        assertEquals("* a", bullets(1, "-not a marker\n* a"))
    }

    @Test
    fun aReplyWithNoBulletsBecomesBullets() {
        assertEquals("* a sentence.\n* another.", bullets(2, "a sentence.\nanother.\nand one more."))
        assertEquals("* The point.", bullets(1, "Summary:\n\nThe point."))
        assertEquals("* The point.", bullets(1, "# Title:\n**The point.**"))
    }

    @Test
    fun aReplyWithNothingUsableIsEmpty() {
        assertEquals("", bullets(2, ""))
        assertEquals("", bullets(2, "\n\n  \n"))
        assertEquals("", bullets(2, "Here it is:\n"))
    }

    @Test
    fun theFilterSaysWhenTheRestIsNotNeeded() {
        val filter = BulletFilter(2)
        filter.accept("* a\n* b")
        assertTrue(!filter.isDone, "The second bullet may go on.")
        assertEquals("", filter.accept("\n* c"))
        assertTrue(filter.isDone)
    }

    @Test
    fun plainProseStaysAsItIs() {
        assertEquals("Hello there.", prose("Hello there."))
        assertEquals("One.\n\nTwo.", prose("One.\n\nTwo."))
        assertEquals("* not a list, just text", prose("* not a list, just text"))
    }

    @Test
    fun spaceAtBothEndsGoesAway() {
        assertEquals("Hello.", prose("\n\n  Hello.  \n\n"))
        assertEquals("a  b", prose("a  b"))
    }

    @Test
    fun anIntroductionIsDropped() {
        assertEquals("Hello there.", prose("Here is the corrected text:\n\nHello there."))
        assertEquals("Hello there.", prose("Corrected text:\nHello there."))
        assertEquals("Hello there.", prose("Sure! Here's the rewrite:\n\nHello there."))
        assertEquals("Hello there.", prose("Here is the corrected text:\n\n\"Hello there.\""))
    }

    @Test
    fun aFirstLineThatOnlyLooksLikeOneStays() {
        assertEquals("Here is a dog.", prose("Here is a dog."))
        assertEquals("Here is a dog.\nIt barks.", prose("Here is a dog.\nIt barks."))
        assertEquals("Sure enough, it rained.", prose("Sure enough, it rained."))
        assertEquals("Hello team:\nMeet at 3.", prose("Hello team:\nMeet at 3."))
    }

    @Test
    fun onlyTheFirstIntroductionIsDropped() {
        assertEquals("Here is more:\nHello.", prose("Here is one:\nHere is more:\nHello."))
    }

    @Test
    fun quotesAroundTheWholeReplyGoAway() {
        assertEquals("Hello there.", prose("\"Hello there.\""))
        assertEquals("Hello there.", prose("“Hello there.”"))
        assertEquals("Hello there.", prose("  \"Hello there.\"  \n"))
    }

    @Test
    fun otherQuotesStay() {
        assertEquals("\"Hi\" he said.", prose("\"Hi\" he said."))
        assertEquals("\"Hi\" and \"bye\"", prose("\"Hi\" and \"bye\""))
        assertEquals("He said \"hi\"", prose("He said \"hi\""))
        assertEquals("\"", prose("\""))
    }

    @Test
    fun aReplyThatIsOnlyAnIntroductionIsEmpty() {
        assertEquals("", prose("Here is the corrected text:\n"))
        assertEquals("", prose("   "))
    }

    @Test
    fun aFirstLineWithAColonAndNothingAfterItIsTheText() {
        assertEquals("Here is the corrected text:", prose("Here is the corrected text:"))
    }
}
