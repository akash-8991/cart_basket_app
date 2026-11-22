package com.smartcart.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Change-control guard: user-facing copy must live in string resources so post-freeze copy and
 * translation fixes never touch Kotlin. Fails if a Kotlin string literal in the app or design
 * system looks like English copy (a capitalised word of 3+ letters).
 *
 * Exempt: comments, the debug-only Developer screen, and a short allowlist of technical
 * identifiers. Server-provided text (reasons, explanations, item names) arrives as data, not literals.
 */
class NoHardcodedCopyTest {
    private val roots = listOf(File("src/main/kotlin"), File("../core/designsystem/src/main/kotlin"))
    private val exemptFiles = setOf("DeveloperScreen.kt")
    private val allowed = setOf(
        "AndroidKeyStore",               // Keystore provider name
        "AES/GCM/NoPadding",             // cipher transformation
        "AppContainer not provided",     // developer error, never shown to users
    )
    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val copyLike = Regex("\\b[A-Z][a-z]{2,}\\b")

    @Test
    fun noUserFacingStringLiteralsInKotlin() {
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" && it.name !in exemptFiles }.toList() }
        assertTrue("source roots not found from ${File(".").absolutePath}", files.size > 10)
        val offenders = mutableListOf<String>()
        for (f in files) {
            var inBlockComment = false
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("/*")) inBlockComment = true
                if (inBlockComment) {
                    if (line.contains("*/")) inBlockComment = false
                    return@forEachIndexed
                }
                if (line.startsWith("//") || line.startsWith("import ")) return@forEachIndexed
                val code = raw.substringBefore(" // ")
                literal.findAll(code).forEach { m ->
                    val value = m.groupValues[1]
                    if (value in allowed) return@forEach
                    // Ignore interpolated expressions and route placeholders such as {quoteId}.
                    val text = value.replace(Regex("\\$\\{[^}]*}"), "").replace(Regex("\\{[^}]*}"), "")
                    if (copyLike.containsMatchIn(text)) offenders += "${f.name}:${i + 1}: \"$value\""
                }
            }
        }
        assertTrue("Move these strings to res/values/strings_*.xml:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
