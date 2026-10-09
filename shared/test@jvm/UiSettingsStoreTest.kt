package ink.lipoly.app.sunrise

import ink.lipoly.app.sunrise.settings.ThemeMode
import ink.lipoly.app.sunrise.settings.UiSettings
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiSettingsStoreTest {
    @Test fun catalogChoicesPersistButAppearanceRemainsProcessLocal() = withPreferences { preferences ->
        val store = JvmUiSettingsStore(preferences)
        val bindings = mapOf("AA:BB:CC:DD:EE:FF" to "12345678-1234-1234-1234-123456789abc")
        val targetUuid = "87654321-4321-4321-4321-cba987654321"
        val next = store.current.copy(
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            amoled = true,
            seedIndex = 3,
            showWind = false,
            catalogOnlyDevices = false,
            showReferenceResponse = false,
            includeResponsePreGain = false,
            referenceProductByAddress = bindings,
            targetProductUuid = targetUuid,
            showTargetResponse = false,
        )
        store.update(next)
        preferences.flush()
        assertEquals(next, store.current)
        assertEquals(
            setOf("catalog_only_devices", "show_reference_response", "include_response_pregain", "reference_products",
                "target_product_uuid", "show_target_response"),
            preferences.keys().toSet(),
        )
        val restored = JvmUiSettingsStore(preferences).current
        assertEquals(
            UiSettings(
                catalogOnlyDevices = false,
                showReferenceResponse = false,
                includeResponsePreGain = false,
                referenceProductByAddress = bindings,
                targetProductUuid = targetUuid,
                showTargetResponse = false,
            ),
            restored,
        )
        store.update(store.current.copy(targetProductUuid = null))
        preferences.flush()
        assertNull(store.current.targetProductUuid)
        val cleared = JvmUiSettingsStore(preferences).current
        assertNull(cleared.targetProductUuid)
        assertFalse(cleared.showTargetResponse)
        assertEquals(bindings, cleared.referenceProductByAddress)
        assertFalse("target_product_uuid" in preferences.keys())
    }

    @Test fun appearanceOnlyChangesDoNotCreatePersistentSettings() = withPreferences { preferences ->
        val store = JvmUiSettingsStore(preferences)
        store.update(store.current.copy(themeMode = ThemeMode.LIGHT, seedIndex = 2))
        assertTrue(preferences.keys().isEmpty())
        assertEquals(ThemeMode.LIGHT, store.current.themeMode)
        assertEquals(UiSettings(), JvmUiSettingsStore(preferences).current)
    }

    @Test fun brokenBindingJsonDoesNotResetOtherCatalogPreferences() = withPreferences { preferences ->
        preferences.putBoolean("catalog_only_devices", false)
        preferences.putBoolean("show_reference_response", false)
        preferences.put("reference_products", "not valid JSON")
        val restored = JvmUiSettingsStore(preferences).current
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
