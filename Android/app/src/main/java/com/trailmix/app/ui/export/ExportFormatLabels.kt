package com.trailmix.app.ui.export

import com.trailmix.app.data.export.ExportFormat

/**
 * Display strings for [ExportFormat] — shared between Settings' persisted-default picker and
 * the share-sheet's one-off picker so the two never drift apart.
 */
val ExportFormat.label: String
    get() = when (this) {
        ExportFormat.LLM_OPTIMIZED -> "LLM-optimized"
        ExportFormat.HUMAN_READABLE -> "Human-readable"
        ExportFormat.PLAIN_TEXT -> "Plain text"
    }
