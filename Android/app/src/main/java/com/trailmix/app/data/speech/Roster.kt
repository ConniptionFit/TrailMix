package com.trailmix.app.data.speech

/**
 * SPK-03: the calendar attendee list as people whose names can be recognised in speech.
 * Pure and Android-free.
 *
 * The note stores attendees as plain strings that are a display name or, when the calendar
 * has no name, an e-mail address; [parse] copes with both ("rob.smith@acme.com" is Rob Smith,
 * "Smith, Rob" is Rob Smith). A first name or nickname is an alias only while it is unique in
 * the room: with two Robs, "Rob" proves nothing about either, so neither gets it.
 */
class Roster private constructor(
    val people: List<Person>,
    /** The entry that is the note-taker, matched on [com.trailmix.app.data.model.UserProfile.name]; null when unknown. */
    val meIndex: Int?,
) {
    class Person(
        val display: String,
        val tokens: List<String>,
        /** Lower-case token sequences that identify this person, longest first. */
        val aliases: List<List<String>>,
    )

    val isEmpty: Boolean get() = people.isEmpty()

    /** The attendee that [name] (an enrolled person's name) refers to, or null if none or several do. */
    fun indexOfName(name: String): Int? {
        val tokens = nameTokens(name) ?: return null
        people.indices.singleOrNull { people[it].tokens == tokens }?.let { return it }
        return people.indices.singleOrNull { i -> people[i].aliases.any { it == tokens } }
    }

    companion object {
        val EMPTY = Roster(emptyList(), null)

        fun parse(entries: List<String>, userName: String?): Roster {
            val parsed = entries.mapNotNull { nameTokens(it) }
                .distinctBy { it.joinToString(" ") }
            if (parsed.isEmpty()) return EMPTY

            val firstCounts = parsed.groupingBy { it.first() }.eachCount()
            val people = parsed.map { tokens ->
                val aliases = LinkedHashSet<List<String>>()
                if (tokens.size > 1) aliases += tokens
                val first = tokens.first()
                if (firstCounts[first] == 1) {
                    aliases += listOf(first)
                    nicknamesOf(first).forEach { nick ->
                        // A nickname shared with another attendee's own first name is not ours.
                        if (parsed.none { other -> other !== tokens && other.first() == nick }) aliases += listOf(nick)
                    }
                }
                Person(display = tokens.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }, tokens = tokens, aliases = aliases.toList())
            }
            // An alias claimed by two people is nobody's.
            val owners = HashMap<List<String>, Int>()
            people.forEach { p -> p.aliases.forEach { owners[it] = (owners[it] ?: 0) + 1 } }
            val unique = people.map { p ->
                Person(p.display, p.tokens, p.aliases.filter { owners[it] == 1 }.sortedByDescending { it.size })
            }
            return Roster(unique, meIndexFor(unique, userName))
        }

        private fun meIndexFor(people: List<Person>, userName: String?): Int? {
            val me = nameTokens(userName ?: return null) ?: return null
            val matches = people.indices.filter { i ->
                val t = people[i].tokens
                t == me || (me.size == 1 && t.first() == me.first()) || (t.first() == me.first() && t.last() == me.last())
            }
            return matches.singleOrNull()
        }

        /** Lower-case name words, or null when [entry] holds no letters. */
        internal fun nameTokens(entry: String): List<String>? {
            var s = entry.trim()
            if (s.contains('@')) s = s.substringBefore('@').replace('.', ' ').replace('_', ' ').replace('-', ' ')
            if (s.contains(',')) s = s.substringAfter(',').trim() + " " + s.substringBefore(',').trim()
            val words = s.lowercase().split(' ', '\t').map { w -> w.filter { it.isLetter() || it == '\'' } }
                .filter { it.isNotEmpty() }
            return words.takeIf { it.isNotEmpty() && it.size <= 4 }
        }

        private fun nicknamesOf(first: String): List<String> =
            NICKNAME_GROUPS.firstOrNull { first in it }?.filter { it != first }.orEmpty()

        private val NICKNAME_GROUPS = listOf(
            setOf("robert", "rob", "robby", "bob", "bobby"),
            setOf("william", "bill", "billy", "will"),
            setOf("michael", "mike", "mikey"),
            setOf("james", "jim", "jimmy", "jamie"),
            setOf("thomas", "tom", "tommy"),
            setOf("david", "dave", "davey"),
            setOf("christopher", "chris"),
            setOf("steven", "stephen", "steve"),
            setOf("katherine", "catherine", "kate", "katie", "kathy"),
            setOf("elizabeth", "liz", "beth", "lizzie"),
            setOf("jennifer", "jen", "jenny"),
            setOf("samuel", "sam"),
            setOf("alexander", "alex"),
            setOf("matthew", "matt"),
            setOf("daniel", "dan", "danny"),
            setOf("nicholas", "nick"),
            setOf("anthony", "tony"),
            setOf("joseph", "joe"),
            setOf("benjamin", "ben"),
            setOf("andrew", "andy", "drew"),
            setOf("patrick", "pat"),
            setOf("edward", "ed", "eddie", "ted"),
            setOf("richard", "rick", "rich", "dick"),
            setOf("jonathan", "jon"),
            setOf("rebecca", "becca", "becky"),
            setOf("margaret", "maggie", "meg"),
            setOf("susan", "sue", "suzy"),
            setOf("victoria", "vicky", "tori"),
        )
    }
}
