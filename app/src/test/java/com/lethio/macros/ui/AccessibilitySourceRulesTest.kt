package com.lethio.macros.ui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Two accessibility rules checked across the source: every `KeyboardOptions` declares an IME
 * action, and every `isError` field has `supportingText`. Neither failure shows up in a running
 * test, and both are about every call site, so this scans the source.
 */
class AccessibilitySourceRulesTest {

    private val screens: List<File> =
        repoPath("app/src/main/java/com/lethio/macros/ui")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `every KeyboardOptions declares an imeAction`() {
        // With keyboardType only, the IME shows its default action and there is no way to advance
        // between the four numeric fields on Goals, Quick add, Add product or the goal wizard
        // without dismissing the keyboard and tapping the next field.
        val offenders = mutableListOf<String>()
        for (file in screens) {
            val text = file.readText()
            var from = 0
            while (true) {
                val at = text.indexOf("KeyboardOptions(", from)
                if (at < 0) break
                from = at + 1
                val call = balancedCall(text, at + "KeyboardOptions".length)
                if (call != null && !call.contains("imeAction")) {
                    offenders += "${file.name}:${lineOf(text, at)}"
                }
            }
        }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every isError field carries a supportingText`() {
        // An invalid form field is marked by both a red outline *and* its own error message, with
        // no exceptions: a red outline alone is no signal to someone who cannot see it.
        //
        // Checked per composable call rather than per file: a screen with four fields, three of
        // them correct, is exactly the shape the missing one hid in.
        val offenders = mutableListOf<String>()
        for (file in screens) {
            val text = file.readText()
            var from = 0
            while (true) {
                val at = text.indexOf("OutlinedTextField(", from)
                if (at < 0) break
                from = at + 1
                val call = balancedCall(text, at + "OutlinedTextField".length) ?: continue
                if (call.contains("isError =") && !call.contains("supportingText =")) {
                    offenders += "${file.name}:${lineOf(text, at)}"
                }
            }
        }
        assertThat(offenders).isEmpty()
    }

    /** The text between the parenthesis at [openAt] and its match, or null if it is unbalanced. */
    private fun balancedCall(text: String, openAt: Int): String? {
        if (openAt >= text.length || text[openAt] != '(') return null
        var depth = 0
        for (i in openAt until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openAt + 1, i)
                }
            }
        }
        return null
    }

    private fun lineOf(text: String, offset: Int) =
        text.substring(0, offset).count { it == '\n' } + 1

    /** Resolves a repo-relative path regardless of which directory Gradle runs from. */
    private fun repoPath(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("could not find $relative walking up from ${File("").absolutePath}")
    }
}
