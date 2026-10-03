package com.trailmix.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/** Where a merged-note segment came from — drives the amber/teal source tinting. */
enum class Provenance { FRAGMENT, TRANSCRIPT }

/** One sentence/clause of a merged note, tagged with its source. */
data class NoteSegment(
    val text: String,
    val source: Provenance,
)

/** One finalized line of the live transcript. `label` is a mm:ss capture offset.
 *  [speakerLabel] (AI-01, v1.20.0) is "Speaker N" from on-device diarization when the user has
 *  opted in — null on every note recorded before that, and on any note where diarization
 *  wasn't enabled or found no segments. */
/** CAP-31: which capture lane dominated an utterance — the mic ("Me") or device audio ("Them"). */
enum class SpeechSource(val displayName: String) {
    ME("Me"),
    THEM("Them"),
    ;

    companion object {
        /** Tolerant parse for stored JSON: unknown or missing -> null. */
        fun fromStored(value: String?): SpeechSource? = entries.firstOrNull { it.name == value }
    }
}

data class TranscriptLine(
    val label: String,
    val text: String,
    val speakerLabel: String? = null,
    /** CAP-31: lane attribution; null for mic-only captures or ambiguous audio. */
    val speechSource: SpeechSource? = null,
)

object SegmentsJson {
    fun encode(segments: List<NoteSegment>): String {
        val arr = JSONArray()
        segments.forEach {
            arr.put(JSONObject().put("t", it.text).put("s", it.source.name))
        }
        return arr.toString()
    }

    fun decode(json: String): List<NoteSegment> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NoteSegment(
                text = o.getString("t"),
                source = runCatching { Provenance.valueOf(o.getString("s")) }
                    .getOrDefault(Provenance.TRANSCRIPT),
            )
        }
    }.getOrDefault(emptyList())
}

object TranscriptJson {
    fun encode(lines: List<TranscriptLine>): String {
        val arr = JSONArray()
        lines.forEach {
            arr.put(
                JSONObject().put("l", it.label).put("t", it.text).apply {
                    it.speakerLabel?.let { sp -> put("sp", sp) }
                    it.speechSource?.let { src -> put("src", src.name) }
                },
            )
        }
        return arr.toString()
    }

    fun decode(json: String): List<TranscriptLine> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TranscriptLine(
                label = o.optString("l"),
                text = o.getString("t"),
                speakerLabel = o.optString("sp").takeIf { it.isNotBlank() },
                speechSource = SpeechSource.fromStored(o.optString("src").takeIf { it.isNotBlank() }),
            )
        }
    }.getOrDefault(emptyList())
}

/** JSON-encoded List<String> — reused for meeting attendee names and Settings name-variant aliases. */
object StringListJson {
    fun encode(items: List<String>): String {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    fun decode(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}

/**
 * How a session is shaped, which decides how the *deterministic* structurer treats it (AI-05).
 *
 * This is not cosmetic. In a [DISCUSSION] the participants commit to things, so imperative
 * language ("we'll send that", "you need to") is genuinely an action item. In a
 * [PRESENTATION] the same language is the speaker teaching — "you should index that column",
 * "let's look at the next benchmark" — and treating it as a task both floods the action list
 * and, because cue-matched sentences are lifted *out* of the topic sections, guts the notes.
 * Under [PRESENTATION] only the listener's own typed commitments and explicit spoken markers
 * ("action item", "to-do") count.
 */
enum class SummaryStyle { DISCUSSION, PRESENTATION }

/**
 * Granola-style default meeting context shared by [SummaryTemplate.AUTO] and
 * [SummaryTemplate.NONE] ("General"): the note is the user's own, organised by topic, anchored on
 * what they typed, ending in owner-tagged next steps. Two sentences — Nano's context is ~8k chars.
 */
const val GENERAL_MEETING_CONTEXT =
    "General meeting notes for the note-taker, grouped by topic and anchored on their own typed " +
        "notes. Concise and specific, ending in next steps with an owner."

/**
 * Pre-generated structuring templates that steer [StructuredSummary] generation (UX-02).
 * UX-05 (v1.7.0): LEARNING replaced SALES_PITCH — old notes that stored "SALES_PITCH"
 * still load fine ([fromStored] falls back to NONE for any retired/unknown value; the
 * stored string on the note is never rewritten).
 * AI-03 (v1.8.0): [guidance] — the sentence spliced into the structuring prompt — moved
 * here from OnDeviceAiProcessor so Settings can show each template's actual logic, and
 * user-defined templates ([CustomSummaryTemplate]) now sit alongside these built-ins via
 * [TemplateOption].
 * AI-05 (v1.10.0): [style] — templates now also steer the zero-AI deterministic path, so
 * picking "Conference talk" changes the note even on a device with no Gemini Nano. Before
 * this, `guidance` was spliced into the model prompt and nowhere else, which meant template
 * choice was silently inert on exactly the fallback path the app promises to work on.
 */
enum class SummaryTemplate(
    val label: String,
    val guidance: String,
    val style: SummaryStyle = SummaryStyle.DISCUSSION,
    /** AI-19: required headings, in order (without Next Steps — always appended by the renderer). */
    val sections: List<SectionSpec> = emptyList(),
) {
    /**
     * AI-20: not a real template — [com.trailmix.app.data.ai.AutoTemplate] resolves it to one
     * of the others at merge time (the note keeps storing "AUTO" so a regenerate re-resolves).
     * Used unresolved, it behaves as [NONE].
     */
    AUTO(
        "Auto",
        GENERAL_MEETING_CONTEXT,
    ),
    NONE(
        "General",
        GENERAL_MEETING_CONTEXT,
    ),
    ONE_ON_ONE(
        "1:1",
        "Weekly 1:1 between the note-taker and their report. Capture what the report raised " +
            "first, then the note-taker's topics. Note feedback in both directions and any career-growth thread.",
        sections = listOf(
            SectionSpec("Their Topics", "what the other person raised, in their words"),
            SectionSpec("My Topics", "what the note-taker raised"),
            SectionSpec("Blockers", "anything stuck and who can unblock it"),
            SectionSpec("Feedback", "feedback given in either direction"),
            SectionSpec("Growth", "career, skills and development threads"),
        ),
    ),
    WEEKLY_STANDUP(
        "Weekly Standup",
        "Team stand-up. Very brief: one line per person.",
        sections = listOf(
            SectionSpec("Updates", "Name: done / doing"),
            SectionSpec("Blockers", "what is blocked and who unblocks it"),
        ),
    ),
    LEARNING(
        "Learning",
        "This is a learning session (talk, seminar, Lunch & Learn, vendor demo, deep dive). " +
            "Capture the substance: claims, numbers, definitions, named tools.",
        SummaryStyle.PRESENTATION,
        sections = listOf(
            SectionSpec("Speakers", "who presented and their role"),
            SectionSpec("Key Takeaways", "the few points worth remembering"),
            SectionSpec("Learning Points", "concepts, numbers and definitions in the order covered"),
            SectionSpec("Follow-up Resources", "tools, papers, links or people to look up"),
        ),
    ),
    PRESENTATION(
        "Conference talk",
        "This is a conference talk or presentation with one speaker and an audience — the " +
            "listener is taking notes, not participating. Track the argument as it develops: " +
            "prefer sections named for the topic being covered, in the order the speaker " +
            "covered them. Capture claims, numbers, definitions, and named tools or papers. " +
            "Treat the speaker's instructional phrasing (\"you should\", \"let's look at\") as " +
            "content, NOT as action items — only the listener's own typed commitments and " +
            "explicitly announced action items belong in the Action Items list.",
        SummaryStyle.PRESENTATION,
    ),
    USER_INTERVIEW(
        "User Interview",
        "This is a user interview; I am trying to understand what the user thinks about my " +
            "product. Focus on what they said, not what I said. Include quotes and numbers.",
        sections = listOf(
            SectionSpec("Participant Background", "who they are and how they use the product"),
            SectionSpec("Key Insights", "the most important things learned"),
            SectionSpec("Pain Points", "problems and frustrations, in their words"),
            SectionSpec("Notable Quotes", "verbatim quotes worth keeping"),
            SectionSpec("Product Feedback", "reactions to features and the product"),
            SectionSpec("Follow-ups", "questions to ask next or things to check"),
        ),
    ),
    CUSTOMER_DISCOVERY(
        "Customer Discovery",
        "Discovery call with a prospect. Diagnose before prescribing. Prioritize their words, " +
            "exact figures and quotes. Do not guess.",
        sections = listOf(
            SectionSpec("Company Context", "who they are, team size, what they do"),
            SectionSpec("Current Workflow", "how they do it today and workarounds"),
            SectionSpec("Pain Points", "problems and their impact"),
            SectionSpec("Decision Process and Stakeholders", "who decides, budget and timing"),
            SectionSpec("Objections", "concerns raised, resolved or open"),
        ),
    ),
    PITCH(
        "Pitch (investor)",
        "I am an investor evaluating this company; these notes inform an investment decision. " +
            "Capture exact figures and claims.",
        sections = listOf(
            SectionSpec("Company and Product", "what they build and for whom"),
            SectionSpec("Team", "founders and key hires"),
            SectionSpec("Market", "market size and competition"),
            SectionSpec("Traction and Metrics", "revenue, growth, retention, users"),
            SectionSpec("Business Model", "how they make money"),
            SectionSpec("Round Terms", "raise amount, valuation, use of funds"),
            SectionSpec("Concerns and Risks", "doubts and red flags"),
            SectionSpec("Open Questions", "what still needs answering"),
        ),
    ),
    PROJECT_KICKOFF(
        "Project Kick-Off",
        "Project kick-off meeting. Record what the team aligned on: scope, owners and dates.",
        sections = listOf(
            SectionSpec("Goals", "what success looks like"),
            SectionSpec("Scope", "what is in and out"),
            SectionSpec("Roles and Owners", "who is responsible for what"),
            SectionSpec("Timeline and Milestones", "dates and phases"),
            SectionSpec("Risks", "what could go wrong"),
        ),
    ),
    TEAM_MEETING(
        "Weekly Team Meeting",
        "Weekly team status meeting. Record decisions together with their rationale.",
        sections = listOf(
            SectionSpec("Updates", "status from each person or area"),
            SectionSpec("Decisions", "what was decided and why"),
            SectionSpec("Discussion Points", "topics debated"),
            SectionSpec("Open Questions", "unresolved items"),
        ),
    ),
    INTERVIEW_DEBRIEF(
        "Interview Debrief",
        "Hiring debrief about a candidate. Evidence-based assessment: examples, not impressions.",
        sections = listOf(
            SectionSpec("Candidate Background", "role, history and context"),
            SectionSpec("Experience Examples", "concrete examples they gave"),
            SectionSpec("Strengths", "what stood out positively"),
            SectionSpec("Concerns", "doubts or gaps"),
            SectionSpec("Compensation and Notice", "expectations, range, notice period"),
            SectionSpec("Recommendation", "hire / no hire and why"),
        ),
    ),
    PIPELINE_REVIEW(
        "Pipeline Review",
        "Sales pipeline review. Emphasize deal progression and risk.",
        sections = listOf(
            SectionSpec("Deals by Stage", "Deal: stage, value, change since last week"),
            SectionSpec("At-Risk Deals", "deal and the reason it is at risk"),
            SectionSpec("Forecast Changes", "movements in the forecast"),
        ),
    ),
    ;

    /** AI-19: the context + sections bundle the prompt builder and deterministic path consume. */
    val spec: TemplateSpec get() = TemplateSpec(guidance, sections)

    companion object {
        fun fromStored(value: String?): SummaryTemplate =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}

/** A user-defined summary template (AI-03): a display name plus the guidance sentence that
 * steers the structuring prompt, exactly like a built-in's [SummaryTemplate.guidance]. */
data class CustomSummaryTemplate(
    val name: String,
    val guidance: String,
    /** AI-19: optional required sections; empty = a context-only template, exactly as before. */
    val sections: List<SectionSpec> = emptyList(),
) {
    val spec: TemplateSpec get() = TemplateSpec(guidance, sections)
}

/**
 * JSON codec for the user-defined template list (AI-03) — DataStore-backed, same fail-soft
 * decode contract as the other codecs here: malformed input returns an empty list, entries
 * missing either field are skipped.
 */
object CustomTemplatesJson {
    fun encode(templates: List<CustomSummaryTemplate>): String {
        val arr = JSONArray()
        templates.forEach { t ->
            val o = JSONObject().put("n", t.name).put("g", t.guidance)
            // AI-19: additive "s" key, written only when there are sections, so a context-only
            // template serializes exactly as it did before.
            if (t.sections.isNotEmpty()) {
                val sarr = JSONArray()
                t.sections.forEach { sarr.put(JSONObject().put("h", it.heading).put("i", it.instruction)) }
                o.put("s", sarr)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(json: String?): List<CustomSummaryTemplate> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("n").trim()
                val guidance = o.optString("g").trim()
                if (name.isBlank() || guidance.isBlank()) return@mapNotNull null
                val sections = o.optJSONArray("s")?.let { sarr ->
                    (0 until sarr.length()).mapNotNull { j ->
                        val so = sarr.optJSONObject(j) ?: return@mapNotNull null
                        val heading = so.optString("h").trim()
                        if (heading.isBlank()) null else SectionSpec(heading, so.optString("i").trim())
                    }
                }.orEmpty()
                CustomSummaryTemplate(name, guidance, sections)
            }
        }.getOrDefault(emptyList())
    }
}

/**
 * One selectable summary template — a built-in [SummaryTemplate] entry or a user-defined
 * [CustomSummaryTemplate] (AI-03). [stored] is what gets persisted on the note and as the
 * Settings default: the enum name for built-ins, `"custom:<name>"` for customs (the prefix
 * can never collide with an enum name, and [SummaryTemplate.fromStored] already treats any
 * unknown value as NONE, so old code paths stay safe).
 */
data class TemplateOption(
    val stored: String,
    val label: String,
    val guidance: String,
    val isCustom: Boolean,
)

object TemplateOptions {
    private const val CUSTOM_PREFIX = "custom:"

    fun customStored(name: String): String = CUSTOM_PREFIX + name

    fun builtIns(): List<TemplateOption> =
        SummaryTemplate.entries.map { TemplateOption(it.name, it.label, it.guidance, isCustom = false) }

    /** Built-ins first, then the user's custom templates in saved order. */
    fun all(customs: List<CustomSummaryTemplate>): List<TemplateOption> =
        builtIns() + customs.map { TemplateOption(customStored(it.name), it.name, it.guidance, isCustom = true) }

    /**
     * Resolve a stored template value to the guidance sentence for the structuring prompt.
     * Unknown values — including a custom template deleted after being set — fall back to
     * [SummaryTemplate.NONE]'s guidance, same contract as [SummaryTemplate.fromStored].
     */
    fun guidanceFor(stored: String?, customs: List<CustomSummaryTemplate>): String {
        if (stored != null && stored.startsWith(CUSTOM_PREFIX)) {
            val name = stored.removePrefix(CUSTOM_PREFIX)
            customs.firstOrNull { it.name == name }?.let { return it.guidance }
            return SummaryTemplate.NONE.guidance
        }
        return SummaryTemplate.fromStored(stored).guidance
    }

    /**
     * Resolve a stored template value to the [SummaryStyle] the deterministic structurer
     * should use (AI-05). Custom templates are always [SummaryStyle.DISCUSSION]: their
     * guidance is free text meant for the model, and inferring intent from it would be a
     * guess. Unknown/retired values degrade to the built-in default, same contract as
     * [guidanceFor].
     */
    fun styleFor(stored: String?, customs: List<CustomSummaryTemplate>): SummaryStyle {
        if (stored != null && stored.startsWith(CUSTOM_PREFIX)) return SummaryStyle.DISCUSSION
        return SummaryTemplate.fromStored(stored).style
    }

    /**
     * AI-19: resolve a stored value to its full [TemplateSpec] (context + sections). Same
     * degrade contract as [guidanceFor]: a deleted custom template or an unknown/retired value
     * yields [SummaryTemplate.NONE]'s context-only spec. A custom template with no sections is
     * context-only, i.e. behaves exactly as it did before AI-19. [SummaryTemplate.AUTO] must be
     * resolved by the caller first (AI-20); unresolved it is the same as NONE.
     */
    fun specFor(stored: String?, customs: List<CustomSummaryTemplate>): TemplateSpec {
        if (stored != null && stored.startsWith(CUSTOM_PREFIX)) {
            val name = stored.removePrefix(CUSTOM_PREFIX)
            return customs.firstOrNull { it.name == name }?.spec ?: SummaryTemplate.NONE.spec
        }
        return SummaryTemplate.fromStored(stored).spec
    }
}

/** One bullet in a structured summary, with the provenance excerpt it was attributed from. */
data class SummaryBullet(
    val text: String,
    val source: Provenance,
    /** The original transcript/fragment sentence this bullet was distilled from, if found. */
    val sourceExcerpt: String? = null,
    /**
     * `mm:ss` capture offset of the transcript line this came from (AI-05) — null for bullets
     * from typed fragments (which have no position in the audio) and for AI-distilled bullets
     * that couldn't be traced to one line. Renders as a source annotation on screen and in the
     * exported Markdown, and is what makes a claim in a 45-minute talk findable again.
     */
    val timestampLabel: String? = null,
    /**
     * AI-18: optional one-level sub-bullets (supporting detail under the main point). Empty for
     * every pre-AI-18 note and for deterministic bullets; stored under the additive `"d"` key.
     */
    val details: List<String> = emptyList(),
    /**
     * UX redesign (N5): true once the user has changed the wording by hand. Provenance
     * ([source]) is kept, so an edited spoken line stays teal and is labelled "edited by you".
     * Stored under the additive `"ed"` key; old notes decode false.
     */
    val edited: Boolean = false,
)

/** A topic-grouped block of bullets in the structured summary body. */
data class SummarySection(
    val heading: String,
    val bullets: List<SummaryBullet>,
    /** True once the user renamed the heading (counts toward the rebuild warning). */
    val edited: Boolean = false,
)

/** An action item isolated from the rest of the summary — owner/deadline only when statable. */
data class ActionItem(
    val text: String,
    val owner: String? = null,
    val deadline: String? = null,
    val source: Provenance = Provenance.TRANSCRIPT,
    val sourceExcerpt: String? = null,
    /** `mm:ss` capture offset of the originating transcript line, if any (AI-05). */
    val timestampLabel: String? = null,
    /** UX redesign (N1): the Next Steps checkbox. Stored under the additive `"x"` key. */
    val done: Boolean = false,
    /** True once the user changed this step by hand (counts toward the rebuild warning). */
    val edited: Boolean = false,
)

/**
 * AI-18: the Granola-style "Next Steps" line — `Owner: action (by deadline)`, falling back to the
 * plain action when there is no owner and/or deadline. Shared by the screen and the Markdown
 * export so the two cannot drift.
 */
fun ActionItem.displayText(): String = buildString {
    val who = owner?.trim()?.takeIf { it.isNotEmpty() }
    var action = text.trim()
    if (who != null) {
        append(who).append(": ")
        action = action.withoutLeadingOwner(who)
    }
    append(action)
    deadline?.takeIf { it.isNotBlank() }?.let { append(" (by ").append(it.trim()).append(')') }
}

/**
 * The model often restates the owner in the action ("Chloe to provide credentials") and the
 * "Owner:" prefix already says it. Only a whole leading word is dropped (not "Chloe's laptop"), and
 * a bare "to"/"will" left in front of the verb goes with it.
 */
private fun String.withoutLeadingOwner(owner: String): String {
    if (!startsWith(owner, ignoreCase = true)) return this
    val rest = substring(owner.length)
    if (rest.firstOrNull().let { it != ' ' && it != ':' && it != ',' }) return this
    val action = rest.trimStart(' ', ':', ',').removePrefix("to ").removePrefix("will ").trim()
    return if (action.isEmpty()) this else action.replaceFirstChar { it.uppercase() }
}

/**
 * AI-produced structured summary (UX-02): highlights near the top, bullets grouped by
 * topic, and an isolated action-items list. Null on a note means "no structure" — the
 * flat [NoteSegment] list is the only representation, same as before this feature.
 */
data class StructuredSummary(
    val highlights: List<SummaryBullet>,
    val sections: List<SummarySection>,
    val actionItems: List<ActionItem>,
)

/**
 * UX-04: reorder the topic sections — returns a copy with the section at [from] moved to
 * [to], or `this` unchanged when the move is a no-op or either index is out of range.
 * Highlights and action items keep their fixed positions (top/bottom); only the
 * topic-grouped middle is user-orderable.
 */
fun StructuredSummary.moveSection(from: Int, to: Int): StructuredSummary {
    if (from == to || from !in sections.indices || to !in sections.indices) return this
    val reordered = sections.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    return copy(sections = reordered)
}

object StructuredSummaryJson {
    private fun bulletToJson(b: SummaryBullet) = JSONObject()
        .put("t", b.text)
        .put("s", b.source.name)
        .apply {
            b.sourceExcerpt?.let { put("e", it) }
            b.timestampLabel?.let { put("ts", it) }
            if (b.details.isNotEmpty()) put("d", JSONArray().apply { b.details.forEach { put(it) } })
            if (b.edited) put("ed", true)
        }

    private fun bulletFromJson(o: JSONObject) = SummaryBullet(
        text = o.getString("t"),
        source = runCatching { Provenance.valueOf(o.getString("s")) }.getOrDefault(Provenance.TRANSCRIPT),
        sourceExcerpt = o.optString("e").takeIf { it.isNotBlank() },
        timestampLabel = o.optString("ts").takeIf { it.isNotBlank() },
        details = o.optJSONArray("d")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty(),
        edited = o.optBoolean("ed", false),
    )

    fun encode(summary: StructuredSummary): String {
        val root = JSONObject()
        val highlights = JSONArray()
        summary.highlights.forEach { highlights.put(bulletToJson(it)) }
        root.put("highlights", highlights)

        val sections = JSONArray()
        summary.sections.forEach { section ->
            val bullets = JSONArray()
            section.bullets.forEach { bullets.put(bulletToJson(it)) }
            sections.put(
                JSONObject().put("heading", section.heading).put("bullets", bullets)
                    .apply { if (section.edited) put("ed", true) },
            )
        }
        root.put("sections", sections)

        val actionItems = JSONArray()
        summary.actionItems.forEach { item ->
            actionItems.put(
                JSONObject()
                    .put("t", item.text)
                    .put("s", item.source.name)
                    .apply {
                        item.owner?.let { put("owner", it) }
                        item.deadline?.let { put("deadline", it) }
                        item.sourceExcerpt?.let { put("e", it) }
                        item.timestampLabel?.let { put("ts", it) }
                        if (item.done) put("x", true)
                        if (item.edited) put("ed", true)
                    },
            )
        }
        root.put("actionItems", actionItems)
        return root.toString()
    }

    fun decode(json: String?): StructuredSummary? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val highlights = root.optJSONArray("highlights")?.let { arr ->
                (0 until arr.length()).map { bulletFromJson(arr.getJSONObject(it)) }
            }.orEmpty()

            val sections = root.optJSONArray("sections")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val bullets = o.optJSONArray("bullets")?.let { barr ->
                        (0 until barr.length()).map { bulletFromJson(barr.getJSONObject(it)) }
                    }.orEmpty()
                    SummarySection(
                        heading = o.getString("heading"),
                        bullets = bullets,
                        edited = o.optBoolean("ed", false),
                    )
                }
            }.orEmpty()

            val actionItems = root.optJSONArray("actionItems")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ActionItem(
                        text = o.getString("t"),
                        owner = o.optString("owner").takeIf { it.isNotBlank() },
                        deadline = o.optString("deadline").takeIf { it.isNotBlank() },
                        source = runCatching { Provenance.valueOf(o.getString("s")) }
                            .getOrDefault(Provenance.TRANSCRIPT),
                        sourceExcerpt = o.optString("e").takeIf { it.isNotBlank() },
                        timestampLabel = o.optString("ts").takeIf { it.isNotBlank() },
                        done = o.optBoolean("x", false),
                        edited = o.optBoolean("ed", false),
                    )
                }
            }.orEmpty()

            if (highlights.isEmpty() && sections.isEmpty() && actionItems.isEmpty()) return null
            StructuredSummary(highlights = highlights, sections = sections, actionItems = actionItems)
        }.getOrNull()
    }
}
