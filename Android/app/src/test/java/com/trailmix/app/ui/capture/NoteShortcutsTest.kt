package com.trailmix.app.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteShortcutsTest {
    @Test fun headingPrefixesTheCurrentLine() {
        val e = NoteShortcuts.heading("pricing\nboard wants it", cursor = 3)
        assertEquals("# pricing\nboard wants it", e.text)
        assertEquals(5, e.cursor)
    }

    @Test fun headingOnLaterLineLeavesEarlierLinesAlone() {
        val e = NoteShortcuts.heading("a\nb", cursor = 3)
        assertEquals("a\n# b", e.text)
        assertEquals(5, e.cursor)
    }

    @Test fun headingTogglesOff() {
        val e = NoteShortcuts.heading("# pricing", cursor = 6)
        assertEquals("pricing", e.text)
        assertEquals(4, e.cursor)
    }

    @Test fun headingOnEmptyTextStartsAHeading() {
        val e = NoteShortcuts.heading("", cursor = 0)
        assertEquals("# ", e.text)
        assertEquals(2, e.cursor)
    }

    @Test fun questionAppendsToTheEndOfTheCurrentLineNotTheText() {
        val e = NoteShortcuts.question("do we grandfather annual plans\nnext", cursor = 5)
        assertEquals("do we grandfather annual plans?\nnext", e.text)
        assertEquals(31, e.cursor)
    }

    @Test fun questionIgnoresTrailingSpaces() {
        val e = NoteShortcuts.question("open item   ", cursor = 4)
        assertEquals("open item?   ", e.text)
    }

    @Test fun questionLeavesAnExistingQuestionAlone() {
        val e = NoteShortcuts.question("already?", cursor = 2)
        assertEquals("already?", e.text)
    }

    @Test fun questionLeavesABlankOrBareHashLineAlone() {
        assertEquals("", NoteShortcuts.question("", 0).text)
        assertEquals("#", NoteShortcuts.question("#", 1).text)
    }

    @Test fun outOfRangeCursorIsClamped() {
        assertEquals("# a", NoteShortcuts.heading("a", cursor = 99).text)
    }
}
