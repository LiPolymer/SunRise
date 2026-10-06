package ink.lipoly.app.sunrise

import ink.lipoly.app.sunrise.settings.UiSettings
import ink.lipoly.app.sunrise.settings.UiSettingsStore
import ink.lipoly.app.sunrise.settings.decodeReferenceProducts
import ink.lipoly.app.sunrise.settings.encodeReferenceProducts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.prefs.Preferences

/** Only catalog choices persist; appearance and language retain their process-local behavior. */
internal class JvmUiSettingsStore(
    private val preferences: Preferences = Preferences.userRoot().node("ink/lipoly/app/sunrise/catalog"),
) : UiSettingsStore {
    private val mutableState = MutableStateFlow(
        UiSettings(
            catalogOnlyDevices = preferences.getBoolean("catalog_only_devices", true),
            showReferenceResponse = preferences.getBoolean("show_reference_response", true),
            includeResponsePreGain = preferences.getBoolean("include_response_pregain", true),
            referenceProductByAddress = decodeReferenceProducts(preferences.get("reference_products", null)),
            targetProductUuid = preferences.get("target_product_uuid", null),
            showTargetResponse = preferences.getBoolean("show_target_response", true),
        )
    )
    override val state: StateFlow<UiSettings> = mutableState.asStateFlow()

    override fun update(next: UiSettings) {
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
        mutableState.value = next
    }
}
