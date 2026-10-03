package com.trailmix.app.data.speech

import kotlin.math.sqrt

/** SPK-04: the little vector maths voiceprints need. Pure and Android-free. */
object VoiceMath {
    /** Cosine similarity in -1..1, or 0 for mismatched sizes or a zero vector (no evidence). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** A unit-length copy, or null for a zero or empty vector. */
    fun normalized(v: FloatArray): FloatArray? {
        var n = 0.0
        for (x in v) n += x * x
        if (n == 0.0 || v.isEmpty()) return null
        val inv = (1.0 / sqrt(n)).toFloat()
        return FloatArray(v.size) { v[it] * inv }
    }

    /**
     * The weighted mean direction of [items] (each normalized first, so a loud or long clip
     * cannot dominate by magnitude), as a unit vector. Null when nothing usable remains or the
     * vectors disagree on size.
     */
    fun weightedMean(items: List<Pair<FloatArray, Double>>): FloatArray? {
        val usable = items.mapNotNull { (v, w) -> normalized(v)?.let { it to w } }.filter { it.second > 0.0 }
        val dim = usable.firstOrNull()?.first?.size ?: return null
        val sum = DoubleArray(dim)
        for ((v, w) in usable) {
            if (v.size != dim) return null
            for (i in 0 until dim) sum[i] += v[i] * w
        }
        return normalized(FloatArray(dim) { sum[it].toFloat() })
    }
}
