package com.trailmix.app.data.ai

import com.trailmix.app.data.model.SummaryTemplate
import org.junit.Assert.assertEquals
import org.junit.Test

/** AI-20: keyword rules, precedence and fallback of the Auto template. */
class AutoTemplateTest {

    private fun title(t: String, attendees: Int = 0, typed: String = "") =
        AutoTemplate.resolve(t, attendees, typed)

    @Test
    fun `each title rule picks its template`() {
        assertEquals(SummaryTemplate.ONE_ON_ONE, title("Alex / Sam 1:1"))
        assertEquals(SummaryTemplate.ONE_ON_ONE, title("1-1 with Priya"))
        assertEquals(SummaryTemplate.ONE_ON_ONE, title("One on one"))
        assertEquals(SummaryTemplate.WEEKLY_STANDUP, title("Daily Stand-up"))
        assertEquals(SummaryTemplate.WEEKLY_STANDUP, title("Eng standup"))
        assertEquals(SummaryTemplate.USER_INTERVIEW, title("User research session"))
        assertEquals(SummaryTemplate.USER_INTERVIEW, title("Customer interview"))
        assertEquals(SummaryTemplate.PITCH, title("Acme pitch"))
        assertEquals(SummaryTemplate.PITCH, title("Investor update"))
        assertEquals(SummaryTemplate.CUSTOMER_DISCOVERY, title("Discovery call - Globex"))
        assertEquals(SummaryTemplate.CUSTOMER_DISCOVERY, title("Product demo"))
        assertEquals(SummaryTemplate.CUSTOMER_DISCOVERY, title("Sales sync"))
        assertEquals(SummaryTemplate.PROJECT_KICKOFF, title("Atlas kickoff"))
        assertEquals(SummaryTemplate.PROJECT_KICKOFF, title("Kick-off"))
        assertEquals(SummaryTemplate.PIPELINE_REVIEW, title("Pipeline review"))
        assertEquals(SummaryTemplate.TEAM_MEETING, title("Weekly team meeting"))
        assertEquals(SummaryTemplate.TEAM_MEETING, title("Company all-hands"))
        assertEquals(SummaryTemplate.TEAM_MEETING, title("Weekly sync"))
        assertEquals(SummaryTemplate.PRESENTATION, title("Kubernetes keynote"))
        assertEquals(SummaryTemplate.PRESENTATION, title("Tech talk: caching"))
        assertEquals(SummaryTemplate.PRESENTATION, title("Webinar on privacy"))
    }

    @Test
    fun `interview debrief beats plain interview`() {
        assertEquals(SummaryTemplate.INTERVIEW_DEBRIEF, title("Interview debrief - Jordan"))
        assertEquals(SummaryTemplate.INTERVIEW_DEBRIEF, title("Debrief: panel interview"))
    }

    @Test
    fun `earlier rules win when a title matches several`() {
        assertEquals(SummaryTemplate.ONE_ON_ONE, title("1:1 standup"))
        assertEquals(SummaryTemplate.USER_INTERVIEW, title("Interview kickoff"))
        assertEquals(SummaryTemplate.PITCH, title("Sales pitch"))
    }

    @Test
    fun `matching is case-insensitive and whole-word`() {
        assertEquals(SummaryTemplate.WEEKLY_STANDUP, title("STANDUP"))
        assertEquals(SummaryTemplate.NONE, title("Talking points review"))
        assertEquals(SummaryTemplate.NONE, title("Meet at 11:30"))
    }

    @Test
    fun `typed first line is a fallback signal but later lines are not`() {
        assertEquals(SummaryTemplate.WEEKLY_STANDUP, AutoTemplate.resolve(null, 0, "# Standup\n- shipped login"))
        assertEquals(SummaryTemplate.WEEKLY_STANDUP, AutoTemplate.resolve("", 0, "\n\nStandup notes"))
        assertEquals(SummaryTemplate.NONE, AutoTemplate.resolve(null, 0, "Budget review\nneed to talk to Bob\nkeynote slides"))
    }

    @Test
    fun `title takes precedence over typed notes`() {
        assertEquals(SummaryTemplate.PITCH, AutoTemplate.resolve("Series A pitch", 0, "standup"))
    }

    @Test
    fun `two attendees with no other match is a 1 on 1`() {
        assertEquals(SummaryTemplate.ONE_ON_ONE, AutoTemplate.resolve("Catch up", 2, ""))
        assertEquals(SummaryTemplate.ONE_ON_ONE, AutoTemplate.resolve(null, 2, "random notes"))
        assertEquals(SummaryTemplate.PITCH, AutoTemplate.resolve("Pitch", 2, ""))
    }

    @Test
    fun `falls back to flat`() {
        assertEquals(SummaryTemplate.NONE, AutoTemplate.resolve(null, 0, ""))
        assertEquals(SummaryTemplate.NONE, AutoTemplate.resolve("Budget review", 5, "numbers"))
        assertEquals(SummaryTemplate.NONE, AutoTemplate.resolve("Catch up", 3, ""))
    }
}
