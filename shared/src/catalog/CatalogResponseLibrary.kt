package ink.lipoly.app.sunrise.catalog

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val responseProductType = JsonPrimitive("Response")

internal data class CatalogResponseLibraryEntry(
    val uuid: String,
    val name: String,
    val file: String,
    val tags: List<String>,
    val raw: JsonObject,
) {
    fun toCatalogProduct(): CatalogProduct = CatalogProduct(
        uuid = uuid,
        name = name,
        type = responseProductType.content,
        model = null,
        languageType = null,
        freqResponse = file,
        raw = buildJsonObject {
            for ((key, value) in raw) put(key, value)
            put("type", responseProductType)
            put("freqResponse", file)
        },
    )
}
