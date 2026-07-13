package com.trailmix.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/** Where a merged-note segment came from — drives the amber/teal source tinting. */
enum class Provenance { FRAGMENT, TRANSCRIPT }

/** One sentence/clause of a merged note, tagged with its source. */
data class NoteSegment(
    val text: String,
    val source: Provenance,
)

/** One finalized line of the live transcript. `label` is a mm:ss capture offset (no diarization on-device yet). */
data class TranscriptLine(
    val label: String,
    val text: String,
)

object SegmentsJson {
    fun encode(segments: List<NoteSegment>): String {
        val arr = JSONArray()
        segments.forEach {
            arr.put(JSONObject().put("t", it.text).put("s", it.source.name))
        }
        return arr.toString()
    }

    fun decode(json: String): List<NoteSegment> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NoteSegment(
                text = o.getString("t"),
                source = runCatching { Provenance.valueOf(o.getString("s")) }
                    .getOrDefault(Provenance.TRANSCRIPT),
            )
        }
    }.getOrDefault(emptyList())
}

object TranscriptJson {
    fun encode(lines: List<TranscriptLine>): String {
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().put("l", it.label).put("t", it.text)) }
        return arr.toString()
    }

    fun decode(json: String): List<TranscriptLine> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TranscriptLine(label = o.optString("l"), text = o.getString("t"))
        }
    }.getOrDefault(emptyList())
}
