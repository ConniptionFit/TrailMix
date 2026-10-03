package com.trailmix.app.data.speech

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceprintStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = VoiceprintStore(File(tmp.root, "voiceprints"))
    private fun person(id: String, name: String) =
        VoicePerson(id, name, false, listOf(Voiceprint(floatArrayOf(1f, 0f), 3.0, 1L)))

    @Test
    fun `a fresh store has nobody`() = runBlocking {
        assertTrue(store().people().isEmpty())
    }

    @Test
    fun `people saved by update are read back by a new store`() = runBlocking {
        assertTrue(store().update { it + person("a", "Priya") })
        assertEquals(listOf("Priya"), store().people().map { it.name })
    }

    @Test
    fun `an update sees what is already there`() = runBlocking {
        val s = store()
        s.update { it + person("a", "Priya") }
        s.update { it + person("b", "Jack") }
        assertEquals(listOf("Priya", "Jack"), s.people().map { it.name })
    }

    @Test
    fun `an unreadable people file is kept and never overwritten`() = runBlocking {
        val dir = File(tmp.root, "voiceprints").also { it.mkdirs() }
        File(dir, "people.json").writeText("{ this is not json")
        val s = store()
        assertFalse(s.update { it + person("a", "Priya") })
        assertEquals("{ this is not json", File(dir, "people.json").readText())
        assertTrue(s.people().isEmpty())
    }

    @Test
    fun `no temp file is left behind`() = runBlocking {
        store().update { it + person("a", "Priya") }
        assertEquals(listOf("people.json"), File(tmp.root, "voiceprints").list()!!.toList())
    }

    @Test
    fun `note centroids save, load and delete per note`() = runBlocking {
        val s = store()
        s.saveNoteCentroids(7, mapOf("Speaker 2" to floatArrayOf(0.6f, 0.8f)))
        assertEquals(setOf("Speaker 2"), s.noteCentroids(7).keys)
        assertTrue(s.noteCentroids(8).isEmpty())
        s.deleteNoteCentroids(7)
        assertTrue(s.noteCentroids(7).isEmpty())
    }

    @Test
    fun `saving nothing or for no note writes nothing`() = runBlocking {
        val s = store()
        s.saveNoteCentroids(0, mapOf("a" to floatArrayOf(1f)))
        s.saveNoteCentroids(5, emptyMap())
        assertFalse(File(tmp.root, "voiceprints").exists())
    }

    @Test
    fun `clearAll removes every voice and every note's centroids`() = runBlocking {
        val s = store()
        s.update { it + person("a", "Priya") }
        s.saveNoteCentroids(1, mapOf("Speaker 1" to floatArrayOf(1f)))
        s.clearAll()
        assertTrue(s.people().isEmpty())
        assertTrue(s.noteCentroids(1).isEmpty())
    }
}
