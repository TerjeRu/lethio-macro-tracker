package com.lethio.macros

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** Dutch as an interface language, and the locale list that must match the folders. */
class InterfaceLanguageTest {

    @Test
    fun `NL selects Dutch`() {
        assertThat(SettingsManager.localeFor("NL")?.language).isEqualTo("nl")
        assertThat(SettingsManager.languageFor("NL")).isEqualTo("nl")
    }

    @Test
    fun `a Dutch reader on NEVO gets Dutch food names first, then English`() {
        assertThat(SettingsManager.derivedFoodLanguages("", "nl", listOf("nl", "en")))
            .containsExactly("nl", "en")
            .inOrder()
    }

    @Test
    fun `DA selects Danish and FI selects Finnish`() {
        assertThat(SettingsManager.localeFor("DA")?.language).isEqualTo("da")
        assertThat(SettingsManager.languageFor("DA")).isEqualTo("da")
        assertThat(SettingsManager.localeFor("FI")?.language).isEqualTo("fi")
        assertThat(SettingsManager.languageFor("FI")).isEqualTo("fi")
    }

    @Test
    fun `Danish and Finnish readers get Frida and Fineli names in their own language first`() {
        assertThat(SettingsManager.derivedFoodLanguages("", "da", listOf("da", "en")))
            .containsExactly("da", "en")
            .inOrder()
        assertThat(SettingsManager.derivedFoodLanguages("", "fi", listOf("fi", "sv", "en")).first())
            .isEqualTo("fi")
    }

    @Test
    fun `locales_config lists exactly the translated values folders`() {
        // The config once declared cs, uk and pt-BR after those folders were gone, so the system
        // picker offered languages the app could not render. Read both sides from the real files.
        val res = repoPath("app/src/main/res")
        val folders =
            res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!
                .filter { File(it, "strings.xml").exists() }
                .map { it.name.removePrefix("values-") }
                .toSet()
        val declared =
            Regex("""android:name="([^"]+)"""")
                .findAll(File(res, "xml/locales_config.xml").readText())
                .map { it.groupValues[1] }
                .toSet()
        assertThat(declared).isEqualTo(folders + "en")
        assertThat(folders).containsAtLeast("nl", "da", "fi")
    }

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
