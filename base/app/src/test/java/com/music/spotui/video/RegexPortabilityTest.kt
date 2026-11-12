/* SPDX-License-Identifier: GPL-3.0-only */
package com.music.spotui.video

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regular expressions with ICU, which refuses a bare "}" that Java takes as a
 * letter (a bare "]" it takes too, as the lyrics' patterns show): a pattern the JVM tests pass threw
 * on the phone and the class holding it never loaded (MusicVideoMatch: every clip lookup failed).
 * Each raw-string Regex of the app is read here as ICU would, a closing brace only after an opening one.
 */
class RegexPortabilityTest {
    @Test fun noBareClosingBraceInTheAppsPatterns() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val literal = Regex("Regex\\(\"\"\"(.*?)\"\"\"")
        val bad = root.walk().filter { it.extension == "kt" }.flatMap { f ->
            literal.findAll(f.readText()).map { it.groupValues[1] }.filter { bare(it) != null }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("patterns ICU refuses (escape the closing }):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun theCheckSeesWhatItLooksFor() {
        assertTrue(bare("""\{.*?}""") != null)
        assertTrue(bare("""\[.*?]""") == null)
        assertTrue(bare("""\{.*?\}|\[.*?\]|[^\]a]|a{2,3}|[}]""") == null)
    }

    /** The index of a bare closing "}" in [p], or null. */
    private fun bare(p: String): Int? {
        var i = 0
        var inClass = false
        var classStart = -1
        var inQuantifier = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']' && i > classStart + 1 && !(i == classStart + 2 && p[classStart + 1] == '^')) inClass = false
                c == '[' -> { inClass = true; classStart = i }
                c == '{' -> inQuantifier = true
                c == '}' -> if (inQuantifier) inQuantifier = false else return i
            }
            i++
        }
        return null
    }
}
