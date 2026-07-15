package com.trailmix.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrLocalesTest {

    @Test
    fun `default is English US and stays first for backward compatibility`() {
        assertEquals("en-US", AsrLocales.default.tag)
        assertEquals("en-US", AsrLocales.options.first().tag)
    }

    @Test
    fun `fromTag resolves a known tag`() {
        assertEquals("fr-FR", AsrLocales.fromTag("fr-FR").tag)
    }

    @Test
    fun `fromTag falls back to default for null or unknown tag`() {
        assertEquals(AsrLocales.default, AsrLocales.fromTag(null))
        assertEquals(AsrLocales.default, AsrLocales.fromTag("xx-ZZ"))
    }

    @Test
    fun `every option resolves to a distinct locale`() {
        val locales = AsrLocales.options.map { AsrLocales.toLocale(it).toLanguageTag() }
        assertEquals(locales.size, locales.toSet().size)
        assertTrue(locales.isNotEmpty())
    }
}
