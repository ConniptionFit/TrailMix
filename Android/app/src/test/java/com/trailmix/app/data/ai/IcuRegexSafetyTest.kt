package com.trailmix.app.data.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android's `java.util.regex` is ICU-backed; the JVM running these tests is not. ICU reads a
 * character set that opens with `[:` as a POSIX class (`[:alpha:]`) and scans ahead for a closing
 * `:]` anywhere later in the pattern, so `1\s*[:\-]\s*1(?![\d:])` compiles on the host and throws
 * "Incorrect Unicode property" on a phone. That broke every Auto-template merge in v1.22.0 and
 * passed unit tests, lint and CI. A unit test cannot run ICU, so this pins the one construct that
 * differs: write the colon anywhere but first (`[\-:]`).
 */
class IcuRegexSafetyTest {

    private val mainSources = File("src/main/java")

    @Test
    fun `no main source opens a character set with a colon`() {
        // An empty scan must not read as "clean": fail if the source tree was not found.
        val files = mainSources.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("expected Kotlin sources under ${mainSources.absolutePath}", files.size > 50)

        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.trimStart()
                val isComment = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
                if (!isComment && "[:" in line) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue(
            "Regex sets must not start with ':' (ICU reads `[:` as a POSIX class); use `[\\-:]`:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
