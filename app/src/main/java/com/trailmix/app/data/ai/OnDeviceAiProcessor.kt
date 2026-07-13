package com.trailmix.app.data.ai

import android.content.Context
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.google.mlkit.genai.summarization.SummarizerOptions.InputType
import com.google.mlkit.genai.summarization.SummarizerOptions.Language
import com.google.mlkit.genai.summarization.SummarizerOptions.OutputType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Singleton
class OnDeviceAiProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val summarizer: Summarizer by lazy {
        val options = SummarizerOptions.builder(context)
            .setInputType(InputType.CONVERSATION)
            .setOutputType(OutputType.THREE_BULLETS)
            .setLanguage(Language.ENGLISH)
            .build()
        Summarization.getClient(options)
    }

    private val generativeModel: GenerativeModel by lazy {
        Generation.getClient()
    }

    suspend fun checkAvailability(): AiAvailability = withContext(executor) {
        try {
            val status = summarizer.checkFeatureStatus().await()
            when (status) {
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

    suspend fun ensureModelReady(
        onProgress: (Long) -> Unit = {},
    ): AiAvailability = withContext(executor) {
        when (val status = checkAvailability()) {
            is AiAvailability.Available -> status
            is AiAvailability.Downloadable, is AiAvailability.Downloading -> {
                downloadModel(onProgress)
                checkAvailability()
            }
            is AiAvailability.Unavailable -> status
        }
    }

    private suspend fun downloadModel(onProgress: (Long) -> Unit) {
        suspendCancellableCoroutine { cont ->
            summarizer.downloadFeature(
                object : DownloadCallback {
                    override fun onDownloadStarted(bytesToDownload: Long) = Unit
                    override fun onDownloadProgress(bytesDownloaded: Long) {
                        onProgress(bytesDownloaded)
                    }
                    override fun onDownloadCompleted() {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onDownloadFailed(e: GenAiException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                },
            )
        }
    }

    suspend fun process(
        typedNotes: String,
        transcript: String,
        createdAtEpochMs: Long,
        durationMs: Long,
    ): ProcessedNoteContent = withContext(Dispatchers.Default) {
        val truncatedTranscript = truncateForContext(transcript, maxChars = 8_000)
        val availability = ensureModelReady()
        if (availability !is AiAvailability.Available) {
            return@withContext fallbackMerge(
                typedNotes = typedNotes,
                transcript = truncatedTranscript,
                createdAtEpochMs = createdAtEpochMs,
                durationMs = durationMs,
                reason = (availability as? AiAvailability.Unavailable)?.reason
                    ?: "On-device AI model is not ready.",
            )
        }

        try {
            val summarySource = buildString {
                if (typedNotes.isNotBlank()) {
                    appendLine("Typed notes:")
                    appendLine(typedNotes.trim())
                    appendLine()
                }
                if (truncatedTranscript.isNotBlank()) {
                    appendLine("Transcript:")
                    appendLine(truncatedTranscript.trim())
                }
            }.ifBlank { "No content captured." }

            val summary = summarizer
                .runInference(SummarizationRequest.builder(summarySource).build())
                .await()
                .summary
                .orEmpty()
                .ifBlank { "No summary generated." }

            val mergePrompt = """
                Merge the typed notes and voice transcript into one clean Markdown note for Obsidian.
                Return ONLY Markdown with this structure:
                # <short title>
                ## Summary
                <2-4 sentences>
                ## Key points
                - bullet points from the summary and content
                ## Notes
                <merged narrative combining typed notes and transcript; prefer typed notes when they conflict>

                Typed notes:
                ${typedNotes.ifBlank { "(none)" }}

                Transcript:
                ${truncatedTranscript.ifBlank { "(none)" }}

                Existing summary bullets:
                $summary
            """.trimIndent()

            val merged = generativeModel.generateContent(mergePrompt)
                .candidates
                .firstOrNull()
                ?.text
                ?.trim()
                .orEmpty()

            val title = extractTitle(merged)
                ?: defaultTitle(createdAtEpochMs)

            val markdown = if (merged.startsWith("#")) {
                merged
            } else {
                buildFallbackMarkdown(
                    title = title,
                    summary = summary,
                    typedNotes = typedNotes,
                    transcript = truncatedTranscript,
                    createdAtEpochMs = createdAtEpochMs,
                    durationMs = durationMs,
                    note = null,
                )
            }

            ProcessedNoteContent(
                title = title,
                summary = summary,
                mergedMarkdown = markdown,
                usedOnDeviceAi = true,
            )
        } catch (e: Exception) {
            fallbackMerge(
                typedNotes = typedNotes,
                transcript = truncatedTranscript,
                createdAtEpochMs = createdAtEpochMs,
                durationMs = durationMs,
                reason = e.message ?: "On-device AI failed.",
            )
        }
    }

    private fun fallbackMerge(
        typedNotes: String,
        transcript: String,
        createdAtEpochMs: Long,
        durationMs: Long,
        reason: String,
    ): ProcessedNoteContent {
        val title = defaultTitle(createdAtEpochMs)
        val summary = buildString {
            appendLine("- Captured on-device without Gemini Nano processing")
            if (typedNotes.isNotBlank()) appendLine("- Includes typed notes")
            if (transcript.isNotBlank()) appendLine("- Includes voice transcript")
            appendLine("- $reason")
        }.trim()
        return ProcessedNoteContent(
            title = title,
            summary = summary,
            mergedMarkdown = buildFallbackMarkdown(
                title = title,
                summary = summary,
                typedNotes = typedNotes,
                transcript = transcript,
                createdAtEpochMs = createdAtEpochMs,
                durationMs = durationMs,
                note = reason,
            ),
            usedOnDeviceAi = false,
        )
    }

    private fun buildFallbackMarkdown(
        title: String,
        summary: String,
        typedNotes: String,
        transcript: String,
        createdAtEpochMs: Long,
        durationMs: Long,
        note: String?,
    ): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("## Summary")
        appendLine(summary)
        appendLine()
        appendLine("## Notes")
        if (typedNotes.isNotBlank()) {
            appendLine(typedNotes.trim())
            appendLine()
        }
        if (transcript.isNotBlank()) {
            appendLine("### Transcript")
            appendLine(transcript.trim())
            appendLine()
        }
        if (note != null) {
            appendLine("> $note")
            appendLine()
        }
        appendLine("---")
        appendLine("Duration: ${formatDuration(durationMs)} · Created: ${formatDateTime(createdAtEpochMs)}")
    }.trim()

    private fun extractTitle(markdown: String): String? {
        val line = markdown.lineSequence().firstOrNull { it.trim().startsWith("# ") } ?: return null
        return line.trim().removePrefix("# ").trim().takeIf { it.isNotBlank() }
    }

    private fun defaultTitle(createdAtEpochMs: Long): String =
        "Note ${formatDateTime(createdAtEpochMs)}"

    private fun truncateForContext(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return "…${text.takeLast(maxChars)}"
    }

    private fun formatDateTime(epochMs: Long): String =
        SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(epochMs))

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    try {
                        cont.resume(get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e.cause ?: e)
                    }
                },
                { it.run() },
            )
        }
}
