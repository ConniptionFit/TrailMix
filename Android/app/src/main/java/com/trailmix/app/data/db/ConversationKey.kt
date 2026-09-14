package com.trailmix.app.data.db

/**
 * Cross-note chat (AI-10): the canonical identity of a multi-note conversation is the set of
 * notes it covers, not a separately allocated id — see [ConversationMessageEntity]. Sorted so
 * selecting the same notes in a different order still resumes the same conversation; deduped
 * because a selection set can't meaningfully contain a note twice.
 */
object ConversationKey {
    fun of(noteIds: Collection<Long>): String = noteIds.toSortedSet().joinToString(",")
}
