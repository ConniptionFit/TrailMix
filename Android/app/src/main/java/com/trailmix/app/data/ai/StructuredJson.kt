package com.trailmix.app.data.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * AI-23: reading the structured-summary reply from a model that cannot be trusted to finish it.
 *
 * ML Kit's prompt API caps a reply at 256 tokens (`maxOutputTokens must be between 1 and 256`),
 * about 900 characters of this JSON. The Granola-shaped note (several sections, sub-bullets, Next
 * Steps with owners) routinely needs more, so Gemini Nano's reply is cut off mid-key, and it also
 * fences the JSON or nests `"sections"` inside itself. A strict `JSONObject(reply)` then threw
 * and the whole summary silently degraded to [DeterministicSummary] — no model bullets, no Next
 * Steps. This keeps what the model did finish: [repair] cuts a truncated reply back to its last
 * complete element and closes what is still open; [sectionObjects] finds sections wherever they
 * ended up in the tree. Pure, so the real failing reply is a unit-test fixture.
 */
object StructuredJson {

    /** The reply as JSON, plus whether it had to be cut back (so actions after the cut are lost). */
    data class Parsed(val root: JSONObject, val truncated: Boolean)

    fun parse(raw: String): Parsed? {
        val (repaired, truncated) = scan(raw)
        if (repaired.isNotBlank()) {
            runCatching { JSONObject(repaired) }.getOrNull()?.let { return Parsed(it, truncated) }
        }
        return harvest(raw)
    }

    /**
     * Last resort for a reply that is not JSON even after [repair] (Gemini Nano has been seen to
     * leave the `sections` array unclosed before `actionItems`): every complete `{...}` object
     * that is a section (`heading` + `bullets`) or an action item (`text` + `owner`/`deadline`) is
     * kept, wherever it sits, and a clean root is rebuilt from them. Marked truncated when no
     * action item was recovered, so the caller asks for them separately.
     */
    private fun harvest(raw: String): Parsed? {
        val sections = JSONArray()
        val actions = JSONArray()
        val starts = ArrayDeque<Int>()
        var inString = false
        var escaped = false
        for (i in raw.indices) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> starts.addLast(i)
                '}' -> starts.removeLastOrNull()?.let { start ->
                    val obj = runCatching { JSONObject(raw.substring(start, i + 1)) }.getOrNull() ?: return@let
                    when {
                        obj.has("heading") && obj.has("bullets") -> sections.put(obj)
                        obj.has("text") && (obj.has("owner") || obj.has("deadline")) -> actions.put(obj)
                    }
                }
            }
        }
        if (sections.length() == 0 && actions.length() == 0) return null
        val root = JSONObject().put("sections", sections).put("actionItems", actions)
        return Parsed(root, truncated = actions.length() == 0)
    }

    /**
     * The first `{` onward, with a trailing code fence or prose after the root object dropped. A
     * reply that stops before the root closes is cut back to just after its last complete element
     * (an object or array that closed) and the still-open containers are closed, so no
     * half-written key or string survives. Blank when there is no object to salvage.
     */
    fun repair(raw: String): String = scan(raw).first

    /** [repair]'s text, and whether the root object never closed (the reply was cut off). */
    private fun scan(raw: String): Pair<String, Boolean> {
        val start = raw.indexOf('{')
        if (start < 0) return "" to false
        val text = raw.substring(start)

        val open = ArrayDeque<Char>() // the closer each open container is waiting for
        var inString = false
        var escaped = false
        var safeEnd = -1
        var safeClosers = ""
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> open.addLast('}')
                '[' -> open.addLast(']')
                '}', ']' -> if (open.lastOrNull() == c) {
                    open.removeLast()
                    if (open.isEmpty()) return text.substring(0, i + 1) to false
                    safeEnd = i + 1
                    safeClosers = open.reversed().joinToString("")
                }
            }
        }
        return if (safeEnd < 0) "" to true else (text.substring(0, safeEnd) + safeClosers) to true
    }

    /**
     * Every object under [node] that has a `heading`, in document order. The model sometimes nests
     * the next section inside `{"sections": [...]}` instead of continuing the array; the sections
     * are still well-formed, so they are collected rather than discarded with the malformed
     * wrapper.
     */
    fun sectionObjects(node: Any?): List<JSONObject> = when (node) {
        is JSONObject ->
            if (node.has("heading")) {
                listOf(node)
            } else {
                node.keys().asSequence().flatMap { sectionObjects(node.opt(it)) }.toList()
            }
        is JSONArray -> (0 until node.length()).flatMap { sectionObjects(node.opt(it)) }
        else -> emptyList()
    }
}
