package com.trailmix.app.data.ai

import org.json.JSONArray
import org.json.JSONObject

/** A user-taught correction: [wrong] is what the on-device ASR tends to mishear, [correct] is
 *  what it should read as instead — a proper noun, product name, or team-specific term. */
data class VocabularyTerm(val wrong: String, val correct: String)

/**
 * AI-08: dictionary-only jargon/vocabulary correction, applied as a post-process pass over ASR
 * output right as each line is finalized.
 *
 * A short spike of `genai-speech-recognition:1.0.0-alpha1`'s public API found no hotword or
 * phrase-biasing surface to feed a vocabulary into instead: `SpeechRecognizerOptions.Builder`
 * exposes only `executor`/`locale`/`preferredMode`, and `SpeechRecognizerRequest.Builder` only
 * an audio source (checked directly against the decompiled class members, not assumed). Simple
 * find/replace on the recognized text is therefore the only lever available on-device today,
 * not a fallback chosen over a richer one that could have been reached instead.
 */
object VocabularyCorrection {
    /** Applies every term in order, case-insensitively, matching whole words only so a short
     *  [VocabularyTerm.wrong] (e.g. "AI") doesn't match inside an unrelated longer word. */
    fun apply(text: String, terms: List<VocabularyTerm>): String {
        if (text.isBlank() || terms.isEmpty()) return text
        var result = text
        for (term in terms) {
            if (term.wrong.isBlank()) continue
            val pattern = Regex("\\b${Regex.escape(term.wrong)}\\b", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, Regex.escapeReplacement(term.correct))
        }
        return result
    }
}

/** JSON codec for the user-defined vocabulary list — same DataStore-list pattern as [RecipesJson]. */
object VocabularyJson {
    fun encode(terms: List<VocabularyTerm>): String {
        val arr = JSONArray()
        terms.forEach { arr.put(JSONObject().put("w", it.wrong).put("c", it.correct)) }
        return arr.toString()
    }

    fun decode(json: String?): List<VocabularyTerm> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val wrong = o.optString("w").trim()
                val correct = o.optString("c").trim()
                if (wrong.isBlank() || correct.isBlank()) null else VocabularyTerm(wrong, correct)
            }
        }.getOrDefault(emptyList())
    }
}
