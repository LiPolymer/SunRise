package ink.lipoly.app.sunrise.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class ThemeMode { SYSTEM, LIGHT, DARK }

internal data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoled: Boolean = false,
    val seedIndex: Int = 0,
    val showWind: Boolean = true,
    val catalogOnlyDevices: Boolean = true,
    val showReferenceResponse: Boolean = true,
    val includeResponsePreGain: Boolean = true,
    val referenceProductByAddress: Map<String, String> = emptyMap(),
    val targetProductUuid: String? = null,
    val showTargetResponse: Boolean = true,
)

internal fun encodeReferenceProducts(bindings: Map<String, String>): String =
    JsonObject(bindings.mapKeys { it.key.uppercase() }.mapValues { JsonPrimitive(it.value) }).toString()

internal fun decodeReferenceProducts(value: String?): Map<String, String> {
    if (value.isNullOrBlank()) return emptyMap()
    return try {
        val root = Json.parseToJsonElement(value) as? JsonObject ?: return emptyMap()
        buildMap {
            for ((address, element) in root) {
                if (element !is JsonPrimitive || !element.isString) return emptyMap()
                put(address.uppercase(), element.content)
            }
        }
    } catch (_: IllegalArgumentException) {
        emptyMap()
    }
}


internal fun UiSettings.isDark(systemDark: Boolean): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
