package ink.lipoly.app.sunrise.compose

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiSettingsRulesTest {
    @Test fun explicitLanguageOverridesSystemLanguage() {
        assertFalse(UiLanguage.SYSTEM.usesEnglish("zh"))
        assertTrue(UiLanguage.SYSTEM.usesEnglish("en"))
        assertTrue(UiLanguage.ENGLISH.usesEnglish("zh"))
        assertFalse(UiLanguage.CHINESE.usesEnglish("en"))
    }

    @Test fun themeModeOverridesSystemBrightness() {
        assertTrue(UiSettings(themeMode = ThemeMode.SYSTEM).isDark(true))
        assertFalse(UiSettings(themeMode = ThemeMode.SYSTEM).isDark(false))
        assertFalse(UiSettings(themeMode = ThemeMode.LIGHT).isDark(true))
        assertTrue(UiSettings(themeMode = ThemeMode.DARK).isDark(false))
    }
}
