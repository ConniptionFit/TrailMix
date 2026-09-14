package com.trailmix.app.data.speech

import com.trailmix.app.data.ai.MergePolicy
import com.trailmix.app.data.ai.TranscriptCoverage
import com.trailmix.app.data.model.StringListJson
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TranscriptLine
import org.json.JSONArray
import org.json.JSONObject

/**
 * REL-09: the on-disk write-ahead log for a live capture, and the pure replay that turns it
 * back into a session.
 *
 * **The problem.** Until this, a capture existed only in [CaptureSessionManager]'s in-memory
 * buffers until End & Merge wrote a note. Anything that killed the process before that —
 * a crash, a low-memory kill while the user was in another app for forty minutes, force-stop,
 * a flat battery — took the entire transcript with it, with nothing left behind to show it
 * had ever happened. On an app that is `allowBackup=false` and local-only, an hour of a
 * conference keynote is simply gone. That is the single worst data-loss shape this app has,
 * and the one most likely to happen at exactly the moment it matters most (a long session,
 * in the background, with the recorder competing for memory against everything else).
 *
 * **The shape of the fix.** An append-only JSONL journal, one file per session, in
 * app-private storage. Every finalized utterance is one appended line; session metadata is
 * folded in as periodic delta records. Append-only is the whole point:
 *
 * - a crash can only ever truncate the *last* record, never corrupt earlier ones, so recovery
 *   degrades by at most one utterance instead of losing the file;
 * - the cost is O(n) total bytes for the session, not O(n²). Re-serialising the growing
 *   transcript on every utterance would have written ~95 MB over a 90-minute keynote
 *   (see `LongSessionLoadTest` for where that figure comes from) — enough that the durability
 *   mechanism would itself have become a battery and I/O problem on long sessions;
 * - replay is a pure fold over lines of text, so all of it is unit-testable with no device.
 *
 * **Audio is never journaled**, only recognized text — zero-retention audio is architectural
 * and this does not touch it.
 *
 * Records are single-line JSON objects keyed by [KEY_KIND]. Unknown kinds and unparseable
 * lines are skipped rather than aborting the replay: a journal is a recovery aid, and the
 * failure mode of a strict parser here is throwing away the very data it exists to protect.
 */
object CaptureJournal {

    /** Bumped only for a change replay can't absorb — see [replay]'s forward-compat note. */
    const val VERSION = 1

    private const val KEY_KIND = "k"
    private const val KIND_SESSION = "s"
    private const val KIND_LINE = "l"
    private const val KIND_DELTA = "d"
    private const val KIND_END = "e"
    private const val KIND_FLAG = "f"

    /** CAP-24: a user-flagged moment during capture — just the timestamp, nothing else. */
    data class CaptureFlag(val label: String)

    /**
     * A capture reconstructed from a journal. Mirrors the fields [CaptureSessionManager] needs
     * to either resume recording into the same session or merge it into a note — anything not
     * represented here is genuinely not recoverable (the live audio route, the partial
     * utterance in flight, the input-device selection).
     */
    data class RecoveredSession(
        val startedAtEpochMs: Long,
        /** The note this capture was resuming into (CAP-07), or -1 for a fresh note. */
        val resumeNoteId: Long = -1L,
        val noteTitle: String = "",
        val meetingTitle: String? = null,
        val template: String = SummaryTemplate.NONE.name,
        val typedFragments: String = "",
        val transcript: List<TranscriptLine> = emptyList(),
        val durationMs: Long = 0L,
        val capturedInCall: Boolean = false,
        val attendees: List<String> = emptyList(),
        /** CAP-24: moments the user flagged during this session, in the order they were tapped. */
        val flags: List<CaptureFlag> = emptyList(),
        /**
         * True when the journal carried an explicit end marker — the session finished cleanly
         * and this file is just litter. Distinguishing this from a crash matters: offering to
         * "recover" a note the user already saved would be a bug that looks like data loss.
         */
        val closedCleanly: Boolean = false,
    ) {
        /**
         * Is there anything here worth offering the user? Deliberately the same rule
         * [MergePolicy] applies at End & Merge — a journal with nothing in it must not
         * produce a recovery prompt, or every crash outside a capture would nag about
         * an empty session.
         */
        val hasContent: Boolean
            get() = !closedCleanly && !MergePolicy.nothingToSave(typedFragments, transcript)
    }

    // ── Writing ────────────────────────────────────────────────────────────

    /** Opening record. Carries the format version and the session's wall-clock origin. */
    fun sessionRecord(startedAtEpochMs: Long, resumeNoteId: Long): String =
        JSONObject()
            .put(KEY_KIND, KIND_SESSION)
            .put("v", VERSION)
            .put("at", startedAtEpochMs)
            .put("note", resumeNoteId)
            .toString()

    /**
     * One finalized utterance. `JSONObject.toString()` escapes newlines, so a record is
     * always exactly one physical line however the recognizer punctuates — which is what
     * makes "read line by line, skip what doesn't parse" a sound recovery strategy.
     */
    fun lineRecord(line: TranscriptLine): String =
        JSONObject()
            .put(KEY_KIND, KIND_LINE)
            .put("l", line.label)
            .put("t", line.text)
            .toString()

    /**
     * A metadata delta: only the fields that actually changed since the last one, with
     * last-write-wins on replay. Written on a slow timer rather than on every change, because
     * none of it is worth an fsync per keystroke — the elapsed duration is the only field
     * that changes continuously, and it is worth at most one write per flush interval.
     *
     * Returns null when nothing changed, so the caller can skip the write entirely.
     */
    @Suppress("LongParameterList")
    fun deltaRecord(
        durationMs: Long? = null,
        typedFragments: String? = null,
        noteTitle: String? = null,
        meetingTitle: String? = null,
        template: String? = null,
        attendees: List<String>? = null,
        capturedInCall: Boolean? = null,
        resumeNoteId: Long? = null,
    ): String? {
        val o = JSONObject().put(KEY_KIND, KIND_DELTA)
        var any = false
        durationMs?.let { o.put("dur", it); any = true }
        typedFragments?.let { o.put("frag", it); any = true }
        noteTitle?.let { o.put("title", it); any = true }
        meetingTitle?.let { o.put("meet", it); any = true }
        template?.let { o.put("tpl", it); any = true }
        attendees?.let { o.put("att", JSONArray(StringListJson.encode(it))); any = true }
        capturedInCall?.let { o.put("call", it); any = true }
        resumeNoteId?.let { o.put("note", it); any = true }
        return if (any) o.toString() else null
    }

    /** Written when a session ends cleanly, before the file is deleted. See [RecoveredSession.closedCleanly]. */
    fun endRecord(): String = JSONObject().put(KEY_KIND, KIND_END).toString()

    /**
     * CAP-24: one flagged moment. [label] matches [TranscriptLine.label]'s `mm:ss` format so it
     * replays through the same [TranscriptCoverage.parseLabelSeconds] parsing everything else
     * already uses — no separate offset representation to keep in sync.
     */
    fun flagRecord(label: String): String =
        JSONObject()
            .put(KEY_KIND, KIND_FLAG)
            .put("l", label)
            .toString()

    // ── Replay ─────────────────────────────────────────────────────────────

    /**
     * Fold a journal's lines back into a session.
     *
     * [fallbackStartedAtEpochMs] is used when the header record is missing or unreadable —
     * the store passes the timestamp encoded in the filename. A journal whose *first* write
     * was the one that got truncated would otherwise be unrecoverable in full, which is a
     * silly way to lose a transcript that is sitting right there in the following records.
     *
     * Forward compatibility: a journal written by a newer [VERSION] is replayed anyway, on
     * the reasoning that this format only ever grows fields and an unknown key is already
     * ignored. A newer version that genuinely can't be read this way must change the
     * *filename* convention so old builds don't see the file at all — silently mis-replaying
     * someone's transcript would be worse than not finding it.
     */
    fun replay(lines: Sequence<String>, fallbackStartedAtEpochMs: Long = 0L): RecoveredSession {
        var startedAt = fallbackStartedAtEpochMs
        var resumeNoteId = -1L
        var noteTitle = ""
        var meetingTitle: String? = null
        var template = SummaryTemplate.NONE.name
        var fragments = ""
        var duration = 0L
        var inCall = false
        var attendees: List<String> = emptyList()
        var closed = false
        val transcript = mutableListOf<TranscriptLine>()
        val flags = mutableListOf<CaptureFlag>()

        for (raw in lines) {
            if (raw.isBlank()) continue
            // A truncated tail record throws here and is simply not applied. Everything
            // written before it has already been folded in and is unaffected.
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            when (o.optString(KEY_KIND)) {
                KIND_SESSION -> {
                    o.optLong("at").takeIf { it > 0 }?.let { startedAt = it }
                    if (o.has("note")) resumeNoteId = o.optLong("note", -1L)
                }

                KIND_LINE -> {
                    val text = o.optString("t")
                    if (text.isNotBlank()) transcript += TranscriptLine(label = o.optString("l"), text = text)
                }

                KIND_DELTA -> {
                    if (o.has("dur")) duration = o.optLong("dur", duration)
                    if (o.has("frag")) fragments = o.optString("frag")
                    if (o.has("title")) noteTitle = o.optString("title")
                    if (o.has("meet")) meetingTitle = o.optString("meet").takeIf { it.isNotBlank() }
                    if (o.has("tpl")) template = o.optString("tpl").ifBlank { template }
                    if (o.has("att")) {
                        attendees = StringListJson.decode(o.optJSONArray("att")?.toString())
                    }
                    if (o.has("call")) inCall = o.optBoolean("call", inCall)
                    if (o.has("note")) resumeNoteId = o.optLong("note", resumeNoteId)
                }

                KIND_FLAG -> {
                    val label = o.optString("l")
                    if (label.isNotBlank()) flags += CaptureFlag(label)
                }

                KIND_END -> closed = true
            }
        }

        return RecoveredSession(
            startedAtEpochMs = startedAt,
            resumeNoteId = resumeNoteId,
            noteTitle = noteTitle,
            meetingTitle = meetingTitle,
            template = template,
            typedFragments = fragments,
            transcript = transcript,
            // The duration delta is only written every flush interval, so on a crash it is
            // stale by up to that interval. The last utterance's own capture offset is a
            // harder floor — it was true at the moment that line was recognized — so take
            // whichever is later. Under-reporting here would silently rewind the timeline a
            // resumed session counts up from.
            durationMs = maxOf(duration, lastLabelMs(transcript)),
            capturedInCall = inCall,
            attendees = attendees,
            flags = flags,
            closedCleanly = closed,
        )
    }

    private fun lastLabelMs(transcript: List<TranscriptLine>): Long =
        transcript.asReversed()
            .firstNotNullOfOrNull { TranscriptCoverage.parseLabelSeconds(it.label) }
            ?.let { it * 1000L }
            ?: 0L
}
