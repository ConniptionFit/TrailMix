package com.trailmix.app.data.speech

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * SPK-04: one enrolled voice. Several of these can describe one [VoicePerson] because the same
 * voice sounds different through different channels (phone mic, speakerphone, headset).
 * [centroid] is a unit vector; [weight] is how many samples it summarises, capped so a print
 * keeps adapting. Only embeddings are stored, never audio.
 */
class Voiceprint(val centroid: FloatArray, val weight: Double, val updatedAtMs: Long)

class VoicePerson(
    val id: String,
    val name: String,
    /** The note-taker's own voice. */
    val isMe: Boolean,
    val prints: List<Voiceprint>,
)

/** A confident-enough match of one speaker's voice to an enrolled person. */
data class VoiceMatch(
    val personId: String,
    val name: String,
    val similarity: Float,
    val margin: Float,
    /** The match is the note-taker's own enrolled voice. */
    val isMe: Boolean = false,
) {
    val tierScore: Double
        get() = when {
            similarity >= VoiceMatcher.CONFIDENT_SIMILARITY && margin >= VoiceMatcher.CONFIDENT_MARGIN -> SpeakerFusion.CONFIDENT_SCORE
            else -> SpeakerFusion.SUGGESTED_SCORE
        }
}

/**
 * SPK-04: decides whether a speaker's voice is an enrolled person's. Pure.
 *
 * The thresholds are **provisional**: they are in the range the speaker-embedding model's
 * published verification results suggest, not tuned on TrailMix's own phone-mic recordings
 * (that needs the evaluation corpus the plan calls for). They only act when the user has
 * turned speaker recognition on, and a match below [CONFIDENT_SIMILARITY] is only ever shown
 * as a question ("Speaker 2 (Priya?)"), never as a plain name.
 */
object VoiceMatcher {
    /** Below this a voice is simply not matched. */
    const val MIN_SIMILARITY = 0.50f

    /** At or above this (and with [CONFIDENT_MARGIN] over the runner-up) a match is confident. */
    const val CONFIDENT_SIMILARITY = 0.65f
    const val CONFIDENT_MARGIN = 0.10f

    /** A new sample this close to an existing print refines it instead of becoming a new one. */
    const val SAME_CHANNEL_SIMILARITY = 0.70f
    const val MAX_PRINTS_PER_PERSON = 4

    /** A print stops averaging in new samples as if it were infinitely old past this weight. */
    const val MAX_WEIGHT = 50.0

    fun similarityTo(person: VoicePerson, centroid: FloatArray): Float =
        person.prints.maxOfOrNull { VoiceMath.cosine(it.centroid, centroid) } ?: 0f

    /** The best enrolled person for [centroid], or null if nobody clears [MIN_SIMILARITY]. */
    fun match(centroid: FloatArray, people: List<VoicePerson>): VoiceMatch? {
        val ranked = people.map { it to similarityTo(it, centroid) }.sortedByDescending { it.second }
        val (best, sim) = ranked.firstOrNull() ?: return null
        if (sim < MIN_SIMILARITY) return null
        val runnerUp = ranked.getOrNull(1)?.second ?: 0f
        return VoiceMatch(best.id, best.name, sim, sim - runnerUp, best.isMe)
    }

    /**
     * Match every speaker at once, never giving one person to two speakers: the strongest
     * pairing is settled first, and the other speaker then sees only the people left.
     */
    fun matchAll(centroids: Map<String, FloatArray>, people: List<VoicePerson>): Map<String, VoiceMatch> {
        val result = LinkedHashMap<String, VoiceMatch>()
        val remaining = centroids.toMutableMap()
        val available = people.toMutableList()
        while (remaining.isNotEmpty() && available.isNotEmpty()) {
            // Margin is judged against everyone enrolled, not just who is left.
            val best = remaining.mapNotNull { (label, c) ->
                match(c, available)?.let { m ->
                    val runnerUp = people.filter { it.id != m.personId }.maxOfOrNull { similarityTo(it, c) } ?: 0f
                    label to m.copy(margin = m.similarity - runnerUp)
                }
            }.maxByOrNull { it.second.similarity } ?: break
            result[best.first] = best.second
            remaining.remove(best.first)
            available.removeAll { it.id == best.second.personId }
        }
        return result
    }

    /**
     * [person] with one more sample folded in: it refines the closest print when it sounds like
     * the same channel, otherwise it becomes a new print (the weakest is dropped past the cap).
     */
    fun withSample(person: VoicePerson, centroid: FloatArray, weight: Double, nowMs: Long): VoicePerson {
        val unit = VoiceMath.normalized(centroid) ?: return person
        if (weight <= 0.0) return person
        val closest = person.prints.indices.maxByOrNull { VoiceMath.cosine(person.prints[it].centroid, unit) }
        val prints = person.prints.toMutableList()
        if (closest != null && VoiceMath.cosine(prints[closest].centroid, unit) >= SAME_CHANNEL_SIMILARITY) {
            val old = prints[closest]
            val blended = VoiceMath.weightedMean(listOf(old.centroid to old.weight, unit to weight)) ?: return person
            prints[closest] = Voiceprint(blended, minOf(old.weight + weight, MAX_WEIGHT), nowMs)
        } else {
            prints += Voiceprint(unit, minOf(weight, MAX_WEIGHT), nowMs)
            if (prints.size > MAX_PRINTS_PER_PERSON) prints.remove(prints.minBy { it.weight })
        }
        return VoicePerson(person.id, person.name, person.isMe, prints)
    }
}

/** SPK-04: JSON for the people file. Embeddings go in as base64 little-endian floats. */
object VoicePeopleJson {
    fun encode(people: List<VoicePerson>): String {
        val arr = JSONArray()
        people.forEach { p ->
            val prints = JSONArray()
            p.prints.forEach { v ->
                prints.put(
                    JSONObject().put("v", floatsToBase64(v.centroid)).put("w", v.weight).put("t", v.updatedAtMs),
                )
            }
            arr.put(JSONObject().put("id", p.id).put("n", p.name).put("me", p.isMe).put("p", prints))
        }
        return JSONObject().put("people", arr).toString()
    }

    fun decode(json: String): List<VoicePerson> = decodeOrNull(json).orEmpty()

    /** Null when [json] is not a people file at all (as opposed to one holding nobody). */
    fun decodeOrNull(json: String): List<VoicePerson>? = runCatching {
        val arr = JSONObject(json).getJSONArray("people")
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val prints = o.getJSONArray("p")
                VoicePerson(
                    id = o.getString("id"),
                    name = o.getString("n"),
                    isMe = o.optBoolean("me", false),
                    prints = (0 until prints.length()).mapNotNull { j ->
                        runCatching {
                            val v = prints.getJSONObject(j)
                            Voiceprint(base64ToFloats(v.getString("v")), v.getDouble("w"), v.optLong("t", 0L))
                        }.getOrNull()
                    },
                )
            }.getOrNull()
        }
    }.getOrNull()

    /** One note's speaker centroids, by the label they carry in that note's transcript. */
    fun encodeCentroids(byLabel: Map<String, FloatArray>): String {
        val o = JSONObject()
        byLabel.forEach { (label, c) -> o.put(label, floatsToBase64(c)) }
        return o.toString()
    }

    fun decodeCentroids(json: String): Map<String, FloatArray> = runCatching {
        val o = JSONObject(json)
        o.keys().asSequence().associateWith { base64ToFloats(o.getString(it)) }
    }.getOrDefault(emptyMap())

    internal fun floatsToBase64(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    internal fun base64ToFloats(s: String): FloatArray {
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buf.remaining() / 4) { buf.getFloat() }
    }
}

/** SPK-04: pure edits to the list of enrolled people. */
object VoicePeople {
    /** [people] with [centroid] folded into the person called [name] (created if new). */
    fun withVoice(
        people: List<VoicePerson>,
        name: String,
        isMe: Boolean,
        centroid: FloatArray,
        weight: Double,
        nowMs: Long,
        newId: () -> String,
    ): List<VoicePerson> {
        val clean = name.trim()
        if (clean.isEmpty()) return people
        val existing = people.firstOrNull { if (isMe) it.isMe else (!it.isMe && it.name.equals(clean, ignoreCase = true)) }
        val base = existing ?: VoicePerson(newId(), clean, isMe, emptyList())
        val updated = VoiceMatcher.withSample(base, centroid, weight, nowMs)
        if (updated.prints.isEmpty()) return people
        return if (existing == null) people + updated else people.map { if (it.id == existing.id) updated else it }
    }

    /**
     * For each raw label in [pre] (the transcript before speaker naming) the label its lines
     * carry in [post] (after), matched by position. This is what a note displays, so it is the
     * key the note's centroids are saved under.
     */
    fun finalLabels(pre: List<com.trailmix.app.data.model.TranscriptLine>, post: List<com.trailmix.app.data.model.TranscriptLine>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (i in pre.indices) {
            val before = pre[i].speakerLabel ?: continue
            val after = post.getOrNull(i)?.speakerLabel ?: continue
            result.putIfAbsent(before, after)
        }
        return result
    }
}
