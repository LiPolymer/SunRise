package ink.lipoly.app.sunrise.catalog

import kotlinx.serialization.json.JsonObject

internal data class CatalogProduct(
    val uuid: String,
    val name: String,
    val model: String?,
    val languageType: String?,
    val freqResponse: String?,
    val raw: JsonObject,
)

internal sealed interface CatalogResponse {
    data class Ready(val response: FrequencyResponse) : CatalogResponse
    data class Unavailable(val reason: String) : CatalogResponse
}

internal class CatalogSnapshot(
    val documentBytes: ByteArray,
    val retrievedAt: String,
    val catalogueUrl: String,
    val cdnBaseUrl: String,
    val products: List<CatalogProduct>,
    val responsesByPath: Map<String, CatalogResponse>,
    val responseHashesByPath: Map<String, String>,
) {
    val productsByUuid: Map<String, CatalogProduct> = products.associateBy { it.uuid }
    val productsByNormalizedName: Map<String, List<CatalogProduct>> = products.groupBy {
        requireNotNull(normalizeCatalogName(it.name))
    }
}

internal fun normalizeCatalogName(name: String?): String? {
    if (name == null) return null
    val normalized = buildString(name.length) {
        var pendingSpace = false
        for (character in name) {
            if (character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }
    return normalized.takeIf { it.isNotEmpty() }?.lowercase()
}

internal expect fun catalogSha256(bytes: ByteArray): String
