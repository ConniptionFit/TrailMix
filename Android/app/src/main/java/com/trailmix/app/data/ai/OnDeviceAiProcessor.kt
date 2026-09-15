package com.trailmix.app.data.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.SummaryStyle
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TranscriptLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

sealed class AiAvailability {
    data object Available : AiAvailability()
    data object Downloadable : AiAvailability()
    data object Downloading : AiAvailability()
    data class Unavailable(val reason: String) : AiAvailability()
}

data class MergeResult(
    val title: String,
    val segments: List<NoteSegment>,
    val usedOnDeviceAi: Boolean,
    /** Structured summary (UX-02) — null when AI is unavailable or structuring failed (deterministic flat fallback). */
    val structuredSummary: StructuredSummary? = null,
)

/**
 * All AI runs on this device via ML Kit GenAI (Gemini Nano / AICore).
 * Every entry point degrades to a deterministic result when the model is
 * missing or fails — the app never requires AI and never leaves the device
 * (the APK carries no INTERNET permission).
 */
@Singleton
class OnDeviceAiProcessor @Inject constructor() {

    private val generativeModel: GenerativeModel? by lazy {
        runCatching { Generation.getClient() }.getOrNull()
    }

    suspend fun checkAvailability(): AiAvailability = withContext(Dispatchers.Default) {
        val model = generativeModel
            ?: return@withContext AiAvailability.Unavailable("On-device AI client unavailable.")
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> AiAvailability.Available
                FeatureStatus.DOWNLOADABLE -> AiAvailability.Downloadable
                FeatureStatus.DOWNLOADING -> AiAvailability.Downloading
                else -> AiAvailability.Unavailable(
                    "On-device Gemini Nano is not available on this device.",
                )
            }
        } catch (e: Exception) {
            AiAvailability.Unavailable(e.message ?: "Unable to check on-device AI status.")
        }
    }

    suspend fun ensureModelReady(): AiAvailability = withContext(Dispatchers.Default) {
        when (val status = checkAvailability()) {
            is AiAvailability.Downloadable, is AiAvailability.Downloading -> {
                runCatching {
                    generativeModel?.download()?.collect { downloadStatus ->
                        if (downloadStatus is DownloadStatus.DownloadFailed) {
                            throw downloadStatus.e
                        }
                    }
                }
                checkAvailability()
            }
            else -> status
        }
    }

    /**
     * Merge typed fragments + transcript into a provenance-tagged note. When AI is
     * available, also attempts a structured summary (UX-02) — highlights, topic-grouped
     * sections, and an isolated action-items list, steered by [template] and given
     * [attendees] as participant context so the model can attribute statements and tasks
     * correctly. (The separate name-variants alias list was removed in v1.7.0, CAL-04 —
     * calendar attendees cover the in-person meeting case.) Structuring is best-effort and
     * independent of the flat title/segments result: any failure just leaves
     * [MergeResult.structuredSummary] null, never a half-built or bogus structure.
     *
     * AI-03 (v1.8.0): [templateGuidance] is the already-resolved guidance sentence — the
     * caller resolves the stored template value (built-in enum name or `custom:<name>`)
     * through [com.trailmix.app.data.model.TemplateOptions.guidanceFor], so this class no
     * longer knows or cares which template kind it came from.
     */
    suspend fun merge(
        typedFragments: String,
        transcript: List<TranscriptLine>,
        createdAtEpochMs: Long,
        attendees: List<String> = emptyList(),
        templateGuidance: String = SummaryTemplate.NONE.guidance,
        style: SummaryStyle = SummaryStyle.DISCUSSION,
        /**
         * REL-10: called as each transcript chunk finishes condensing, with the number done
         * and the total. A keynote-scale merge is a dozen sequential model calls; the caller
         * turns this into the progress the foreground notification shows so the wait doesn't
         * look like a hang. Called from a background dispatcher — never touch UI directly.
         */
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MergeResult = withContext(Dispatchers.Default) {
        // CAP-11 (v1.8.0): no transcript → nothing to merge or summarize. The typed notes
        // are saved verbatim via the deterministic path; the model is never invoked.
        if (!MergePolicy.hasTranscript(transcript)) {
            return@withContext fallbackMerge(typedFragments, transcript, createdAtEpochMs, style)
        }
        // AI-05: sample evenly across the whole session rather than taking the first
        // MAX_CONTEXT_CHARS. The old `.take()` meant a 45-minute talk was titled and
        // summarized entirely from its first ~10 minutes, with nothing marking the loss.
        val transcriptText = TranscriptCoverage.evenSample(transcript, MAX_CONTEXT_CHARS)
        val availability = ensureModelReady()
        if (availability !is AiAvailability.Available) {
            return@withContext fallbackMerge(typedFragments, transcript, createdAtEpochMs, style)
        }
        try {
            val prompt = """
                You are merging a user's rough typed notes with a call transcript into one
                clean, structured meeting note.
                Rules:
                - Prefer the typed notes when they conflict with the transcript.
                - Keep it factual and short; no invented details.
                - First line: a title of at most 8 words naming what this was about.
                  No markdown, no "Title:" label, no trailing period. Name the topic —
                  do not copy the transcript's opening sentence.
                - Then the note body as plain sentences, one thought per sentence.

                Typed notes:
                ${typedFragments.ifBlank { "(none)" }}

                Transcript:
                ${transcriptText.ifBlank { "(none)" }}
            """.trimIndent()

            val output = generate(prompt)
            val lines = output.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size < 2) return@withContext fallbackMerge(typedFragments, transcript, createdAtEpochMs, style)

            // AI-07: the prompt's "max 8 words, no markdown" is a request, not a constraint.
            // NoteTitle.clean is the constraint — it also falls back to the default title,
            // so no separate ifBlank guard is needed below.
            val title = NoteTitle.clean(lines.first(), createdAtEpochMs)
            val body = lines.drop(1).joinToString(" ")
            val segments = attributeProvenance(splitSentences(body), typedFragments, transcriptText)
            // Structured summary from the model, or the deterministic structurer as a net so
            // a bad/non-JSON model reply still yields sectioned output, never a flat wall.
            // The fallback reads the FULL transcript, not the sampled text the model saw.
            val structured = runCatching {
                generateStructuredSummary(typedFragments, transcript, attendees, templateGuidance, onProgress)
            }.getOrNull() ?: DeterministicSummary.from(typedFragments, transcript, style)

            MergeResult(
                title = title,
                segments = segments.ifEmpty {
                    fallbackMerge(typedFragments, transcript, createdAtEpochMs, style).segments
                },
                usedOnDeviceAi = true,
                structuredSummary = structured,
            )
        } catch (_: Exception) {
            fallbackMerge(typedFragments, transcript, createdAtEpochMs, style)
        }
    }

    /** Chat about a note; recipes are just saved prompts routed through here. */
    suspend fun chat(
        noteBody: String,
        transcript: String,
        history: List<Pair<String, String>>, // role to text
        userMessage: String,
        attendees: List<String> = emptyList(),
    ): String = withContext(Dispatchers.Default) {
        val availability = ensureModelReady()
        if (availability !is AiAvailability.Available) {
            return@withContext OFFLINE_ASSISTANT_REPLY
        }
        try {
            val prompt = buildString {
                appendLine("You are a concise assistant working with one meeting note.")
                appendLine("Answer using only the note and transcript below. Plain text only.")
                if (attendees.isNotEmpty()) {
                    appendLine("Meeting attendees: ${attendees.joinToString(", ")}.")
                }
                appendLine()
                appendLine("Note:")
                appendLine(noteBody.take(MAX_CONTEXT_CHARS))
                if (transcript.isNotBlank()) {
                    appendLine()
                    appendLine("Transcript:")
                    appendLine(transcript.take(MAX_CONTEXT_CHARS))
                }
                if (history.isNotEmpty()) {
                    appendLine()
                    appendLine("Conversation so far:")
                    history.takeLast(6).forEach { (role, text) ->
                        appendLine("$role: ${text.take(500)}")
                    }
                }
                appendLine()
                appendLine("user: $userMessage")
                append("assistant:")
            }
            generate(prompt).ifBlank { OFFLINE_ASSISTANT_REPLY }
        } catch (_: Exception) {
            OFFLINE_ASSISTANT_REPLY
        }
    }

    /**
     * Cross-note chat (AI-10): like [chat], but for a question spanning several notes at once.
     * [context] is pre-assembled by [CrossNoteRetrieval] — already relevance-ranked and
     * char-budgeted against the question — so unlike [chat]'s single-note `.take()`, this
     * never needs its own truncation; whatever didn't make the cut was already the least
     * relevant material, not just whatever sorted last.
     */
    suspend fun chatAcrossNotes(
        context: String,
        noteTitles: List<String>,
        history: List<Pair<String, String>>,
        userMessage: String,
    ): String = withContext(Dispatchers.Default) {
        val availability = ensureModelReady()
        if (availability !is AiAvailability.Available) {
            return@withContext OFFLINE_ASSISTANT_REPLY
        }
        try {
            val prompt = buildString {
                appendLine("You are a concise assistant working across ${noteTitles.size} meeting notes.")
                appendLine(
                    "Answer using only the excerpts below. If they don't cover the question, " +
                        "say so plainly rather than guessing. Plain text only.",
                )
                appendLine()
                appendLine("Notes: ${noteTitles.joinToString(", ")}")
                appendLine()
                appendLine("Relevant excerpts:")
                appendLine(context.ifBlank { "(none found)" })
                if (history.isNotEmpty()) {
                    appendLine()
                    appendLine("Conversation so far:")
                    history.takeLast(6).forEach { (role, text) ->
                        appendLine("$role: ${text.take(500)}")
                    }
                }
                appendLine()
                appendLine("user: $userMessage")
                append("assistant:")
            }
            generate(prompt).ifBlank { OFFLINE_ASSISTANT_REPLY }
        } catch (_: Exception) {
            OFFLINE_ASSISTANT_REPLY
        }
    }

    /**
     * AI-11: condense the transcript captured so far into a few live bullets, for the
     * in-progress capture screen. Independent of [merge] — no typed fragments, no title, no
     * provenance, just "what's been said so far" — and best-effort like every AI path here:
     * unavailable or erroring yields null rather than surfacing a half-built result. Input is
     * capped the same way [generateStructuredSummary]'s per-chunk calls are, so a long-running
     * capture never makes this call more expensive the longer the session runs.
     */
    suspend fun rollingSummary(
        transcript: List<TranscriptLine>,
        templateGuidance: String = SummaryTemplate.NONE.guidance,
    ): String? = withContext(Dispatchers.Default) {
        if (transcript.isEmpty()) return@withContext null
        val availability = ensureModelReady()
        if (availability !is AiAvailability.Available) return@withContext null
        val sample = TranscriptCoverage.evenSampleLines(transcript, CHUNK_CHARS)
        if (sample.isEmpty()) return@withContext null
        runCatching { condenseChunk(sample, templateGuidance) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    // ── Structured summary (UX-02) ───────────────────────────────────────────

    /**
     * AI-05: structure the note, reading the **whole** session rather than its first
     * MAX_CONTEXT_CHARS. When the transcript fits one model context this is a single call, as
     * before. When it doesn't — any talk over roughly ten minutes — it becomes map-reduce: each
     * chunk is condensed to timestamped bullets ([condenseChunk]), and the condensed set is
     * what gets structured. Per-chunk failures are skipped rather than fatal, so a session
     * still summarizes if one call misbehaves; only a total wipeout returns null and lets the
     * caller drop to [DeterministicSummary].
     */
    private suspend fun generateStructuredSummary(
        typedFragments: String,
        transcript: List<TranscriptLine>,
        attendees: List<String>,
        templateGuidance: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): StructuredSummary? {
        val chunks = TranscriptCoverage.chunks(transcript, CHUNK_CHARS)
        // REL-10: publish the shape of the work before any of it is done, so the notification
        // can switch from "merging" to a real N-of-M count immediately rather than after the
        // first chunk — which on a keynote is already a minute in.
        onProgress(0, chunks.size)
        val transcriptText = when {
            chunks.isEmpty() -> ""
            chunks.size == 1 -> labelled(chunks.single())
            else -> {
                val condensed = chunks.mapIndexedNotNull { index, chunk ->
                    runCatching { condenseChunk(chunk, templateGuidance) }.getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        // Reported for failed chunks too: a skipped chunk is still work
                        // finished, and a progress line that stalls on the one call that
                        // misbehaved is the hang it is meant to rule out.
                        .also { onProgress(index + 1, chunks.size) }
                }
                if (condensed.isEmpty()) return null
                condensed.joinToString("\n")
            }
        }

        val prompt = """
            You are structuring a meeting note into JSON. $templateGuidance
            ${if (attendees.isNotEmpty()) "Attendees: ${attendees.joinToString(", ")}." else ""}
            The transcript lines are prefixed with [mm:ss] timestamps. Begin every bullet and
            action item you produce with the [mm:ss] of the moment it came from, then the text.
            Respond with ONLY valid JSON, no markdown fences, matching exactly this shape:
            {"highlights": ["[mm:ss] short key decision or highlight", "..."],
             "sections": [{"heading": "Topic name", "bullets": ["[mm:ss] bullet text", "..."]}],
             "actionItems": [{"text": "[mm:ss] what needs doing", "owner": "name or null", "deadline": "date/phrase or null"}]}
            Rules: factual only, no invented details, omit owner/deadline (use null) when not statable,
            2-5 highlights, cover the WHOLE session end to end in 2-6 topic sections ordered as they
            occurred, action items only when real.

            Typed notes:
            ${typedFragments.ifBlank { "(none)" }}

            Transcript:
            ${transcriptText.ifBlank { "(none)" }}
        """.trimIndent()

        val raw = generate(prompt)
        val jsonText = raw.substringAfter('{', "").let { if (it.isBlank()) raw else "{$it" }
            .substringBeforeLast('}', "").let { if (it.isBlank()) raw else "$it}" }
        val root = JSONObject(jsonText)

        // Tokenize the attribution corpora once — a long talk has hundreds of candidate
        // sentences and this runs per produced bullet.
        val transcriptUnits = transcript.flatMap { line ->
            splitSentences(line.text).map { TranscriptLine(line.label, it) }
        }.map { it to QuoteMatch.tokenize(it.text) }
        val fragWords = QuoteMatch.tokenize(typedFragments)
        val transWords = transcriptUnits.flatMapTo(mutableSetOf()) { it.second }

        fun attribute(raw: String): SummaryBullet {
            val (stamp, text) = splitTimestamp(raw)
            val match = classify(text, fragWords, transWords, transcriptUnits)
            return SummaryBullet(
                text = text,
                source = match.source,
                sourceExcerpt = match.excerpt,
                timestampLabel = stamp ?: match.label,
            )
        }

        val highlights = root.optJSONArray("highlights")?.let { arr ->
            (0 until arr.length()).map { attribute(arr.getString(it).trim()) }
        }.orEmpty().filter { it.text.isNotBlank() }

        val sections = root.optJSONArray("sections")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val bullets = o.optJSONArray("bullets")?.let { barr ->
                    (0 until barr.length()).map { attribute(barr.getString(it).trim()) }
                }.orEmpty().filter { it.text.isNotBlank() }
                SummarySection(heading = o.getString("heading").trim(), bullets = bullets)
            }
        }.orEmpty().filter { it.bullets.isNotEmpty() }

        val actionItems = root.optJSONArray("actionItems")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val (stamp, text) = splitTimestamp(o.getString("text").trim())
                val match = classify(text, fragWords, transWords, transcriptUnits)
                ActionItem(
                    text = text,
                    owner = o.optString("owner").takeIf { it.isNotBlank() && it != "null" },
                    deadline = o.optString("deadline").takeIf { it.isNotBlank() && it != "null" },
                    source = match.source,
                    sourceExcerpt = match.excerpt,
                    timestampLabel = stamp ?: match.label,
                )
            }
        }.orEmpty().filter { it.text.isNotBlank() }

        if (highlights.isEmpty() && sections.isEmpty() && actionItems.isEmpty()) return null
        return StructuredSummary(highlights = highlights, sections = sections, actionItems = actionItems)
    }

    /** Map step: one chunk of a long session → a few timestamped factual bullets. */
    private suspend fun condenseChunk(chunk: List<TranscriptLine>, templateGuidance: String): String {
        val prompt = """
            Condense this section of a transcript into at most $BULLETS_PER_CHUNK short factual
            bullets. $templateGuidance
            Start each bullet with the [mm:ss] timestamp it came from, then the point itself.
            Plain text only, one bullet per line, no headings, no commentary.

            Transcript section:
            ${labelled(chunk)}
        """.trimIndent()
        return generate(prompt)
    }

    private fun labelled(lines: List<TranscriptLine>): String =
        lines.joinToString("\n") { if (it.label.isBlank()) it.text else "[${it.label}] ${it.text}" }

    /** Pull a leading `[mm:ss]` / `[h:mm:ss]` marker off a model-produced bullet. */
    private fun splitTimestamp(text: String): Pair<String?, String> {
        val match = LEADING_TIMESTAMP.find(text) ?: return null to text.removePrefix("-").trim()
        return match.groupValues[1] to text.removeRange(match.range).removePrefix("-").trim()
    }

    private data class Attribution(val source: Provenance, val excerpt: String?, val label: String?)

    /**
     * Classifies which side a bullet leans on, then backs it with the transcript quote that
     * best supports it (AI-09) — including when the bullet is fragment-sourced. Before this, a
     * fragment-sourced bullet's excerpt was the best-matching *other typed sentence*, which is
     * a self-quote: tapping "reveal source" on something the user typed just showed more of
     * what they typed. The useful reveal is always the spoken moment it relates to, if any.
     */
    private fun classify(
        text: String,
        fragWords: Set<String>,
        transWords: Set<String>,
        transcriptUnits: List<Pair<TranscriptLine, Set<String>>>,
    ): Attribution {
        val words = QuoteMatch.tokenize(text)
        val fragScore = QuoteMatch.overlapScore(words, fragWords)
        val transScore = QuoteMatch.overlapScore(words, transWords)
        val source = if (fragScore > transScore) Provenance.FRAGMENT else Provenance.TRANSCRIPT
        val best = QuoteMatch.bestOf(words, transcriptUnits)
        return Attribution(source, best?.text, best?.label?.takeIf { it.isNotBlank() })
    }

    private suspend fun generate(prompt: String): String =
        generativeModel
            ?.generateContent(prompt)
            ?.candidates
            ?.firstOrNull()
            ?.text
            ?.trim()
            .orEmpty()

    // ── Deterministic fallback path (no AI required) ────────────────────────

    private fun fallbackMerge(
        typedFragments: String,
        transcript: List<TranscriptLine>,
        createdAtEpochMs: Long,
        style: SummaryStyle = SummaryStyle.DISCUSSION,
    ): MergeResult {
        val segments = buildList {
            splitSentences(typedFragments).forEach { add(NoteSegment(it, Provenance.FRAGMENT)) }
            // AI-05: spread the flat body's transcript segments across the whole session
            // instead of taking the first 12 lines (~2 minutes of a talk).
            TranscriptCoverage.evenSampleLines(transcript, FLAT_BODY_CHARS)
                .map { it.text }
                .filter { it.isNotBlank() }
                .forEach { add(NoteSegment(it.trim(), Provenance.TRANSCRIPT)) }
        }
        // Even with no AI, structure the content deterministically so the default note is
        // sectioned + Action Items rather than a flat block (null → flat only for tiny notes).
        return MergeResult(
            title = defaultTitle(createdAtEpochMs),
            segments = segments.ifEmpty {
                listOf(NoteSegment("Empty capture.", Provenance.FRAGMENT))
            },
            usedOnDeviceAi = false,
            structuredSummary = DeterministicSummary.from(typedFragments, transcript, style),
        )
    }

    // ── Provenance attribution ──────────────────────────────────────────────

    /**
     * Tag each merged sentence by word overlap against the two source buffers.
     * Independent of model compliance — works even if the LLM ignores tagging
     * instructions, which small on-device models often do.
     */
    private fun attributeProvenance(
        sentences: List<String>,
        fragments: String,
        transcript: String,
    ): List<NoteSegment> {
        val fragWords = QuoteMatch.tokenize(fragments)
        val transWords = QuoteMatch.tokenize(transcript)
        return sentences.map { sentence ->
            val words = QuoteMatch.tokenize(sentence)
            val source = if (QuoteMatch.overlapScore(words, fragWords) > QuoteMatch.overlapScore(words, transWords)) {
                Provenance.FRAGMENT
            } else {
                Provenance.TRANSCRIPT
            }
            NoteSegment(text = sentence, source = source)
        }
    }

    private fun splitSentences(text: String): List<String> = SummaryText.splitSentences(text)

    /** Delegates to [NoteTitle] so generating and *recognising* a default stay in one place. */
    private fun defaultTitle(epochMs: Long): String = NoteTitle.default(epochMs)

    companion object {
        private const val MAX_CONTEXT_CHARS = 8_000

        /** Per-chunk budget for map-reduce; below MAX_CONTEXT_CHARS to leave room for the prompt. */
        private const val CHUNK_CHARS = 6_000
        private const val BULLETS_PER_CHUNK = 8

        /** Cap on the flat (non-structured) body's transcript sample. */
        private const val FLAT_BODY_CHARS = 2_000

        private val LEADING_TIMESTAMP = Regex("^\\s*-?\\s*\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)]\\s*")
        const val OFFLINE_ASSISTANT_REPLY =
            "On-device AI isn't available on this device yet, so I can't generate this. " +
                "Your note and transcript are still saved locally."
    }
}
