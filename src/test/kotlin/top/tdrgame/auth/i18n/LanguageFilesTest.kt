package top.tdrgame.auth.i18n

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import top.tdrgame.auth.i18n.I18nKeys
import top.tdrgame.auth.i18n.ServerI18n
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageFilesTest {

    @Test
    fun `english and chinese language files expose the same keys`() {
        val english = readLanguageKeys("en_us")
        val chinese = readLanguageKeys("zh_cn")

        val declared = I18nKeys::class.java.fields
            .filter { it.type == String::class.java }
            .map { it.get(null) as String }
            .toSortedSet()

        assertTrue(english.isNotEmpty(), "en_us language file must contain keys")
        assertEquals(english, chinese, "English and Chinese language files must define identical keys")
        assertTrue(english.containsAll(declared), "language files must contain all I18nKeys constants")
        assertTrue(declared.all { ServerI18n.fallback(it) != it || it.startsWith("tauth.gui.") },
            "server-visible keys must define fallback text")
    }

    private fun readLanguageKeys(locale: String): Set<String> {
        val path = Path.of("src/main/resources/assets/tauth/lang/$locale.json")
        assertTrue(Files.exists(path), "$locale language file must exist")
        return Regex("\\\"([^\\\"]+)\\\"\\s*:")
            .findAll(Files.readString(path))
            .map { it.groupValues[1] }
            .toSortedSet()
    }
}
