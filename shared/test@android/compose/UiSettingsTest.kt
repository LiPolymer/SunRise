package ink.lipoly.app.sunrise.compose

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class UiSettingsTest {
    @Test fun settingsSurviveStoreRecreation() {
        val preferences = memoryPreferences()
        val first = UiSettingsStore(preferences)
        assertEquals(UiSettings(), first.current)

        val selected = UiSettings(
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            amoled = true,
            seedIndex = 3,
            language = UiLanguage.ENGLISH,
            showWind = false,
        )
        first.update(selected)

        assertEquals(selected, UiSettingsStore(preferences).current)
    }

    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putInt", "putBoolean" -> {
                    values[args!![0] as String] = args[1]
                    editor
                }
                "apply" -> null
                "commit" -> true
                else -> error("Unexpected editor method: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getInt", "getBoolean" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
    }
}
