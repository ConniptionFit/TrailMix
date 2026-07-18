package com.trailmix.app.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX-06 (v1.7.0): DataStore JSON codec for user-defined recipes. */
class RecipesJsonTest {

    @Test
    fun `recipes round-trip through JSON`() {
        val recipes = listOf(
            Recipe("Status update", "Write a 3-sentence status update from this note."),
            Recipe("Poem", "Turn this note into a limerick — with \"quotes\" and — dashes."),
        )
        assertEquals(recipes, RecipesJson.decode(RecipesJson.encode(recipes)))
    }

    @Test
    fun `decode of null, blank, or malformed JSON returns empty`() {
        assertTrue(RecipesJson.decode(null).isEmpty())
        assertTrue(RecipesJson.decode("").isEmpty())
        assertTrue(RecipesJson.decode("not json").isEmpty())
        assertTrue(RecipesJson.decode("{\"broken\":").isEmpty())
    }

    @Test
    fun `entries missing a name or prompt are skipped, not crashed on`() {
        val json = """[{"n":"Valid","p":"Do the thing"},{"n":"","p":"orphan prompt"},{"n":"orphan name","p":""},{"x":1}]"""
        assertEquals(listOf(Recipe("Valid", "Do the thing")), RecipesJson.decode(json))
    }

    @Test
    fun `encoded names and prompts survive whitespace trimming on decode`() {
        val decoded = RecipesJson.decode("""[{"n":"  Spaced  ","p":"  prompt  "}]""")
        assertEquals(listOf(Recipe("Spaced", "prompt")), decoded)
    }
}
