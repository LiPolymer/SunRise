package ink.lipoly.app.sunrise.catalog

import ink.lipoly.app.sunrise.composeLegacy.ThemeMode
import ink.lipoly.app.sunrise.composeLegacy.UiLanguage
import ink.lipoly.app.sunrise.composeLegacy.UiSettings
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmCatalogSettingsTest {
    @Test fun catalogChoicesPersistButAppearanceRemainsProcessLocal() = withPreferences { preferences ->
        val store = JvmCatalogSettingsStore(preferences)
        val bindings = mapOf("AA:BB:CC:DD:EE:FF" to "12345678-1234-1234-1234-123456789abc")
        val next = store.current.copy(
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            amoled = true,
            seedIndex = 3,
            language = UiLanguage.ENGLISH,
            showWind = false,
            catalogOnlyDevices = false,
            showReferenceResponse = false,
            includeResponsePreGain = false,
            referenceProductByAddress = bindings,
        )
        store.update(next)
        preferences.flush()
        assertEquals(next, store.current)
        assertEquals(
            setOf("catalog_only_devices", "show_reference_response", "include_response_pregain", "reference_products"),
            preferences.keys().toSet(),
        )
        val restored = JvmCatalogSettingsStore(preferences).current
        assertEquals(
            UiSettings(
                catalogOnlyDevices = false,
                showReferenceResponse = false,
                includeResponsePreGain = false,
                referenceProductByAddress = bindings,
            ),
            restored,
        )
    }

    @Test fun appearanceOnlyChangesDoNotCreatePersistentSettings() = withPreferences { preferences ->
        val store = JvmCatalogSettingsStore(preferences)
        store.update(store.current.copy(themeMode = ThemeMode.LIGHT, seedIndex = 2))
        assertTrue(preferences.keys().isEmpty())
        assertEquals(ThemeMode.LIGHT, store.current.themeMode)
        assertEquals(UiSettings(), JvmCatalogSettingsStore(preferences).current)
    }

    @Test fun brokenBindingJsonDoesNotResetOtherCatalogPreferences() = withPreferences { preferences ->
        preferences.putBoolean("catalog_only_devices", false)
        preferences.putBoolean("show_reference_response", false)
        preferences.put("reference_products", "not valid JSON")
        val restored = JvmCatalogSettingsStore(preferences).current
        assertFalse(restored.catalogOnlyDevices)
        assertFalse(restored.showReferenceResponse)
        assertTrue(restored.includeResponsePreGain)
        assertTrue(restored.referenceProductByAddress.isEmpty())
    }

    private fun withPreferences(test: (Preferences) -> Unit) {
        val preferences = Preferences.userRoot().node("ink/lipoly/app/sunrise/catalog-tests/${UUID.randomUUID()}")
        try {
            test(preferences)
        } finally {
            preferences.removeNode()
            Preferences.userRoot().flush()
        }
    }
}
