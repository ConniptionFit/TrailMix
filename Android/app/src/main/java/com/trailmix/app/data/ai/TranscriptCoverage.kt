package com.trailmix.app.data.ai

import com.trailmix.app.data.model.TranscriptLine

/**
 * Whole-session coverage (AI-05).
 *
 * Through v1.9.0 every summarization path read the transcript from the front and stopped:
 * the AI merge at 8,000 characters (~10 minutes of speech), the deterministic structurer at
 * 25 sentences (~3 minutes). For a 20-minute meeting that is harmless; for a 45-minute
 * conference talk the note silently described the introduction and nothing else, with no
 * marker saying so. The raw transcript was always kept in full — only the *summary* was
 * truncated, which is the more dangerous failure because it looks complete.
 *
 * Everything here is pure and offline. The rule throughout: when content has to be dropped to
 * fit a budget, drop it *evenly across the whole timeline* rather than lopping off the tail.
 */
object TranscriptCoverage {

    /** One contiguous stretch of the session, used as a summary section. */
    data class Window(
        val startSeconds: Int,
        val endSeconds: Int,
        val lines: List<TranscriptLine>,
    ) {
        /** e.g. "12:30 – 19:00"; blank when the lines carried no parseable labels. */
        val rangeLabel: String
            get() = if (startSeconds < 0) "" else "${formatSeconds(startSeconds)} – ${formatSeconds(endSeconds)}"

        val text: String get() = lines.joinToString(" ") { it.text }
    }

    /**
     * Parse a `mm:ss` or `h:mm:ss` capture offset to seconds, or null when the label is absent
     * or malformed. Fail-soft by design: label formatting is a display concern and a bad label
     * must never cost the user their summary.
     */
    fun parseLabelSeconds(label: String?): Int? {
        val parts = label?.trim()?.split(':') ?: return null
        if (parts.size !in 2..3 || parts.any { it.isBlank() }) return null
        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        }
    }

    fun formatSeconds(total: Int): String {
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * Split the session into time windows, each of which becomes its own summary section.
     *
     * Returns a single window when the session is too short to benefit — that keeps every
     * pre-existing short-meeting note rendering exactly as it did before this feature, so
     * windowing only ever engages where the old behavior was actually failing.
     */
    fun windows(
        lines: List<TranscriptLine>,
        targetWindowSeconds: Int = TARGET_WINDOW_SECONDS,
        maxWindows: Int = MAX_WINDOWS,
    ): List<Window> {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return emptyList()

        val stamps = usable.map { parseLabelSeconds(it.label) }
        val first = stamps.firstOrNull { it != null }
        val last = stamps.lastOrNull { it != null }
        val span = if (first != null && last != null) last - first else 0

        // Too short (or unlabelled) to slice — one window, no time range shown.
        if (span < targetWindowSeconds * 2) {
            return listOf(Window(startSeconds = -1, endSeconds = -1, lines = usable))
        }

        val count = (span / targetWindowSeconds).coerceIn(2, maxWindows)
        val windowSpan = span.toDouble() / count
        val buckets = List(count) { mutableListOf<TranscriptLine>() }
        var lastKnown = first
        usable.forEachIndexed { i, line ->
            val at = stamps[i] ?: lastKnown!!
            lastKnown = at
            val index = (((at - first!!) / windowSpan).toInt()).coerceIn(0, count - 1)
            buckets[index] += line
        }

        return buckets.mapIndexedNotNull { index, bucket ->
            if (bucket.isEmpty()) return@mapIndexedNotNull null
            val start = first!! + (windowSpan * index).toInt()
            val end = if (index == count - 1) first + span else first + (windowSpan * (index + 1)).toInt()
            Window(startSeconds = start, endSeconds = end, lines = bucket)
        }
    }

    /**
     * Reduce [lines] to at most [maxChars] of text while still touching the whole session:
     * the timeline is cut into slices and each contributes a contiguous run proportional to
     * its share, so the model sees the end of the talk as well as the beginning. Contiguous
     * runs rather than scattered single lines — a language model summarizes connected speech
     * far better than a shuffled bag of sentences.
     */
    fun evenSampleLines(lines: List<TranscriptLine>, maxChars: Int): List<TranscriptLine> {
        val usable = lines.filter { it.text.isNotBlank() }
        if (maxChars <= 0 || usable.isEmpty()) return emptyList()
        if (usable.sumOf { it.text.length + 1 } <= maxChars) return usable

        val slices = SAMPLE_SLICES.coerceAtMost(usable.size)
        val perSlice = maxChars / slices
        val sliceSize = usable.size.toDouble() / slices
        val picked = mutableListOf<TranscriptLine>()
        repeat(slices) { index ->
            val from = (sliceSize * index).toInt()
            val to = if (index == slices - 1) usable.size else (sliceSize * (index + 1)).toInt()
            var used = 0
            for (i in from until to) {
                val cost = usable[i].text.length + 1
                if (used + cost > perSlice && used > 0) break
                picked += usable[i]
                used += cost
            }
        }
        return picked
    }

    fun evenSample(lines: List<TranscriptLine>, maxChars: Int): String =
        evenSampleLines(lines, maxChars).joinToString("\n") { it.text }

    /**
     * Break the session into consecutive chunks that each fit [maxChars], for map-reduce
     * summarization. When the session is longer than [maxChunks] chunks would hold, the
     * timeline is instead divided into exactly [maxChunks] windows and each is evenly sampled
     * down to fit — bounding how many on-device model calls a merge costs while still covering
     * the session end to end. A three-hour recording therefore costs the same as a one-hour one.
     */
    fun chunks(
        lines: List<TranscriptLine>,
        maxChars: Int,
        maxChunks: Int = MAX_CHUNKS,
    ): List<List<TranscriptLine>> {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty() || maxChars <= 0) return emptyList()

        val consecutive = mutableListOf<MutableList<TranscriptLine>>()
        var current = mutableListOf<TranscriptLine>()
        var used = 0
        usable.forEach { line ->
            val cost = line.text.length + 1
            if (used + cost > maxChars && current.isNotEmpty()) {
                consecutive += current
                current = mutableListOf()
                used = 0
            }
            current += line
            used += cost
        }
        if (current.isNotEmpty()) consecutive += current
        if (consecutive.size <= maxChunks) return consecutive

        val windowSize = usable.size.toDouble() / maxChunks
        return (0 until maxChunks).mapNotNull { index ->
            val from = (windowSize * index).toInt()
            val to = if (index == maxChunks - 1) usable.size else (windowSize * (index + 1)).toInt()
            evenSampleLines(usable.subList(from, to), maxChars).takeIf { it.isNotEmpty() }
        }
    }

    /** Aim for one section per ~7 minutes of talk. */
    const val TARGET_WINDOW_SECONDS = 420
    const val MAX_WINDOWS = 8

    /** Bounds the number of on-device model calls one merge can trigger. */
    const val MAX_CHUNKS = 6

    private const val SAMPLE_SLICES = 12
}
