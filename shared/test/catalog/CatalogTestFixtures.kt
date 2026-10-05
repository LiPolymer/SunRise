package ink.lipoly.app.sunrise.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object CatalogTestFixtures {
    const val UUID = "11111111-1111-4111-8111-111111111111"
    const val SECOND_UUID = "22222222-2222-4222-8222-222222222222"
    const val PATH = "BT/Ultra response.txt"
    const val RETRIEVED_AT = "2026-10-04T00:00:00Z"
    val responseBytes: ByteArray get() = "* preserved comment\r\n100 40\r\n1000 60\r\n".encodeToByteArray()

    fun product(
        uuid: String = UUID,
        name: String = "SPACE TRAVEL 2 ULTRA",
        path: String? = PATH,
        language: String? = "en-US",
    ): JsonObject = buildJsonObject {
        put("uuid", uuid)
        put("name", name)
        put("model", "Ultra")
        put("type", "BT")
        put("languageType", language)
        put("freqResponse", path)
        put("futureVendorField", buildJsonObject { put("preserve", "raw metadata") })
    }

    fun catalogue(
        products: List<JsonElement> = listOf(product()),
        code: JsonElement = JsonPrimitive(0),
    ): ByteArray = buildJsonObject {
        put("code", code)
        put("data", JsonArray(products))
        put("unknownEnvelopeField", "also retained")
    }.toString().encodeToByteArray()

    fun asset(bytes: ByteArray): JsonObject {
        var encoding = "utf-8"
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            encoding = "iso-8859-1"
            CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
        }
        return buildJsonObject {
            put("sha256", catalogSha256(bytes))
            put("encoding", encoding)
            put("lines", JsonArray(text.splitToSequence('\n').map(::JsonPrimitive).toList()))
        }
    }

    fun assetBytes(asset: JsonObject): ByteArray {
        val text = (asset.getValue("lines") as JsonArray).joinToString("\n") { (it as JsonPrimitive).content }
        return when ((asset.getValue("encoding") as JsonPrimitive).content) {
            "utf-8" -> text.encodeToByteArray()
            "iso-8859-1" -> ByteArray(text.length) { text[it].code.toByte() }
            else -> error("Unsupported fixture encoding")
        }
    }

    // Deliberately independent of encodeCatalogSnapshot, allowing invalid structural test inputs.
    fun document(
        catalogueBytes: ByteArray = catalogue(),
        responseFiles: Map<String, ByteArray> = mapOf(PATH to responseBytes),
    ): ByteArray = buildJsonObject {
        put("format", CATALOG_FORMAT)
        put("schemaVersion", CATALOG_SCHEMA_VERSION)
        put("retrievedAt", RETRIEVED_AT)
        put("catalogueUrl", CATALOGUE_URL)
        put("cdnBaseUrl", CATALOG_CHINA_CDN_URL)
        put("catalogue", root(catalogueBytes))
        put("responseFiles", JsonArray(responseFiles.map { (path, bytes) ->
            JsonObject(asset(bytes) + ("path" to JsonPrimitive(path)))
        }))
        put("unknownRootField", buildJsonObject { put("preserved", true) })
    }.toString().encodeToByteArray()

    fun snapshot(
        catalogueBytes: ByteArray = catalogue(),
        responseFiles: Map<String, ByteArray> = mapOf(PATH to responseBytes),
    ): CatalogSnapshot = decodeCatalogSnapshot(document(catalogueBytes, responseFiles))

    fun root(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()) as JsonObject

    fun changed(bytes: ByteArray, field: String, value: JsonElement): ByteArray =
        JsonObject(root(bytes) + (field to value)).toString().encodeToByteArray()
}
