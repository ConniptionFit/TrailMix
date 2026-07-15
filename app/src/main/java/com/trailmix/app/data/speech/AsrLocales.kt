package com.trailmix.app.data.speech

import java.util.Locale

/**
 * Curated locale list for the ASR language picker (AI-02, v1.5.0). ML Kit GenAI speech
 * recognition's basic mode documents support for roughly 15 languages; this is a practical
 * subset rather than an exhaustive enumeration pulled from the SDK (it doesn't expose one at
 * build time) — extend as needed. `"en-US"` (the prior hardcoded default) stays first/default
 * so an upgrade with no picked setting behaves identically to before.
 */
object AsrLocales {
    data class Option(val tag: String, val label: String)

    val options: List<Option> = listOf(
        Option("en-US", "English (US)"),
        Option("en-GB", "English (UK)"),
        Option("es-ES", "Spanish"),
        Option("fr-FR", "French"),
        Option("de-DE", "German"),
        Option("it-IT", "Italian"),
        Option("pt-BR", "Portuguese (Brazil)"),
        Option("ja-JP", "Japanese"),
        Option("ko-KR", "Korean"),
        Option("hi-IN", "Hindi"),
    )

    val default = options.first()

    fun fromTag(tag: String?): Option = options.firstOrNull { it.tag == tag } ?: default

    fun toLocale(option: Option): Locale = Locale.forLanguageTag(option.tag)
}
