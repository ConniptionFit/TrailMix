package com.trailmix.app.data.ai

sealed class AiAvailability {
    data object Available : AiAvailability()
    data object Downloadable : AiAvailability()
    data object Downloading : AiAvailability()
    data class Unavailable(val reason: String) : AiAvailability()
}

data class ProcessedNoteContent(
    val title: String,
    val summary: String,
    val mergedMarkdown: String,
    val usedOnDeviceAi: Boolean,
)
