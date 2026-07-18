package com.trailmix.app.data.ai

import org.json.JSONArray
import org.json.JSONObject

data class Recipe(val name: String, val prompt: String)

/** Built-in saved-prompt list (per design handoff). User-defined recipes (UX-06) are
 * appended after these on Chat & Recipes and run through the identical execution path. */
val DEFAULT_RECIPES = listOf(
    Recipe(
        name = "Follow-up email",
        prompt = "Draft a short, friendly follow-up email based on this note. " +
            "Include agreed dates and owners. Plain text, no subject line preamble beyond 'Subject:'.",
    ),
    Recipe(
        name = "Create ticket",
        prompt = "Turn this note into a work ticket: one-line title, short description, " +
            "and a bullet list of acceptance criteria.",
    ),
    Recipe(
        name = "Summarize",
        prompt = "Summarize this note in 3 short bullet points.",
    ),
    Recipe(
        name = "Action items",
        prompt = "List every action item from this note as '- [owner] task — due date if mentioned'.",
    ),
)

/**
 * JSON codec for the user-defined recipe list (UX-06, v1.7.0) — DataStore-backed, same
 * fail-soft decode contract as the other codecs in `data/model`: malformed input returns
 * an empty list rather than crashing, entries missing either field are skipped.
 */
object RecipesJson {
    fun encode(recipes: List<Recipe>): String {
        val arr = JSONArray()
        recipes.forEach { arr.put(JSONObject().put("n", it.name).put("p", it.prompt)) }
        return arr.toString()
    }

    fun decode(json: String?): List<Recipe> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("n").trim()
                val prompt = o.optString("p").trim()
                if (name.isBlank() || prompt.isBlank()) null else Recipe(name, prompt)
            }
        }.getOrDefault(emptyList())
    }
}
