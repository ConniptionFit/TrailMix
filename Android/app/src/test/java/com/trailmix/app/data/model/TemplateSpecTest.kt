package com.trailmix.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-19: section-based template shape, its JSON codec, and spec resolution. */
class TemplateSpecTest {

    @Test
    fun `custom template with sections round-trips through JSON`() {
        val templates = listOf(
            CustomSummaryTemplate(
                "Sales call",
                "A sales call.",
                listOf(SectionSpec("Needs", "what they need"), SectionSpec("Objections")),
            ),
            CustomSummaryTemplate("Plain", "Context only."),
        )
        assertEquals(templates, CustomTemplatesJson.decode(CustomTemplatesJson.encode(templates)))
    }

    @Test
    fun `context-only template serializes without a sections key`() {
        val json = CustomTemplatesJson.encode(listOf(CustomSummaryTemplate("Plain", "Context only.")))
        assertFalse(json.contains("\"s\""))
    }

    @Test
    fun `old JSON without sections decodes as context-only`() {
        val old = """[{"n":"Retro","g":"Prefer sections like Went Well."}]"""
        val decoded = CustomTemplatesJson.decode(old).single()
        assertEquals(emptyList<SectionSpec>(), decoded.sections)
        assertFalse(decoded.spec.hasSections)
    }

    @Test
    fun `blank-heading and malformed sections are skipped`() {
        val json = """[{"n":"T","g":"G","s":[{"h":"  ","i":"x"},{"h":"Kept","i":"why"},"junk",{"i":"no heading"}]}]"""
        assertEquals(listOf(SectionSpec("Kept", "why")), CustomTemplatesJson.decode(json).single().sections)
    }

    @Test
    fun `specFor resolves built-ins and customs`() {
        val customs = listOf(CustomSummaryTemplate("Sales call", "Ctx.", listOf(SectionSpec("Needs"))))
        val builtIn = TemplateOptions.specFor("ONE_ON_ONE", customs)
        assertEquals(SummaryTemplate.ONE_ON_ONE.guidance, builtIn.meetingContext)
        assertEquals(
            listOf("Their Topics", "My Topics", "Blockers", "Feedback", "Growth"),
            builtIn.sections.map { it.heading },
        )
        val custom = TemplateOptions.specFor("custom:Sales call", customs)
        assertEquals("Ctx.", custom.meetingContext)
        assertEquals(listOf(SectionSpec("Needs")), custom.sections)
        assertTrue(custom.omitEmptySections)
    }

    @Test
    fun `specFor degrades to the flat spec for deleted customs and unknown values`() {
        assertEquals(SummaryTemplate.NONE.spec, TemplateOptions.specFor("custom:Gone", emptyList()))
        assertEquals(SummaryTemplate.NONE.spec, TemplateOptions.specFor("SALES_PITCH", emptyList()))
        assertEquals(SummaryTemplate.NONE.spec, TemplateOptions.specFor(null, emptyList()))
        assertFalse(TemplateOptions.specFor(null, emptyList()).hasSections)
    }

    @Test
    fun `unresolved AUTO behaves as NONE`() {
        assertEquals(SummaryTemplate.NONE.spec, TemplateOptions.specFor("AUTO", emptyList()))
    }

    @Test
    fun `built-in section templates never list Next Steps and keep compact contexts`() {
        SummaryTemplate.entries.forEach { t ->
            assertTrue("${t.name} has Next Steps", t.sections.none { it.heading.contains("next steps", ignoreCase = true) })
            if (t != SummaryTemplate.PRESENTATION) {
                assertTrue("${t.name} context too long", t.guidance.length <= 330)
            }
        }
        val expectedNew = listOf(
            "CUSTOMER_DISCOVERY", "PITCH", "PROJECT_KICKOFF", "TEAM_MEETING", "INTERVIEW_DEBRIEF", "PIPELINE_REVIEW",
        )
        expectedNew.forEach { name ->
            assertTrue("$name missing sections", SummaryTemplate.fromStored(name).sections.isNotEmpty())
        }
    }

    @Test
    fun `presentation styles and AUTO first in the picker`() {
        assertEquals(SummaryStyle.PRESENTATION, SummaryTemplate.PRESENTATION.style)
        assertEquals(SummaryStyle.PRESENTATION, SummaryTemplate.LEARNING.style)
        assertEquals(SummaryTemplate.AUTO.name, TemplateOptions.builtIns().first().stored)
    }

    @Test
    fun `describe lists context then numbered sections`() {
        val text = TemplateSpec("Ctx.", listOf(SectionSpec("A", "do a"), SectionSpec("B"))).describe()
        assertEquals("Ctx.\n\nSections:\n1. A — do a\n2. B", text)
        assertEquals("Ctx.", TemplateSpec("Ctx.").describe())
    }
}
