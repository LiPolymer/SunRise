package ink.lipoly.app.sunrise.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

internal const val CATALOG_FORMAT = "sunrise-moondrop-catalog"
internal const val CATALOG_SCHEMA_VERSION = 3
internal const val CATALOGUE_URL = "https://cdn-service.moondroplab.tech/api/v1/products/all"
internal const val CATALOG_RESPONSE_LIBRARY_URL = "https://cdn-service.moondroplab.tech/api/v1/responselib/allwithtag"
internal const val CATALOG_CHINA_CDN_URL = "https://cdn.moondroplab.tech/"
internal const val CATALOG_OVERSEAS_CDN_URL = "https://kaigai.cdn.moondroplab.tech/"
internal const val MAX_CATALOG_SNAPSHOT_BYTES = 64 * 1024 * 1024
internal const val MAX_CATALOGUE_BYTES = 2 * 1024 * 1024
internal const val MAX_RESPONSE_FILE_BYTES = 2 * 1024 * 1024
internal const val MAX_CATALOG_ASSET_BYTES = 48 * 1024 * 1024

private val catalogJson = Json { prettyPrint = true }
private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val sha256Pattern = Regex("[0-9a-f]{64}")

private fun JsonObject.string(field: String): String {
    val value = this[field] as? JsonPrimitive
    require(value != null && value.isString) { "$field must be a string" }
    return value.content
}

private fun JsonObject.nonblankString(field: String): String = string(field).also {
    require(it.isNotBlank()) { "$field must not be blank" }
}

private fun JsonObject.optionalString(field: String): String? {
    val value = this[field] ?: return null
    if (value == JsonNull) return null
    require(value is JsonPrimitive && value.isString) { "$field must be a string or null" }
    return value.content.takeIf { it.isNotBlank() }
}

private fun JsonObject.integer(field: String): Int {
    val value = this[field] as? JsonPrimitive
    require(value != null && !value.isString) { "$field must be an integer" }
    return value.content.toIntOrNull() ?: throw IllegalArgumentException("$field must be an integer")
}

private fun parseObject(bytes: ByteArray, label: String): JsonObject {
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("$label must be valid UTF-8", error)
    }
    val element = try {
        catalogJson.parseToJsonElement(text.removePrefix("\uFEFF"))
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("$label must be valid JSON: ${error.message}", error)
    }
    return element as? JsonObject ?: throw IllegalArgumentException("$label must be an object")
}

internal fun validateCatalogResponsePath(path: String) {
    require(path.isNotBlank()) { "Frequency response path must not be blank" }
    require(!path.startsWith('/') && '\\' !in path && ':' !in path && '?' !in path && '#' !in path) {
        "Frequency response path must be a relative path without scheme, backslash, query or fragment: $path"
    }
    require(path.none { it.code < 0x20 || it.code in 0x7f..0x9f }) { "Frequency response path contains a control character" }
    require(path.split('/').none { it == "." || it == ".." }) { "Frequency response path contains a dot segment: $path" }
    var index = 0
    while (index < path.length) {
        val code = path[index].code
        if (code in 0xd800..0xdbff) {
            require(index + 1 < path.length && path[index + 1].code in 0xdc00..0xdfff) {
                "Frequency response path contains an unpaired Unicode surrogate"
            }
            index++
        } else {
            require(code !in 0xdc00..0xdfff) { "Frequency response path contains an unpaired Unicode surrogate" }
        }
        index++
    }
}

/** Retains every vendor object, including separate UUID/language records sharing the same name. */
internal fun parseCatalogProducts(bytes: ByteArray): List<CatalogProduct> {
    require(bytes.size <= MAX_CATALOGUE_BYTES) { "Catalogue exceeds 2 MiB" }
    return parseCatalogProducts(parseObject(bytes, "Catalogue"))
}

private fun parseCatalogProducts(root: JsonObject): List<CatalogProduct> {
    require(root.integer("code") == 0) { "Catalogue API code must be 0" }
    val records = root["data"] as? JsonArray ?: throw IllegalArgumentException("Catalogue data must be an array")
    require(records.isNotEmpty()) { "Catalogue data must not be empty" }
    val uuids = HashSet<String>()
    return records.mapIndexed { index, record ->
        val product = record as? JsonObject ?: throw IllegalArgumentException("Catalogue record $index must be an object")
        val uuid = product.string("uuid")
        require(uuidPattern.matches(uuid)) { "Catalogue record $index has an invalid UUID" }
        require(uuids.add(uuid.lowercase())) { "Catalogue contains duplicate UUID: $uuid" }
        val name = product.nonblankString("name")
        val type = product.nonblankString("type")
        val path = product.optionalString("freqResponse")?.also(::validateCatalogResponsePath)
        CatalogProduct(uuid, name, type, product.optionalString("model"), product.optionalString("languageType"), path, product)
    }
}

internal fun parseCatalogResponseLibrary(bytes: ByteArray): List<CatalogResponseLibraryEntry> {
    require(bytes.size <= MAX_CATALOGUE_BYTES) { "Response library exceeds 2 MiB" }
    return parseCatalogResponseLibrary(parseObject(bytes, "Response library"))
}

private fun parseCatalogResponseLibrary(root: JsonObject): List<CatalogResponseLibraryEntry> {
    require(root.integer("code") == 0) { "Response library API code must be 0" }
    val records = root["data"] as? JsonArray ?: throw IllegalArgumentException("Response library data must be an array")
    val uuids = HashSet<String>()
    return records.mapIndexed { index, record ->
        val entry = record as? JsonObject ?: throw IllegalArgumentException("Response library record $index must be an object")
        val uuid = entry.string("uuid")
        require(uuidPattern.matches(uuid)) { "Response library record $index has an invalid UUID" }
        require(uuids.add(uuid.lowercase())) { "Response library contains duplicate UUID: $uuid" }
        val name = entry.nonblankString("name")
        val file = entry.nonblankString("file").also(::validateCatalogResponsePath)
        val tags = entry["tags"] as? JsonArray ?: throw IllegalArgumentException("Response library tags must be an array")
        val tagValues = tags.map { tag ->
            require(tag is JsonPrimitive && tag.isString) { "Response library tags must be strings" }
            tag.content
        }
        CatalogResponseLibraryEntry(uuid, name, file, tagValues, entry)
    }
}

internal fun mergeCatalogProducts(
    products: List<CatalogProduct>,
    library: List<CatalogResponseLibraryEntry>,
): List<CatalogProduct> {
    val uuids = HashSet<String>(products.size + library.size)
    for (product in products) uuids.add(product.uuid.lowercase())
    val merged = ArrayList<CatalogProduct>(products.size + library.size)
    merged.addAll(products)
    for (entry in library) {
        require(uuids.add(entry.uuid.lowercase())) { "Catalogue and response library contain duplicate UUID: ${entry.uuid}" }
        merged.add(entry.toCatalogProduct())
    }
    return merged
}

private fun validateRetrievedAt(value: String) {
    require(value.endsWith('Z') || value.endsWith("+00:00")) { "retrievedAt must be a UTC timestamp" }
    try {
        Instant.parse(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("retrievedAt must be a valid UTC timestamp", error)
    }
}

private fun decodeResponseAsset(asset: JsonObject, label: String, maxBytes: Int): Pair<ByteArray, String> {
    val hash = asset.string("sha256")
    require(sha256Pattern.matches(hash)) { "$label SHA-256 must be 64 lowercase hexadecimal characters" }
    val encoding = asset.string("encoding")
    require(encoding == "utf-8" || encoding == "iso-8859-1") { "$label has unsupported encoding: $encoding" }
    val lines = asset["lines"] as? JsonArray ?: throw IllegalArgumentException("$label lines must be an array")
    require(lines.isNotEmpty()) { "$label lines must contain at least one string" }
    var textLength = lines.size - 1L
    for (element in lines) {
        val line = element as? JsonPrimitive
        require(line != null && line.isString && '\n' !in line.content) { "$label lines must be strings without LF" }
        textLength += line.content.length
        require(textLength <= maxBytes) { "$label exceeds its decoded byte limit" }
    }
    val text = lines.joinToString("\n") { (it as JsonPrimitive).content }
    val bytes = if (encoding == "utf-8") {
        try {
            text.encodeToByteArray(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("$label contains invalid Unicode", error)
        }
    } else {
        ByteArray(text.length) { index ->
            val code = text[index].code
            require(code <= 0xff) { "$label iso-8859-1 text contains a non-byte character" }
            code.toByte()
        }
    }
    require(bytes.size <= maxBytes) { "$label exceeds its decoded byte limit" }
    require(catalogSha256(bytes) == hash) { "$label SHA-256 mismatch" }
    return bytes to hash
}

internal fun decodeCatalogSnapshot(bytes: ByteArray): CatalogSnapshot {
    require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
    val root = parseObject(bytes, "Snapshot")
    require(root.string("format") == CATALOG_FORMAT) { "Unsupported catalogue snapshot format" }
    val version = root.integer("schemaVersion")
    require(version == CATALOG_SCHEMA_VERSION) { "Unsupported catalogue schemaVersion $version; a compatible application version is required" }
    val retrievedAt = root.string("retrievedAt").also(::validateRetrievedAt)
    val catalogueUrl = root.nonblankString("catalogueUrl")
    val responseLibraryUrl = root.nonblankString("responseLibraryUrl")
    val cdnBaseUrl = root.nonblankString("cdnBaseUrl")
    val catalogue = root["catalogue"] as? JsonObject ?: throw IllegalArgumentException("catalogue must be an object")
    val catalogueBytes = catalogue.toString().encodeToByteArray()
    require(catalogueBytes.size <= MAX_CATALOGUE_BYTES) { "Catalogue exceeds 2 MiB" }
    val responseLibrary = root["responseLibrary"] as? JsonObject ?: throw IllegalArgumentException("responseLibrary must be an object")
    val responseLibraryBytes = responseLibrary.toString().encodeToByteArray()
    require(responseLibraryBytes.size <= MAX_CATALOGUE_BYTES) { "Response library exceeds 2 MiB" }
    val library = parseCatalogResponseLibrary(responseLibrary)
    val products = mergeCatalogProducts(parseCatalogProducts(catalogue), library)
    val expectedPaths = products.mapNotNullTo(LinkedHashSet()) { it.freqResponse }
    val files = root["responseFiles"] as? JsonArray ?: throw IllegalArgumentException("responseFiles must be an array")
    val responses = LinkedHashMap<String, CatalogResponse>()
    val hashes = LinkedHashMap<String, String>()
    var assetBytes = catalogueBytes.size + responseLibraryBytes.size
    for ((index, element) in files.withIndex()) {
        val file = element as? JsonObject ?: throw IllegalArgumentException("responseFiles[$index] must be an object")
        val path = file.string("path").also(::validateCatalogResponsePath)
        require(path in expectedPaths) { "Unexpected frequency response asset: $path" }
        require(path !in responses) { "Duplicate frequency response asset: $path" }
        val (content, hash) = decodeResponseAsset(file, "Frequency response $path", minOf(MAX_RESPONSE_FILE_BYTES, MAX_CATALOG_ASSET_BYTES - assetBytes))
        assetBytes += content.size
        hashes[path] = hash
        responses[path] = try {
            CatalogResponse.Ready(parseFrequencyResponse(content))
        } catch (error: IllegalArgumentException) {
            CatalogResponse.Unavailable(requireNotNull(error.message))
        }
    }
    require(responses.keys == expectedPaths) { "Snapshot is missing frequency response assets: ${expectedPaths - responses.keys}" }
    return CatalogSnapshot(
        bytes, retrievedAt, catalogueUrl, cdnBaseUrl, products, responses, hashes,
        responseLibraryUrl, buildSet(library.size) { for (entry in library) add(entry.uuid) },
    )
}

private fun responseAsset(bytes: ByteArray, path: String): JsonObject {
    var encoding = "utf-8"
    val text = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        // A reversible byte mapping, not a guess at the vendor's original character set.
        encoding = "iso-8859-1"
        CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() }.concatToString()
    }
    return buildJsonObject {
        put("path", path)
        put("sha256", catalogSha256(bytes))
        put("encoding", encoding)
        put("lines", JsonArray(text.splitToSequence('\n').map(::JsonPrimitive).toList()))
    }
}

/** Nests both raw metadata envelopes and retains response bytes as readable, reversible text lines. */
internal fun encodeCatalogSnapshot(
    catalogueBytes: ByteArray,
    responseLibraryBytes: ByteArray,
    responseFiles: Map<String, ByteArray>,
    retrievedAt: String = Clock.System.now().toString(),
    cdnBaseUrl: String = CATALOG_CHINA_CDN_URL,
    catalogueUrl: String = CATALOGUE_URL,
    responseLibraryUrl: String = CATALOG_RESPONSE_LIBRARY_URL,
): ByteArray {
    require(catalogueBytes.size <= MAX_CATALOGUE_BYTES) { "Catalogue exceeds 2 MiB" }
    val catalogue = parseObject(catalogueBytes, "Catalogue")
    require(responseLibraryBytes.size <= MAX_CATALOGUE_BYTES) { "Response library exceeds 2 MiB" }
    val responseLibrary = parseObject(responseLibraryBytes, "Response library")
    val products = mergeCatalogProducts(parseCatalogProducts(catalogue), parseCatalogResponseLibrary(responseLibrary))
    val expectedPaths = products.mapNotNullTo(LinkedHashSet()) { it.freqResponse }
    require(responseFiles.keys == expectedPaths) { "Frequency response assets must exactly match merged catalogue paths" }
    var assetBytes = catalogueBytes.size.toLong() + responseLibraryBytes.size
    for ((path, content) in responseFiles) {
        validateCatalogResponsePath(path)
        require(content.size <= MAX_RESPONSE_FILE_BYTES) { "Frequency response $path exceeds 2 MiB" }
        assetBytes += content.size
    }
    require(assetBytes <= MAX_CATALOG_ASSET_BYTES) { "Decoded assets exceed 48 MiB" }
    validateRetrievedAt(retrievedAt)
    require(catalogueUrl.isNotBlank() && responseLibraryUrl.isNotBlank() && cdnBaseUrl.isNotBlank()) {
        "Catalogue, response library and CDN provenance URLs must not be blank"
    }
    val root = buildJsonObject {
        put("format", CATALOG_FORMAT)
        put("schemaVersion", CATALOG_SCHEMA_VERSION)
        put("retrievedAt", retrievedAt)
        put("catalogueUrl", catalogueUrl)
        put("responseLibraryUrl", responseLibraryUrl)
        put("cdnBaseUrl", cdnBaseUrl)
        put("catalogue", catalogue)
        put("responseLibrary", responseLibrary)
        put("responseFiles", JsonArray(responseFiles.map { (path, content) ->
            responseAsset(content, path)
        }))
    }
    val document = catalogJson.encodeToString(JsonObject.serializer(), root).encodeToByteArray()
    require(document.size <= MAX_CATALOG_SNAPSHOT_BYTES) { "Snapshot exceeds 64 MiB" }
    return document
}
