package com.trailmix.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {
    private val full = UserProfile("Sam", "Engineering Manager", "Acme", listOf("hiring", "roadmap"))

    @Test fun roundTrip() {
        assertEquals(full, UserProfileJson.decode(UserProfileJson.encode(full)))
    }

    @Test fun malformedAndBlankDecodeEmpty() {
        assertTrue(UserProfileJson.decode(null).isEmpty)
        assertTrue(UserProfileJson.decode("  ").isEmpty)
        assertTrue(UserProfileJson.decode("{not json").isEmpty)
        assertTrue(UserProfileJson.decode("[1,2]").isEmpty)
        assertTrue(UserProfileJson.decode("{}").isEmpty)
    }

    @Test fun missingKeysDefault() {
        assertEquals(UserProfile(name = "Sam"), UserProfileJson.decode("""{"name":"Sam"}"""))
    }

    @Test fun promptLineFull() {
        assertEquals(
            "The note-taker is Sam, Engineering Manager at Acme, focused on hiring and roadmap.",
            full.promptLine(),
        )
    }

    @Test fun promptLineEmptyIsNull() {
        assertNull(UserProfile().promptLine())
        assertNull(UserProfile(name = "  ", focusAreas = listOf(" ", "")).promptLine())
    }

    @Test fun promptLineVariants() {
        assertEquals("The note-taker is Sam.", UserProfile(name = "Sam").promptLine())
        assertEquals("The note-taker is Sam, who works at Acme.", UserProfile(name = "Sam", company = "Acme").promptLine())
        assertEquals("The note-taker works as PM.", UserProfile(role = "PM").promptLine())
        assertEquals("The note-taker works at Acme.", UserProfile(company = "Acme").promptLine())
        assertEquals("The note-taker is focused on a, b, and c.", UserProfile(focusAreas = listOf("a", "b", "c")).promptLine())
        assertEquals("The note-taker is Sam, focused on hiring.", UserProfile(name = "Sam", focusAreas = listOf("hiring")).promptLine())
    }

    @Test fun capsApplied() {
        val p = UserProfile(
            name = "N".repeat(500),
            focusAreas = (1..20).map { "area$it" } + listOf("AREA1"),
        ).normalized()
        assertEquals(UserProfile.MAX_NAME, p.name.length)
        assertEquals(UserProfile.MAX_FOCUS_AREAS, p.focusAreas.size)
        assertTrue(UserProfile(name = "N".repeat(500)).promptLine()!!.length < 100)
    }

    @Test fun whitespaceCollapsedAndDeduped() {
        val p = UserProfile(name = "  Sam   Lee ", focusAreas = listOf("x", "X", " x ")).normalized()
        assertEquals("Sam Lee", p.name)
        assertEquals(listOf("x"), p.focusAreas)
    }

    @Test fun parseFocusAreas() {
        assertEquals(listOf("a", "b", "c"), UserProfile.parseFocusAreas(" a, b;\nc,, "))
    }
}
