package com.trailmix.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterTest {

    @Test
    fun `an email address becomes a name`() {
        val roster = Roster.parse(listOf("rob.smith@acme.com"), null)
        assertEquals("Rob Smith", roster.people.single().display)
    }

    @Test
    fun `last comma first is reordered`() {
        assertEquals("Rob Smith", Roster.parse(listOf("Smith, Rob"), null).people.single().display)
    }

    @Test
    fun `a unique first name and its nickname are aliases`() {
        val aliases = Roster.parse(listOf("Robert Smith", "Priya Patel"), null).people.first().aliases
        assertTrue(listOf("robert") in aliases)
        assertTrue(listOf("rob") in aliases)
        assertTrue(listOf("robert", "smith") in aliases)
    }

    @Test
    fun `a first name shared by two attendees is nobody's alias`() {
        val roster = Roster.parse(listOf("Rob Smith", "Rob Jones"), null)
        roster.people.forEach { assertTrue(listOf("rob") !in it.aliases) }
        assertTrue(listOf("rob", "smith") in roster.people[0].aliases)
    }

    @Test
    fun `duplicate entries collapse`() {
        assertEquals(1, Roster.parse(listOf("Priya Patel", "priya patel"), null).people.size)
    }

    @Test
    fun `the note-taker is found by name and only when unambiguous`() {
        val roster = Roster.parse(listOf("Jack Lee", "Priya Patel"), "Priya Patel")
        assertEquals(1, roster.meIndex)
        assertEquals(1, Roster.parse(listOf("Jack Lee", "Priya Patel"), "Priya").meIndex)
        assertNull(Roster.parse(listOf("Jack Lee", "Priya Patel"), "Someone Else").meIndex)
        assertNull(Roster.parse(listOf("Jack Lee", "Jack Brown"), "Jack").meIndex)
        assertNull(Roster.parse(listOf("Jack Lee", "Priya Patel"), null).meIndex)
    }

    @Test
    fun `an empty or junk list is empty`() {
        assertTrue(Roster.parse(emptyList(), null).isEmpty)
        assertTrue(Roster.parse(listOf("   ", "123"), null).isEmpty)
    }
}
