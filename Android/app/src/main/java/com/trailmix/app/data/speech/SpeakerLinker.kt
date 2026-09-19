package com.trailmix.app.data.speech

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager

/**
 * CAP-30 (2026-09-19): turns a per-window local speaker id into a session-global one, using
 * voice-embedding similarity so the same person keeps the same global id across separate
 * diarization windows — [SherpaOnnxDiarizer] now diarizes in windows rather than one
 * whole-session call (see its own doc for why), and each window's own local `speakerTag` ints
 * start over at 0 and carry no meaning across windows: `OfflineSpeakerDiarization` clusters only
 * within the one call it was given.
 *
 * Backed by sherpa-onnx's own [SpeakerEmbeddingManager] rather than a hand-rolled
 * cosine-similarity loop — nearest-embedding search plus register-if-new is exactly this
 * class's job, done in native code the SDK already ships and this project already trusts
 * elsewhere (AI-12's planned Phase 4 named-speaker recognition is built on the same class).
 *
 * Not unit-testable on the JVM without the native library present — same category as
 * [SherpaOnnxDiarizer] itself.
 */
class SpeakerLinker(private val manager: SpeakerEmbeddingManager) {
    private var nextGlobalId = 0
    private val nameToGlobalId = mutableMapOf<String, Int>()

    /**
     * [embedding] should be whatever [com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor.compute]
     * returns directly — already normalized the way the manager's `search`/`add` expect.
     * Returns a stable small int: the same real speaker (by embedding similarity, within
     * [threshold]) always maps to the same id across calls, whichever window it was first
     * seen in.
     */
    fun globalIdFor(embedding: FloatArray, threshold: Float): Int {
        // search()'s Java signature doesn't declare nullability, but a genuine "no match"
        // does return null at runtime — an explicit String? keeps Kotlin's inference from
        // treating it as a non-null platform type here.
        val matched: String? = manager.search(embedding, threshold)
        val existing = matched?.let { nameToGlobalId[it] }
        if (existing != null) return existing
        val name = "spk$nextGlobalId"
        val id = nextGlobalId++
        nameToGlobalId[name] = id
        // A failed add() (native error) just means this speaker won't be recognized again if
        // they speak in a later window — a missed match, not a crash or a skipped segment.
        runCatching { manager.add(name, embedding) }
        return id
    }

    fun release() {
        runCatching { manager.release() }
    }
}
