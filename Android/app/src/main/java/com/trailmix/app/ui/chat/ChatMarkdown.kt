package com.trailmix.app.ui.chat

/** One rendered block of an assistant reply. */
sealed interface ChatBlock {
    val text: String

    data class Heading(override val text: String) : ChatBlock
    data class Paragraph(override val text: String) : ChatBlock
    data class Bullet(override val text: String) : ChatBlock
    data class Numbered(val number: Int, override val text: String) : ChatBlock
}

/** A run of inline text; [bold] comes from `**double asterisks**`. */
data class ChatSpan(val text: String, val bold: Boolean)

/**
 * K2: assistant replies render as light Markdown (headings, bullets, numbered steps, bold)
 * instead of one flat string with stray asterisks. Deliberately small: the model is asked for
 * plain text, so this only has to make the common shapes read well, and anything it does not
 * recognise falls through as a paragraph, never dropped.
 */
object ChatMarkdown {
    private val bulletRe = Regex("""^\s*[-*•]\s+(.*)$""")
    private val numberedRe = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")
    private val headingRe = Regex("""^\s*#{1,6}\s+(.*)$""")

    fun parse(reply: String): List<ChatBlock> {
        val blocks = mutableListOf<ChatBlock>()
        val paragraph = StringBuilder()

        fun flush() {
            if (paragraph.isNotBlank()) blocks += ChatBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }

        for (raw in reply.lines()) {
            val line = raw.trimEnd()
            val heading = headingRe.matchEntire(line)
            val bullet = bulletRe.matchEntire(line)
            val numbered = numberedRe.matchEntire(line)
            when {
                line.isBlank() -> {
                    flush()
                }

                heading != null -> {
                    flush()
                    blocks += ChatBlock.Heading(heading.groupValues[1].trim())
                }

                bullet != null -> {
                    flush()
                    blocks += ChatBlock.Bullet(bullet.groupValues[1].trim())
                }

                numbered != null -> {
                    flush()
                    blocks += ChatBlock.Numbered(numbered.groupValues[1].toInt(), numbered.groupValues[2].trim())
                }

                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    /** Splits on `**`; an unmatched trailing marker is kept as literal text. */
    fun spans(text: String): List<ChatSpan> {
        val parts = text.split("**")
        if (parts.size < 3 || parts.size % 2 == 0) return listOf(ChatSpan(text.replace("**", ""), bold = false))
        return parts.mapIndexedNotNull { i, part ->
            if (part.isEmpty()) null else ChatSpan(part, bold = i % 2 == 1)
        }
    }

    /** Plain text of a block, for copying a reply without the markers. */
    fun plain(reply: String): String = parse(reply).joinToString("\n") { block ->
        val text = spans(block.text).joinToString("") { it.text }
        when (block) {
            is ChatBlock.Bullet -> "• $text"
            is ChatBlock.Numbered -> "${block.number}. $text"
            else -> text
        }
    }
}
