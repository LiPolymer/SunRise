package ink.lipoly.app.sunrise

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ink.lipoly.app.sunrise.composeLegacy.UiSettings
import ink.lipoly.app.sunrise.composeLegacy.decodeReferenceProducts
import ink.lipoly.app.sunrise.composeLegacy.encodeReferenceProducts
import java.util.prefs.Preferences

/** Only catalog choices persist; appearance and language retain their process-local behavior. */
internal class UiSettingsStore(
    private val preferences: Preferences = Preferences.userRoot().node("ink/lipoly/app/sunrise/catalog"),
) {
    var current by mutableStateOf(
        UiSettings(
            catalogOnlyDevices = preferences.getBoolean("catalog_only_devices", true),
            showReferenceResponse = preferences.getBoolean("show_reference_response", true),
            includeResponsePreGain = preferences.getBoolean("include_response_pregain", true),
            referenceProductByAddress = decodeReferenceProducts(preferences.get("reference_products", null)),
            targetProductUuid = preferences.get("target_product_uuid", null),
            showTargetResponse = preferences.getBoolean("show_target_response", true),
        )
    )
        private set

    fun update(next: UiSettings) {
        val previous = current
        if (next.catalogOnlyDevices != previous.catalogOnlyDevices) {
            preferences.putBoolean("catalog_only_devices", next.catalogOnlyDevices)
        }
        if (next.showReferenceResponse != previous.showReferenceResponse) {
            preferences.putBoolean("show_reference_response", next.showReferenceResponse)
        }
        if (next.includeResponsePreGain != previous.includeResponsePreGain) {
            preferences.putBoolean("include_response_pregain", next.includeResponsePreGain)
        }
        if (next.referenceProductByAddress != previous.referenceProductByAddress) {
            preferences.put("reference_products", encodeReferenceProducts(next.referenceProductByAddress))
        }
        if (next.targetProductUuid != previous.targetProductUuid) {
            if (next.targetProductUuid == null) preferences.remove("target_product_uuid")
            else preferences.put("target_product_uuid", next.targetProductUuid)
        }
        if (next.showTargetResponse != previous.showTargetResponse) {
            preferences.putBoolean("show_target_response", next.showTargetResponse)
        }
        current = next
    }
}
