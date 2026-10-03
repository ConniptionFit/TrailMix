package com.trailmix.app.data.speech

import com.trailmix.app.data.model.TranscriptLine

/**
 * SPK-03: what people *say* about who is speaking. Pure and Android-free.
 *
 * Direction matters and is the classic way to get this wrong, so each cue carries it:
 *  - [Kind.SELF_INTRO] ("this is Rob", "Rob here"): the person speaking on this line.
 *  - [Kind.ADDRESS_NEXT] ("Priya, can you take this?", "over to Priya"): the person who
 *    answers next, not the speaker of this line.
 *  - [Kind.THANKED_PREVIOUS] ("thanks Priya"): the person who spoke just before.
 * A bare third-person mention ("Priya said...") is deliberately no cue at all.
 */
object NameCues {
    enum class Kind(val weight: Double) {
        SELF_INTRO(3.0),
        ADDRESS_NEXT(1.5),
        THANKED_PREVIOUS(1.5),
    }

    data class Cue(val lineIndex: Int, val personIndex: Int, val kind: Kind)

    /** A self-introduction has to open the line; later "this is" is introducing someone else. */
    private const val INTRO_WINDOW_TOKENS = 6

    private val FILLER_OPENERS = setOf("hey", "hi", "hello", "okay", "ok", "so", "and", "right", "alright", "well", "thanks", "thank", "you", "again", "much")

    /** Words that follow a name when it is being asked something. */
    private val QUESTION_FOLLOWERS = setOf("can", "could", "would", "will", "what", "do", "how", "any", "you", "your", "did", "are", "should", "want")

    /** Names that are also everyday words: they only count behind an unambiguous introduction. */
    private val AMBIGUOUS_NAMES = setOf("will", "mark", "grace", "hope", "bill", "rob", "art", "pat", "sue", "rich", "dick", "drew", "frank", "may", "bob")

    private val ADDRESS_LEAD_INS = listOf(
        listOf("over", "to"),
        listOf("hear", "from"),
        listOf("go", "ahead"),
        listOf("handing", "it", "to"),
        listOf("hand", "it", "to"),
        listOf("question", "for"),
        listOf("turn", "it", "over", "to"),
        listOf("pass", "it", "to"),
    )

    fun extract(lines: List<TranscriptLine>, roster: Roster): List<Cue> {
        if (roster.isEmpty) return emptyList()
        val cues = mutableListOf<Cue>()
        lines.forEachIndexed { i, line ->
            val words = tokenize(line.text)
            if (words.isEmpty()) return@forEachIndexed
            roster.people.forEachIndexed { p, person ->
                if (p == roster.meIndex) return@forEachIndexed
                for (alias in person.aliases) {
                    var from = 0
                    while (true) {
                        val at = indexOf(words, alias, from)
                        if (at < 0) break
                        from = at + 1
                        classify(words, at, alias.size, alias.size == 1 && alias.first() in AMBIGUOUS_NAMES).forEach {
                            cues += Cue(i, p, it)
                        }
                    }
                }
            }
        }
        return cues.distinct()
    }

    private class Word(val text: String, val commaAfter: Boolean)

    private fun tokenize(text: String): List<Word> {
        val words = mutableListOf<Word>()
        val current = StringBuilder()
        fun flush(comma: Boolean) {
            if (current.isNotEmpty()) {
                words += Word(current.toString().trim('\''), comma)
                current.clear()
            }
        }
        text.lowercase().forEach { c ->
            when {
                c.isLetter() || c == '\'' || c == '’' -> current.append(if (c == '’') '\'' else c)
                c == ',' -> flush(true)
                else -> flush(false)
            }
        }
        flush(false)
        return words.filter { it.text.isNotEmpty() }
    }

    private fun indexOf(words: List<Word>, alias: List<String>, from: Int): Int {
        for (i in from..words.size - alias.size) {
            if (alias.indices.all { words[i + it].text == alias[it] }) return i
        }
        return -1
    }

    private fun classify(words: List<Word>, at: Int, length: Int, ambiguous: Boolean): List<Kind> {
        val before = words.subList(0, at).map { it.text }
        val after = words.drop(at + length).map { it.text }
        val afterComma = words[at + length - 1].commaAfter

        // Self-introduction: opens the line, in one of a few fixed phrasings.
        if (at <= INTRO_WINDOW_TOKENS) {
            val strongLead = endsWith(before, "this", "is") || endsWith(before, "my", "name", "is")
            val softLead = endsWith(before, "i", "am") || endsWith(before, "i'm") || endsWith(before, "im")
            if (strongLead || (softLead && !ambiguous)) return listOf(Kind.SELF_INTRO)
            if (!ambiguous && at <= 3 && after.firstOrNull() in setOf("here", "speaking")) return listOf(Kind.SELF_INTRO)
        }

        val kinds = mutableListOf<Kind>()
        // Thanking someone points at the previous speaker.
        val thanking = endsWith(before, "thanks") || endsWith(before, "thank", "you") || endsWith(before, "thanks", "again") ||
            endsWith(before, "thanks", "so", "much") || endsWith(before, "thank", "you", "so", "much")
        if (thanking && !ambiguous) kinds += Kind.THANKED_PREVIOUS

        // Asking someone something points at the next speaker.
        if (ADDRESS_LEAD_INS.any { endsWith(before, *it.toTypedArray()) }) {
            kinds += Kind.ADDRESS_NEXT
        } else {
            val opening = before.size <= 5 && before.all { it in FILLER_OPENERS }
            if (opening && !ambiguous && (afterComma || after.firstOrNull() in QUESTION_FOLLOWERS)) kinds += Kind.ADDRESS_NEXT
        }
        return kinds
    }

    private fun endsWith(words: List<String>, vararg tail: String): Boolean =
        words.size >= tail.size && tail.indices.all { words[words.size - tail.size + it] == tail[it] }
}
