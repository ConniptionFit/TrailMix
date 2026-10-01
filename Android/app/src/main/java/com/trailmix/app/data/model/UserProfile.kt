package com.trailmix.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Who "Me" is (AI-21): a short, user-entered profile that a later stage feeds into the
 * on-device note prompt so the model writes from the note-taker's perspective. Pure and
 * Android-free. Stored as one DataStore JSON string (no Room change) and never leaves the device.
 *
 * Gemini Nano's context is tiny, so every field is hard-capped here — [normalized] is applied
 * on decode and by [UserProfileJson.encode], so no caller can store or prompt an oversized value.
 */
data class UserProfile(
    val name: String = "",
    val role: String = "",
    val company: String = "",
    val focusAreas: List<String> = emptyList(),
) {
    /** True when nothing usable is set (blank fields count as unset). */
    val isEmpty: Boolean
        get() = name.isBlank() && role.isBlank() && company.isBlank() && focusAreas.none { it.isNotBlank() }

    /** Trimmed, whitespace-collapsed, length-capped copy; focus areas deduped and count-capped. */
    fun normalized(): UserProfile = UserProfile(
        name = name.clean(MAX_NAME),
        role = role.clean(MAX_ROLE),
        company = company.clean(MAX_COMPANY),
        focusAreas = focusAreas.map { it.clean(MAX_FOCUS) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_FOCUS_AREAS),
    )

    /**
     * One compact sentence for an LLM prompt, e.g. "The note-taker is Sam, Engineering Manager
     * at Acme, focused on hiring and roadmap.", or null when the profile is empty.
     */
    fun promptLine(): String? {
        val p = normalized()
        if (p.isEmpty) return null
        val job = when {
            p.role.isNotEmpty() && p.company.isNotEmpty() -> "${p.role} at ${p.company}"
            p.role.isNotEmpty() -> p.role
            else -> ""
        }
        val lead = "The note-taker"
        val sb = StringBuilder(lead)
        when {
            p.name.isNotEmpty() -> {
                sb.append(" is ").append(p.name)
                if (job.isNotEmpty()) {
                    sb.append(", ").append(job)
                } else if (p.company.isNotEmpty()) {
                    sb.append(", who works at ").append(p.company)
                }
            }
            job.isNotEmpty() -> sb.append(" works as ").append(job)
            p.company.isNotEmpty() -> sb.append(" works at ").append(p.company)
        }
        if (p.focusAreas.isNotEmpty()) {
            sb.append(if (sb.length > lead.length) ", focused on " else " is focused on ")
            sb.append(joinNatural(p.focusAreas))
        }
        return sb.append('.').toString()
    }

    companion object {
        const val MAX_NAME = 60
        const val MAX_ROLE = 80
        const val MAX_COMPANY = 80
        const val MAX_FOCUS = 50
        const val MAX_FOCUS_AREAS = 6

        /** Parse the comma/semicolon/newline-separated text the Settings dialog edits. */
        fun parseFocusAreas(text: String): List<String> =
            text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        private fun String.clean(max: Int): String =
            trim().replace(Regex("\\s+"), " ").take(max).trim()

        private fun joinNatural(items: List<String>): String = when (items.size) {
            1 -> items[0]
            2 -> "${items[0]} and ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
        }
    }
}

/** JSON codec for [UserProfile]; fail-soft — null/blank/malformed decodes to an empty profile. */
object UserProfileJson {
    fun encode(profile: UserProfile): String {
        val p = profile.normalized()
        val focus = JSONArray()
        p.focusAreas.forEach { focus.put(it) }
        return JSONObject()
            .put("name", p.name)
            .put("role", p.role)
            .put("company", p.company)
            .put("focus", focus)
            .toString()
    }

    fun decode(json: String?): UserProfile {
        if (json.isNullOrBlank()) return UserProfile()
        return runCatching {
            val o = JSONObject(json)
            val arr = o.optJSONArray("focus")
            UserProfile(
                name = o.optString("name", ""),
                role = o.optString("role", ""),
                company = o.optString("company", ""),
                focusAreas = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it, "") },
            ).normalized()
        }.getOrDefault(UserProfile())
    }
}
