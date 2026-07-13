package com.trailmix.app.data.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.trailmix.app.data.model.NoteSegment
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.TranscriptLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** Merge typed fragments + transcript into a provenance-tagged note. */
    suspend fun merge(
        typedFragments: String,
        transcript: List<TranscriptLine>,
        createdAtEpochMs: Long,
    ): MergeResult = withContext(Dispatchers.Default) {
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

            MergeResult(
                title = title.ifBlank { defaultTitle(createdAtEpochMs) },
                segments = segments.ifEmpty {
                    fallbackMerge(typedFragments, transcript, createdAtEpochMs).segments
                },
                usedOnDeviceAi = true,
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
    ): String = withContext(Dispatchers.Default) {
        val availability = ensureModelReady()
        if (availability !is AiAvailability.Available) {
            return@withContext OFFLINE_ASSISTANT_REPLY
        }
        try {
            val prompt = buildString {
                appendLine("You are a concise assistant working with one meeting note.")
                appendLine("Answer using only the note and transcript below. Plain text only.")
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
        return MergeResult(
            title = defaultTitle(createdAtEpochMs),
            segments = segments.ifEmpty {
                listOf(NoteSegment("Empty capture.", Provenance.FRAGMENT))
            },
            usedOnDeviceAi = false,
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
