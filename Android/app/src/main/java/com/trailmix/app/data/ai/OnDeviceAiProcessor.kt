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
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TranscriptLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    ): MergeResult = withContext(Dispatchers.Default) {
        // CAP-11 (v1.8.0): no transcript → nothing to merge or summarize. The typed notes
        // are saved verbatim via the deterministic path; the model is never invoked.
        if (!MergePolicy.hasTranscript(transcript)) {
            return@withContext fallbackMerge(typedFragments, transcript, createdAtEpochMs)
        }
        val transcriptText = transcript.joinToString("\n") { it.text }.take(MAX_CONTEXT_CHARS)
        val availability = ensureModelReady()
        if (availability !is AiAvailability.Available) {
            return@withContext fallbackMerge(typedFragments, transcript, createdAtEpochMs)
        }
        try {
            val prompt = """
                You are merging a user's rough typed notes with a call transcript into one
                clean, structured meeting note.
                Rules:
                - Prefer the typed notes when they conflict with the transcript.
                - Keep it factual and short; no invented details.
                - First line: a short title (max 8 words), no markdown.
                - Then the note body as plain sentences, one thought per sentence.

                Typed notes:
                ${typedFragments.ifBlank { "(none)" }}

                Transcript:
                ${transcriptText.ifBlank { "(none)" }}
            """.trimIndent()

            val output = generate(prompt)
            val lines = output.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size < 2) return@withContext fallbackMerge(typedFragments, transcript, createdAtEpochMs)

            val title = lines.first().removePrefix("#").trim().take(80)
            val body = lines.drop(1).joinToString(" ")
            val segments = attributeProvenance(splitSentences(body), typedFragments, transcriptText)
            // Structured summary from the model, or the deterministic structurer as a net so
            // a bad/non-JSON model reply still yields sectioned output, never a flat wall.
            val structured = runCatching {
                generateStructuredSummary(typedFragments, transcriptText, attendees, templateGuidance)
            }.getOrNull() ?: DeterministicSummary.from(typedFragments, transcriptText)

            MergeResult(
                title = title.ifBlank { defaultTitle(createdAtEpochMs) },
                segments = segments.ifEmpty {
                    fallbackMerge(typedFragments, transcript, createdAtEpochMs).segments
                },
                usedOnDeviceAi = true,
                structuredSummary = structured,
            )
        } catch (_: Exception) {
            fallbackMerge(typedFragments, transcript, createdAtEpochMs)
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

    // ── Structured summary (UX-02) ───────────────────────────────────────────

    private suspend fun generateStructuredSummary(
        typedFragments: String,
        transcriptText: String,
        attendees: List<String>,
        templateGuidance: String,
    ): StructuredSummary? {
        val prompt = """
            You are structuring a meeting note into JSON. $templateGuidance
            ${if (attendees.isNotEmpty()) "Attendees: ${attendees.joinToString(", ")}." else ""}
            Respond with ONLY valid JSON, no markdown fences, matching exactly this shape:
            {"highlights": ["short key decision or highlight", "..."],
             "sections": [{"heading": "Topic name", "bullets": ["bullet text", "..."]}],
             "actionItems": [{"text": "what needs doing", "owner": "name or null", "deadline": "date/phrase or null"}]}
            Rules: factual only, no invented details, omit owner/deadline (use null) when not statable,
            2-5 highlights, group remaining content into 2-5 topic sections, action items only when real.

            Typed notes:
            ${typedFragments.ifBlank { "(none)" }}

            Transcript:
            ${transcriptText.ifBlank { "(none)" }}
        """.trimIndent()

        val raw = generate(prompt)
        val jsonText = raw.substringAfter('{', "").let { if (it.isBlank()) raw else "{$it" }
            .substringBeforeLast('}', "").let { if (it.isBlank()) raw else "$it}" }
        val root = JSONObject(jsonText)

        val fragmentSentences = splitSentences(typedFragments)
        val transcriptSentences = splitSentences(transcriptText)
        val fragWords = tokenize(typedFragments)
        val transWords = tokenize(transcriptText)

        fun attribute(text: String): SummaryBullet {
            val (source, excerpt) = classify(text, fragWords, transWords, fragmentSentences, transcriptSentences)
            return SummaryBullet(text = text, source = source, sourceExcerpt = excerpt)
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
                val text = o.getString("text").trim()
                val (source, excerpt) = classify(text, fragWords, transWords, fragmentSentences, transcriptSentences)
                ActionItem(
                    text = text,
                    owner = o.optString("owner").takeIf { it.isNotBlank() && it != "null" },
                    deadline = o.optString("deadline").takeIf { it.isNotBlank() && it != "null" },
                    source = source,
                    sourceExcerpt = excerpt,
                )
            }
        }.orEmpty().filter { it.text.isNotBlank() }

        if (highlights.isEmpty() && sections.isEmpty() && actionItems.isEmpty()) return null
        return StructuredSummary(highlights = highlights, sections = sections, actionItems = actionItems)
    }

    /** Same word-overlap logic as [attributeProvenance], plus the best-matching source excerpt. */
    private fun classify(
        text: String,
        fragWords: Set<String>,
        transWords: Set<String>,
        fragmentSentences: List<String>,
        transcriptSentences: List<String>,
    ): Pair<Provenance, String?> {
        val words = tokenize(text)
        val fragScore = overlapScore(words, fragWords)
        val transScore = overlapScore(words, transWords)
        val source = if (fragScore > transScore) Provenance.FRAGMENT else Provenance.TRANSCRIPT
        val candidates = if (source == Provenance.FRAGMENT) fragmentSentences else transcriptSentences
        val excerpt = candidates
            .map { it to overlapScore(words, tokenize(it)) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0.0 }
            ?.first
        return source to excerpt
    }

    private fun overlapScore(words: Set<String>, against: Set<String>): Double =
        if (against.isEmpty() || words.isEmpty()) 0.0 else words.count { it in against } / words.size.toDouble()

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
    ): MergeResult {
        val segments = buildList {
            splitSentences(typedFragments).forEach { add(NoteSegment(it, Provenance.FRAGMENT)) }
            transcript.map { it.text }.filter { it.isNotBlank() }.take(12).forEach {
                add(NoteSegment(it.trim(), Provenance.TRANSCRIPT))
            }
        }
        // Even with no AI, structure the content deterministically so the default note is
        // Key Topics + Action Items rather than a flat block (null → flat only for tiny notes).
        val transcriptText = transcript.joinToString("\n") { it.text }
        return MergeResult(
            title = defaultTitle(createdAtEpochMs),
            segments = segments.ifEmpty {
                listOf(NoteSegment("Empty capture.", Provenance.FRAGMENT))
            },
            usedOnDeviceAi = false,
            structuredSummary = DeterministicSummary.from(typedFragments, transcriptText),
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
        val fragWords = tokenize(fragments)
        val transWords = tokenize(transcript)
        return sentences.map { sentence ->
            val words = tokenize(sentence)
            val fragScore = if (fragWords.isEmpty() || words.isEmpty()) {
                0.0
            } else {
                words.count { it in fragWords } / words.size.toDouble()
            }
            val transScore = if (transWords.isEmpty() || words.isEmpty()) {
                0.0
            } else {
                words.count { it in transWords } / words.size.toDouble()
            }
            NoteSegment(
                text = sentence,
                source = if (fragScore > transScore) Provenance.FRAGMENT else Provenance.TRANSCRIPT,
            )
        }
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9']+"))
            .filter { it.length > 2 }
            .toSet()

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun defaultTitle(epochMs: Long): String =
        "Note — " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMs))

    companion object {
        private const val MAX_CONTEXT_CHARS = 8_000
        const val OFFLINE_ASSISTANT_REPLY =
            "On-device AI isn't available on this device yet, so I can't generate this. " +
                "Your note and transcript are still saved locally."
    }
}
