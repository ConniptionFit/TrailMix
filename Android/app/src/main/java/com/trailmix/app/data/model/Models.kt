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
data class TranscriptLine(
    val label: String,
    val text: String,
    val speakerLabel: String? = null,
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
) {
    NONE(
        "Flat (no template)",
        "Group the discussion into a few clearly-named topic sections and keep any tasks " +
            "or commitments in a separate Action Items list.",
    ),
    ONE_ON_ONE(
        "1:1",
        "This is a 1:1 — prefer sections like Wins, Challenges, Career/Growth, Feedback.",
    ),
    WEEKLY_STANDUP(
        "Weekly Standup",
        "This is a team standup — prefer sections like Done, In Progress, Blockers, Next Up.",
    ),
    LEARNING(
        "Learning",
        "This is a learning session (talk, seminar, Lunch & Learn, vendor demo, deep dive) — " +
            "prefer sections like Speakers, Key Takeaways, Learning Points, Follow-up Resources.",
        SummaryStyle.PRESENTATION,
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
        "This is a user interview — prefer sections like Background, Pain Points, Feature Requests, Quotes.",
    ),
    ;

    companion object {
        fun fromStored(value: String?): SummaryTemplate =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}

/** A user-defined summary template (AI-03): a display name plus the guidance sentence that
 * steers the structuring prompt, exactly like a built-in's [SummaryTemplate.guidance]. */
data class CustomSummaryTemplate(val name: String, val guidance: String)

/**
 * JSON codec for the user-defined template list (AI-03) — DataStore-backed, same fail-soft
 * decode contract as the other codecs here: malformed input returns an empty list, entries
 * missing either field are skipped.
 */
object CustomTemplatesJson {
    fun encode(templates: List<CustomSummaryTemplate>): String {
        val arr = JSONArray()
        templates.forEach { arr.put(JSONObject().put("n", it.name).put("g", it.guidance)) }
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
                if (name.isBlank() || guidance.isBlank()) null else CustomSummaryTemplate(name, guidance)
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
)

/** A topic-grouped block of bullets in the structured summary body. */
data class SummarySection(
    val heading: String,
    val bullets: List<SummaryBullet>,
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
)

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
        }

    private fun bulletFromJson(o: JSONObject) = SummaryBullet(
        text = o.getString("t"),
        source = runCatching { Provenance.valueOf(o.getString("s")) }.getOrDefault(Provenance.TRANSCRIPT),
        sourceExcerpt = o.optString("e").takeIf { it.isNotBlank() },
        timestampLabel = o.optString("ts").takeIf { it.isNotBlank() },
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
            sections.put(JSONObject().put("heading", section.heading).put("bullets", bullets))
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
                    SummarySection(heading = o.getString("heading"), bullets = bullets)
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
                    )
                }
            }.orEmpty()

            if (highlights.isEmpty() && sections.isEmpty() && actionItems.isEmpty()) return null
            StructuredSummary(highlights = highlights, sections = sections, actionItems = actionItems)
        }.getOrNull()
    }
}
