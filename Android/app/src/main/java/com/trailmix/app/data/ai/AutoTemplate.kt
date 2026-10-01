package com.trailmix.app.data.ai

import com.trailmix.app.data.model.SummaryTemplate
import java.util.Locale

/**
 * AI-20: the "Auto" template — picks the best built-in [SummaryTemplate] for a meeting from
 * cheap signals, so a new install gets a sensible shape without anyone choosing a template.
 * Rule-based on purpose (works with no model, deterministic, testable); the caller resolves
 * [SummaryTemplate.AUTO] with this at merge time and keeps storing "AUTO" on the note so a
 * regenerate re-resolves against the then-current title / notes.
 *
 * Signals, in precedence order: the calendar/meeting title, then the first line of the typed
 * notes (a user who types "# Standup" has said what this is; a casual "talk to Bob" later in
 * the notes must not retype the meeting, so only the first line counts), then a two-person
 * attendee list (a 1:1), else [SummaryTemplate.NONE]. Within one text the rules are checked
 * in the order below, so the more specific "interview debrief" beats plain "interview".
 *
 * Pure and Android-free.
 */
object AutoTemplate {

    fun resolve(meetingTitle: String?, attendeeCount: Int, typedFragments: String): SummaryTemplate {
        matchText(meetingTitle)?.let { return it }
        matchText(typedFragments.lineSequence().firstOrNull { it.isNotBlank() })?.let { return it }
        return if (attendeeCount == 2) SummaryTemplate.ONE_ON_ONE else SummaryTemplate.NONE
    }

    private fun matchText(text: String?): SummaryTemplate? {
        if (text.isNullOrBlank()) return null
        val t = text.lowercase(Locale.ROOT)
        return RULES.firstOrNull { (regex, _) -> regex.containsMatchIn(t) }?.second
    }

    private val RULES: List<Pair<Regex, SummaryTemplate>> = listOf(
        Regex("""(?<![\d:])1\s*[:\-]\s*1(?![\d:])|\bone[ -]on[ -]one\b|\b1on1\b""") to SummaryTemplate.ONE_ON_ONE,
        Regex("""\bstand[ -]?up\b""") to SummaryTemplate.WEEKLY_STANDUP,
        Regex("""\binterview\b.*\bdebrief\b|\bdebrief\b.*\binterview\b""") to SummaryTemplate.INTERVIEW_DEBRIEF,
        Regex("""\binterview\b|\buser research\b""") to SummaryTemplate.USER_INTERVIEW,
        Regex("""\bpitch\b|\binvestor\b""") to SummaryTemplate.PITCH,
        Regex("""\bdiscovery\b|\bdemo\b|\bsales\b""") to SummaryTemplate.CUSTOMER_DISCOVERY,
        Regex("""\bkick[ -]?off\b""") to SummaryTemplate.PROJECT_KICKOFF,
        Regex("""\bpipeline\b""") to SummaryTemplate.PIPELINE_REVIEW,
        Regex("""\bteam meeting\b|\bweekly sync\b|\ball[ -]hands\b""") to SummaryTemplate.TEAM_MEETING,
        Regex("""\btalk\b|\bkeynote\b|\bconference\b|\bwebinar\b""") to SummaryTemplate.PRESENTATION,
    )
}
