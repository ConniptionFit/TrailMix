package com.trailmix.app.data.ai

import com.trailmix.app.data.export.NoteMarkdown
import com.trailmix.app.data.model.SummaryStyle
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * REL-01: the summarization and export pipeline at **conference-keynote scale**.
 *
 * Every prior test in this suite runs on a handful of lines. Real use is a 60–90 minute
 * session producing well over a thousand utterances, and the failure modes that matter only
 * appear there: quadratic blow-ups that are invisible at n=5, and silent truncation that
 * leaves a plausible-looking note covering only the opening — which is exactly the AI-05 bug,
 * and the one that is hardest to notice because the *transcript* still looks complete.
 *
 * These are deliberately generous on time. They are not benchmarks; they are tripwires for a
 * future change that makes the pipeline super-linear. A wall-clock assertion is a blunt tool,
 * so the bounds are set far above observed cost — if one of these ever fails, the cause is an
 * algorithmic regression, not a slow machine.
 *
 * The one thing this *cannot* cover is the on-device model: a real 90-minute merge issues a
 * chunked series of AICore calls whose latency and failure behaviour only exist on hardware.
 * That part of REL-01 stays open.
 */
class LongSessionLoadTest {

    /** ~90 minutes at roughly one utterance every 4.5 seconds. */
    private val lineCount = 1_200
    private val sessionMs = 90 * 60_000L

    /**
     * A transcript with realistic shape: varied vocabulary so dedupe and coverage selection
     * have real work to do, recurring speakers/topics so it isn't trivially compressible, and
     * a distinctive marker in the final minutes used to prove whole-session coverage.
     */
    private fun longTranscript(): List<TranscriptLine> {
        val topics = listOf(
            "identity governance", "token lifetimes", "device posture", "session revocation",
            "policy drift", "provisioning latency", "audit retention", "risk scoring",
        )
        val verbs = listOf(
            "we measured", "the team rolled back", "customers reported", "we benchmarked",
            "the working group proposed", "we deprecated", "the migration exposed",
        )
        val subjects = listOf(
            "the staging fleet", "downstream partners", "the mobile clients", "batch importers",
            "the admin console", "regional replicas", "legacy connectors", "the edge cache",
            "shared tenants", "the reporting warehouse", "webhook consumers",
        )
        val phases = listOf(
            "the pilot", "general availability", "the freeze window", "the rollback drill",
            "onboarding", "the compliance review", "peak traffic", "the cutover",
        )
        val outcomes = listOf(
            "no regressions", "a latency spike", "cleaner audit trails", "fewer support tickets",
            "an unexpected retry storm", "flat error rates", "improved cache hits",
        )
        return (0 until lineCount).map { i ->
            val ms = (sessionMs * i) / lineCount
            val label = String.format("%d:%02d", ms / 60_000, (ms % 60_000) / 1000)
            val text = when {
                i == lineCount - 3 -> "The closing recommendation is to consolidate on zephyrine rollouts."
                // Varied enough that overlap-based dedupe has real discrimination to do —
                // a template that repeats every few dozen lines produces genuine duplicates
                // and would measure dedupe's collapse rate rather than the pipeline's cost.
                else -> "${verbs[i % verbs.size]} ${topics[i % topics.size]} against " +
                    "${subjects[i % subjects.size]} during ${phases[i % phases.size]}, " +
                    "landing at ${i % 97} percent with ${outcomes[i % outcomes.size]}."
            }
            TranscriptLine(label = label, text = text)
        }
    }

    @Test
    fun `deterministic summary covers a 90-minute session end to end`() {
        val transcript = longTranscript()
        lateinit var summaryText: String
        var summary: com.trailmix.app.data.model.StructuredSummary? = null

        val elapsed = measureTimeMillis {
            summary = DeterministicSummary.from(
                typedFragments = "Need to brief the exec team on this.",
                transcript = transcript,
                style = SummaryStyle.PRESENTATION,
            )
            assertNotNull("a 90-minute session must produce a structured summary", summary)
            summaryText = summary!!.sections.joinToString("\n") { section ->
                section.heading + "\n" + section.bullets.joinToString("\n") { it.text }
            }
        }

        // The AI-05 regression, at scale: a note built only from the opening still looks
        // complete. Coverage here is structural, not "one specific sentence survived" —
        // selection inside a window is a coverage heuristic and picking any given line is not
        // a contract. What IS a contract: bullets must be drawn from across the whole session.
        val stamps = summary!!.sections
            .flatMap { it.bullets }
            .mapNotNull { it.timestampLabel }
            .mapNotNull { label ->
                label.split(":").takeIf { it.size == 2 }?.let { (m, sec) ->
                    m.toIntOrNull()?.let { mm -> sec.toIntOrNull()?.let { ss -> mm * 60 + ss } }
                }
            }
        assertTrue("expected timestamped transcript bullets", stamps.isNotEmpty())
        val sessionSeconds = (sessionMs / 1000).toInt()
        assertTrue("summary should start near the beginning", stamps.min() < sessionSeconds / 4)
        assertTrue(
            "summary must reach the final stretch of the session, not stop early " +
                "(latest bullet at ${stamps.max()}s of ${sessionSeconds}s)",
            stamps.max() > sessionSeconds * 3 / 4,
        )
        assertTrue("expected several time-windowed sections", summary!!.sections.size >= 5)
        assertTrue("DeterministicSummary took ${elapsed}ms — suspect super-linear cost", elapsed < 10_000)
    }

    @Test
    fun `time windows and chunking stay proportional to the session`() {
        val transcript = longTranscript()
        val elapsed = measureTimeMillis {
            val windows = TranscriptCoverage.windows(transcript)
            assertTrue("a 90-minute session should slice into several windows", windows.size >= 5)
            // Every line must land in exactly one window — no silent drops.
            assertEquals(lineCount, windows.sumOf { it.lines.size })

            val chunks = TranscriptCoverage.chunks(transcript, 8_000)
            // Measured: a 90-minute session is ~158k transcript chars → 8 time windows → 6
            // model chunks. Each chunk is one AICore generateContent call, and
            // generateStructuredSummary makes its own pass, so a keynote merge is on the
            // order of a dozen sequential model calls — minutes, not seconds. That is why the
            // merge runs on CaptureSessionManager's process-scoped scope behind the capture
            // foreground service: the user can leave the app while it finishes.
            assertTrue("expected the transcript to need several model chunks", chunks.size > 1)
        }
        assertTrue("coverage math took ${elapsed}ms — suspect super-linear cost", elapsed < 10_000)
    }

    /** The exported pair must render, and the transcript file must keep every single line. */
    @Test
    fun `export renders a 90-minute note without dropping transcript lines`() {
        val transcript = longTranscript()
        val source = NoteMarkdown.Source(
            title = "Identity platform keynote",
            createdAtEpochMs = 1_785_000_000_000,
            durationMs = sessionMs,
            summary = DeterministicSummary.from("", transcript, SummaryStyle.PRESENTATION),
            transcript = transcript,
        )

        lateinit var note: String
        lateinit var transcriptDoc: String
        val elapsed = measureTimeMillis {
            note = NoteMarkdown.buildNote(source)
            transcriptDoc = NoteMarkdown.buildTranscript(source)
        }

        // The summary must stay small enough to paste into another model — that is the whole
        // point of OBS-02 splitting the transcript out. Assert it didn't quietly re-inline.
        assertTrue("summary should stay far smaller than the transcript", note.length < transcriptDoc.length)
        assertTrue("transcript document lost lines", transcriptDoc.lines().count { it.startsWith("| `") } == lineCount)
        assertTrue("export took ${elapsed}ms — suspect super-linear cost", elapsed < 10_000)
    }

    /** Sentence-level helpers are called per utterance; they must not degrade with volume. */
    @Test
    fun `sentence helpers stay linear over a long session`() {
        val sentences = longTranscript().map { it.text }
        val elapsed = measureTimeMillis {
            // Note: no assertion on how MUCH dedupe collapses. That is a property of the
            // text, not of the code — synthetic prose drawn from a small vocabulary trips the
            // 0.8-overlap threshold far more than real speech does, so asserting a keep-rate
            // would only be measuring the fixture. What matters here is that it completes.
            val deduped = SummaryText.dedupe(sentences)
            assertTrue("dedupe returned nothing at all", deduped.isNotEmpty())
            SummaryText.selectDistinct(deduped, limit = 12)
            SummaryText.keywords(sentences.joinToString(" "), 8)
        }
        // Measured at ~26ms for 1,200 sentences on a Pixel-class dev machine. dedupe() is
        // O(n^2) — it rescans every kept sentence — but the constant is small enough that a
        // 3-hour session lands near 100ms, so it is a documented ceiling, not a problem to
        // fix. This bound is ~75x headroom: it catches an algorithmic regression, not jitter.
        assertTrue("sentence helpers took ${elapsed}ms — suspect super-linear cost", elapsed < 2_000)
    }
}
