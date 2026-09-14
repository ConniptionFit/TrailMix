package com.trailmix.app.data.speech

/**
 * Decision layer on top of Eagle's raw per-frame similarity scores. Unlike [SpeakerLabels] —
 * a provably-correct mapping over Falcon's already-decided clustering — this file's own
 * thresholding and vote-based hysteresis is itself a second probabilistic layer that can be
 * wrong independent of Eagle's own accuracy, so it's the main unit-test concentration for live
 * speaker recognition (real accuracy still needs a hardware spike with real voices).
 *
 * Callers normalize whatever Eagle's SDK actually returns per frame into either a FloatArray of
 * similarity scores (one per enrolled profile, same order every call) or an empty array for "no
 * usable voice this frame" — this object never touches the SDK directly.
 */
object LiveSpeakerMatcher {

    sealed class Verdict {
        /** Index into the caller's profile list, e.g. `enrolledSpeakers[profileIndex]`. */
        data class Recognized(val profileIndex: Int) : Verdict()

        /** Enough signal to decide, but it doesn't confidently belong to any enrolled profile. */
        data object Unknown : Verdict()

        /** Not enough frames (or not enough agreement yet) to trust a verdict either way. */
        data object InsufficientSignal : Verdict()
    }

    /**
     * @param scoresPerFrame one score array per processed frame, chronological, each either
     *   sized to the enrolled-profile count or empty (no usable voice that frame). Empty frames
     *   are skipped, not counted as votes against recognition.
     * @param matchThreshold a frame's best score must clear this to "vote" for that profile at
     *   all. Placeholder default — Picovoice doesn't publish a universal similarity threshold,
     *   and the real value depends on real phone-mic/room-noise data from a hardware spike; treat
     *   as provisional until tuned against real recordings.
     * @param minVotes how many frames must agree on the same best profile before the verdict is
     *   trusted — the hysteresis that keeps one noisy frame from flipping the label.
     */
    fun resolve(
        scoresPerFrame: List<FloatArray>,
        matchThreshold: Float = DEFAULT_MATCH_THRESHOLD,
        minVotes: Int = DEFAULT_MIN_VOTES,
    ): Verdict {
        val usableFrames = scoresPerFrame.filter { it.isNotEmpty() }
        if (usableFrames.size < minVotes) return Verdict.InsufficientSignal

        val votes = mutableMapOf<Int, Int>()
        for (frame in usableFrames) {
            val bestIndex = frame.indices.maxBy { frame[it] }
            if (frame[bestIndex] >= matchThreshold) {
                votes[bestIndex] = (votes[bestIndex] ?: 0) + 1
            }
        }

        if (votes.isEmpty()) return Verdict.Unknown

        // Check the vote count before the tie, not after: two leaders short of minVotes each
        // just means "not enough data yet" (InsufficientSignal), not a confident split decision
        // between two people (Unknown) — only a tie *at or past* minVotes is genuine ambiguity.
        val maxVotes = votes.values.max()
        if (maxVotes < minVotes) return Verdict.InsufficientSignal

        val leaders = votes.filterValues { it == maxVotes }.keys
        if (leaders.size > 1) return Verdict.Unknown

        return Verdict.Recognized(leaders.single())
    }

    private const val DEFAULT_MATCH_THRESHOLD = 0.6f
    private const val DEFAULT_MIN_VOTES = 3
}
