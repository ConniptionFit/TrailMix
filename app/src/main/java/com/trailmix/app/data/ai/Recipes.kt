package com.trailmix.app.data.ai

data class Recipe(val name: String, val prompt: String)

/** Static saved-prompt list for this pass (per design handoff). */
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
