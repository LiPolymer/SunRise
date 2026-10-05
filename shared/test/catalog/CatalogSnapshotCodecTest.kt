package ink.lipoly.app.sunrise.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class CatalogSnapshotCodecTest {
    private val fixtures = CatalogTestFixtures

    @Test fun identicalNamesAndSharedAssetsRetainEveryUuidAndRawRecord() {
        val first = fixtures.product()
        val second = fixtures.product(uuid = fixtures.SECOND_UUID, name = "  SPACE   TRAVEL 2 ULTRA ", language = "zh-CN")
        val snapshot = fixtures.snapshot(fixtures.catalogue(listOf(first, second)))
        assertEquals(2, snapshot.products.size)
        assertEquals(setOf(fixtures.UUID, fixtures.SECOND_UUID), snapshot.productsByUuid.keys)
        assertEquals(2, snapshot.productsByNormalizedName.getValue("space travel 2 ultra").size)
        assertEquals(first, snapshot.productsByUuid.getValue(fixtures.UUID).raw)
        assertEquals(second, snapshot.productsByUuid.getValue(fixtures.SECOND_UUID).raw)
        assertEquals(setOf(fixtures.PATH), snapshot.responsesByPath.keys)
    }

    @Test fun nameIndexUsesOnlyCompleteNormalizedNamesAndUnicodeWhitespace() {
        val snapshot = fixtures.snapshot()
        assertEquals("space travel 2 ultra", normalizeCatalogName("\u2003 SPACE\u00A0\u00A0TRAVEL\t2 ULTRA \n"))
        assertNotNull(snapshot.productsByNormalizedName[requireNotNull(normalizeCatalogName("  SPACE   TRAVEL 2 ULTRA "))])
        assertNull(normalizeCatalogName(null))
        assertNull(normalizeCatalogName(" \t\u2003\u00A0"))
        assertNull(snapshot.productsByNormalizedName[requireNotNull(normalizeCatalogName("Ultra"))])
        assertNull(snapshot.productsByNormalizedName[requireNotNull(normalizeCatalogName("Space Travel 2 Ultra XYZ"))])
        assertEquals("moondrop: ultra", normalizeCatalogName("MOONDROP: Ultra"))
    }

    @Test fun nestedCatalogueMetadataAndOriginalResponsesSurviveDecodeAndExport() {
        val catalogue = ("\n  " + fixtures.catalogue().decodeToString() + "\r\n").encodeToByteArray()
        val response = "\uFEFF* Freq(Hz) SPL(dB) Phase(degrees)\r\n* raw EQ comment\r\n100 40 -90\r\n1000 60 180\r\n".encodeToByteArray()
        val document = " \n".encodeToByteArray() + fixtures.document(catalogue, mapOf(fixtures.PATH to response)) + "\r\n".encodeToByteArray()
        val snapshot = decodeCatalogSnapshot(document)
        assertContentEquals(document, snapshot.documentBytes)
        val exportedRoot = fixtures.root(snapshot.documentBytes)
        assertNotNull(exportedRoot["unknownRootField"])
        assertEquals(fixtures.root(catalogue), exportedRoot["catalogue"])
        val asset = (exportedRoot["responseFiles"] as JsonArray).single() as JsonObject
        assertContentEquals(response, fixtures.assetBytes(asset))
        assertEquals(catalogSha256(response), snapshot.responseHashesByPath.getValue(fixtures.PATH))
        assertEquals(snapshot.products.single().raw, decodeCatalogSnapshot(snapshot.documentBytes).products.single().raw)
    }

    @Test fun unavailableCurveStillRetainsItsAssetHashAndOriginalBytes() {
        val unsupported = "* original unknown format\r\n100 40 0\r\n1000 60 0\r\n".encodeToByteArray()
        val snapshot = fixtures.snapshot(responseFiles = mapOf(fixtures.PATH to unsupported))
        val unavailable = assertIs<CatalogResponse.Unavailable>(snapshot.responsesByPath.getValue(fixtures.PATH))
        assertTrue(unavailable.reason.contains("line 2"))
        assertEquals(1, snapshot.products.size)
        assertEquals(catalogSha256(unsupported), snapshot.responseHashesByPath.getValue(fixtures.PATH))
        val file = (fixtures.root(snapshot.documentBytes)["responseFiles"] as JsonArray).single() as JsonObject
        assertContentEquals(unsupported, fixtures.assetBytes(file))
    }

    @Test fun missingNullAndBlankOptionalFieldsDoNotInvalidateDirectory() {
        for (value in listOf(null, JsonNull, JsonPrimitive(""), JsonPrimitive(" \t "))) {
            var product = fixtures.product()
            for (field in listOf("model", "languageType", "freqResponse")) {
                product = JsonObject(if (value == null) product - field else product + (field to value))
            }
            val snapshot = fixtures.snapshot(fixtures.catalogue(listOf(product)), emptyMap())
            assertNull(snapshot.products.single().model)
            assertNull(snapshot.products.single().languageType)
            assertNull(snapshot.products.single().freqResponse)
            assertTrue(snapshot.responsesByPath.isEmpty())
        }
    }

    @Test fun catalogueRequiresSuccessNonemptyObjectRecordsAndValidRequiredFields() {
        for (bytes in listOf(
            fixtures.catalogue(code = JsonPrimitive(1)),
            fixtures.catalogue(code = JsonPrimitive("0")),
            fixtures.catalogue(code = JsonPrimitive(0.0)),
            fixtures.catalogue(emptyList()),
            fixtures.catalogue(listOf(JsonNull)),
            fixtures.catalogue(listOf(JsonPrimitive("record"))),
            "[]".encodeToByteArray(),
            "{\"code\":0,\"data\":{}}".encodeToByteArray(),
            "{\"data\":[]}".encodeToByteArray(),
            byteArrayOf(0xff.toByte()),
        )) assertFailsWith<IllegalArgumentException> { parseCatalogProducts(bytes) }
        for ((field, value) in listOf(
            "uuid" to JsonPrimitive("not-a-uuid"),
            "uuid" to JsonPrimitive(1),
            "name" to JsonPrimitive(" \t"),
            "name" to JsonNull,
            "type" to JsonPrimitive(" \t"),
            "type" to JsonNull,
            "type" to JsonPrimitive(1),
            "model" to JsonPrimitive(2),
            "languageType" to JsonObject(emptyMap()),
            "freqResponse" to JsonPrimitive(false),
        )) {
            val product = JsonObject(fixtures.product() + (field to value))
            assertFailsWith<IllegalArgumentException> { parseCatalogProducts(fixtures.catalogue(listOf(product))) }
        }
        for (field in listOf("uuid", "name", "type")) {
            assertFailsWith<IllegalArgumentException> {
                parseCatalogProducts(fixtures.catalogue(listOf(JsonObject(fixtures.product() - field))))
            }
        }
    }

    @Test fun mixedProductTypesRetainEveryRecordAndRequireAllReferencedAssets() {
        val products = listOf(
            fixtures.product(),
            fixtures.product(uuid = fixtures.SECOND_UUID, type = "USB", path = "USB/reference.txt"),
            fixtures.product(uuid = "33333333-3333-4333-8333-333333333333", type = "FUTURE", path = null),
        )
        val catalogue = fixtures.catalogue(products)
        val files = mapOf(fixtures.PATH to fixtures.responseBytes, "USB/reference.txt" to "100 70\n1000 80\n".encodeToByteArray())
        val snapshot = decodeCatalogSnapshot(encodeCatalogSnapshot(catalogue, fixtures.responseLibrary(), files))
        assertEquals(listOf("BT", "USB", "FUTURE"), snapshot.products.map { it.type })
        assertEquals(products, snapshot.products.map { it.raw })
        val usb = assertIs<CatalogResponse.Ready>(snapshot.responsesByPath.getValue("USB/reference.txt"))
        assertContentEquals(doubleArrayOf(70.0, 80.0), usb.response.splDb)
        assertFailsWith<IllegalArgumentException> {
            encodeCatalogSnapshot(catalogue, fixtures.responseLibrary(), mapOf(fixtures.PATH to fixtures.responseBytes))
        }
    }

    @Test fun responseLibraryMergePreservesSourceDataAndRejectsIncompleteSnapshots() {
        val libraryUuid = "33333333-3333-4333-8333-333333333333"
        val unavailableUuid = "44444444-4444-4444-8444-444444444444"
        val physical = listOf(
            fixtures.product(),
            fixtures.product(uuid = fixtures.SECOND_UUID, type = "Response", path = "USB/reference.txt"),
        )
        val entries = listOf(
            fixtures.responseEntry(uuid = libraryUuid, tags = listOf("standard", "", "test", "standard")),
            fixtures.responseEntry(uuid = unavailableUuid, file = "peq-config-file/target.txt", tags = emptyList()),
        )
        val catalogue = fixtures.catalogue(physical)
        val library = fixtures.responseLibrary(entries)
        val unsupported = "100 40 0\r\n1000 60 0\r\n".encodeToByteArray()
        val files = linkedMapOf(
            fixtures.PATH to fixtures.responseBytes,
            "USB/reference.txt" to "100 70\n1000 80\n".encodeToByteArray(),
            "peq-config-file/target.txt" to unsupported,
        )
        val document = encodeCatalogSnapshot(catalogue, library, files, fixtures.RETRIEVED_AT)
        val snapshot = decodeCatalogSnapshot(document)
        assertEquals(listOf(fixtures.UUID, fixtures.SECOND_UUID, libraryUuid, unavailableUuid), snapshot.products.map { it.uuid })
        assertEquals(listOf("BT", "Response", "Response", "Response"), snapshot.products.map { it.type })
        assertEquals(physical, snapshot.products.take(2).map { it.raw })
        assertEquals(snapshot.products.map { it.uuid }.toSet(), snapshot.productsByUuid.keys)
        assertEquals(files.keys, snapshot.responsesByPath.keys)
        assertEquals(setOf(libraryUuid, unavailableUuid), snapshot.responseLibraryUuids)
        assertEquals(CATALOG_RESPONSE_LIBRARY_URL, snapshot.responseLibraryUrl)
        assertEquals(CATALOGUE_URL, Catalog.sourceUrl(snapshot, snapshot.productsByUuid.getValue(fixtures.SECOND_UUID)))
        assertEquals(CATALOG_RESPONSE_LIBRARY_URL, Catalog.sourceUrl(snapshot, snapshot.productsByUuid.getValue(libraryUuid)))
        assertEquals(CATALOG_RESPONSE_LIBRARY_URL, Catalog.sourceUrl(snapshot, snapshot.productsByUuid.getValue(unavailableUuid)))
        val projected = snapshot.productsByUuid.getValue(libraryUuid)
        assertNull(projected.model)
        assertNull(projected.languageType)
        assertEquals(fixtures.PATH, projected.freqResponse)
        assertEquals(JsonPrimitive("Response"), projected.raw["type"])
        assertEquals(JsonPrimitive(fixtures.PATH), projected.raw["freqResponse"])
        assertEquals(entries[0], JsonObject(projected.raw - "type" - "freqResponse"))
        assertIs<CatalogResponse.Unavailable>(snapshot.responsesByPath.getValue("peq-config-file/target.txt"))
        val root = fixtures.root(snapshot.documentBytes)
        assertEquals(fixtures.root(catalogue), root["catalogue"])
        assertEquals(fixtures.root(library), root["responseLibrary"])
        assertNull(entries[0]["type"])
        assertNull(entries[0]["freqResponse"])
        for (element in root["responseFiles"] as JsonArray) {
            val asset = element as JsonObject
            val path = (asset.getValue("path") as JsonPrimitive).content
            assertContentEquals(files.getValue(path), fixtures.assetBytes(asset))
            assertEquals(catalogSha256(files.getValue(path)), snapshot.responseHashesByPath.getValue(path))
        }
        val parsedEntries = parseCatalogResponseLibrary(library)
        assertEquals(listOf("standard", "", "test", "standard"), parsedEntries[0].tags)
        assertTrue(parsedEntries[1].tags.isEmpty())
        assertEquals(entries, parsedEntries.map { it.raw })
        for ((missingPath, _) in files) {
            val incomplete = files - missingPath
            assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(fixtures.document(catalogue, incomplete, library)) }
            assertFailsWith<IllegalArgumentException> { encodeCatalogSnapshot(catalogue, library, incomplete) }
        }
        val duplicateFiles = root.getValue("responseFiles") as JsonArray
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.changed(document, "responseFiles", JsonArray(duplicateFiles + duplicateFiles.last())))
        }
        val broken = JsonObject((duplicateFiles.last() as JsonObject) + ("sha256" to JsonPrimitive("0".repeat(64))))
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.changed(document, "responseFiles", JsonArray(duplicateFiles.dropLast(1) + broken)))
        }
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.document(catalogue, files + ("extra.txt" to fixtures.responseBytes), library))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeCatalogSnapshot(catalogue, library, files + ("extra.txt" to fixtures.responseBytes))
        }
        val conflictingUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val conflictingCatalogue = fixtures.catalogue(listOf(fixtures.product(uuid = conflictingUuid)))
        for (uuid in listOf(conflictingUuid, conflictingUuid.uppercase())) {
            val conflictingLibrary = fixtures.responseLibrary(listOf(fixtures.responseEntry(uuid = uuid)))
            assertFailsWith<IllegalArgumentException> {
                decodeCatalogSnapshot(fixtures.document(conflictingCatalogue, responseLibraryBytes = conflictingLibrary))
            }
            assertFailsWith<IllegalArgumentException> {
                encodeCatalogSnapshot(conflictingCatalogue, conflictingLibrary, mapOf(fixtures.PATH to fixtures.responseBytes))
            }
            assertFailsWith<IllegalArgumentException> {
                mergeCatalogProducts(parseCatalogProducts(conflictingCatalogue), parseCatalogResponseLibrary(conflictingLibrary))
            }
        }
    }

    @Test fun responseLibraryRequiresAnExplicitSuccessEnvelopeAndValidRecordsWithoutFilteringTags() {
        val entry = fixtures.responseEntry()
        val envelope = fixtures.root(fixtures.responseLibrary(listOf(entry)))
        val invalidRoots = mutableListOf(
            JsonObject(envelope - "code"),
            JsonObject(envelope - "data"),
            JsonObject(envelope + ("data" to JsonObject(emptyMap()))),
            JsonObject(envelope + ("data" to JsonNull)),
        )
        for (code in listOf(JsonPrimitive(1), JsonPrimitive("0"), JsonPrimitive(0.0), JsonNull)) {
            invalidRoots.add(JsonObject(envelope + ("code" to code)))
        }
        for (record in listOf(JsonNull, JsonPrimitive("record"), JsonArray(emptyList()))) {
            invalidRoots.add(JsonObject(envelope + ("data" to JsonArray(listOf(record)))))
        }
        for (field in listOf("uuid", "name", "file", "tags")) {
            invalidRoots.add(fixtures.root(fixtures.responseLibrary(listOf(JsonObject(entry - field)))))
        }
        for ((field, value) in listOf(
            "uuid" to JsonPrimitive("not-a-uuid"),
            "uuid" to JsonPrimitive(1),
            "uuid" to JsonNull,
            "name" to JsonPrimitive(" \t"),
            "name" to JsonPrimitive(1),
            "name" to JsonNull,
            "file" to JsonPrimitive(" \t"),
            "file" to JsonPrimitive(1),
            "file" to JsonNull,
            "tags" to JsonNull,
            "tags" to JsonPrimitive("standard"),
            "tags" to JsonObject(emptyMap()),
            "tags" to JsonArray(listOf(JsonNull)),
            "tags" to JsonArray(listOf(JsonPrimitive(1))),
            "tags" to JsonArray(listOf(JsonPrimitive(false))),
            "tags" to JsonArray(listOf(JsonObject(emptyMap()))),
        )) {
            invalidRoots.add(fixtures.root(fixtures.responseLibrary(listOf(JsonObject(entry + (field to value))))))
        }
        for (path in listOf("/absolute", "../curve", "dir/../curve", "https://host/curve", "dir\\curve", "curve?q", "curve#f", "curve\u0000")) {
            invalidRoots.add(fixtures.root(fixtures.responseLibrary(listOf(fixtures.responseEntry(file = path)))))
        }
        val uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        for (secondUuid in listOf(uuid, uuid.uppercase())) {
            invalidRoots.add(fixtures.root(fixtures.responseLibrary(listOf(
                fixtures.responseEntry(uuid = uuid), fixtures.responseEntry(uuid = secondUuid),
            ))))
        }
        for (root in invalidRoots) {
            val bytes = root.toString().encodeToByteArray()
            assertFailsWith<IllegalArgumentException> { parseCatalogResponseLibrary(bytes) }
            assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(fixtures.document(responseLibraryBytes = bytes)) }
            assertFailsWith<IllegalArgumentException> {
                encodeCatalogSnapshot(fixtures.catalogue(), bytes, mapOf(fixtures.PATH to fixtures.responseBytes))
            }
        }
        for (bytes in listOf("[]".encodeToByteArray(), "{".encodeToByteArray(), byteArrayOf(0xff.toByte()))) {
            assertFailsWith<IllegalArgumentException> { parseCatalogResponseLibrary(bytes) }
            assertFailsWith<IllegalArgumentException> {
                encodeCatalogSnapshot(fixtures.catalogue(), bytes, mapOf(fixtures.PATH to fixtures.responseBytes))
            }
        }
        assertTrue(parseCatalogResponseLibrary(fixtures.responseLibrary()).isEmpty())
        val empty = fixtures.snapshot()
        assertEquals(listOf(fixtures.UUID), empty.products.map { it.uuid })
        assertTrue(empty.responseLibraryUuids.isEmpty())
        for (path in listOf("freq-response-file/中文 %.txt", "peq-config-file/%2e%2e/raw%20name.txt", " raw/curve.txt ")) {
            val bytes = fixtures.responseLibrary(listOf(fixtures.responseEntry(file = path, name = " Target ", tags = emptyList())))
            val snapshot = fixtures.snapshot(
                responseFiles = mapOf(fixtures.PATH to fixtures.responseBytes, path to fixtures.responseBytes),
                responseLibraryBytes = bytes,
            )
            val product = snapshot.productsByUuid.getValue(fixtures.SECOND_UUID)
            assertEquals(path, product.freqResponse)
            assertEquals(" Target ", product.name)
        }
    }

    @Test fun legacyFormatAndVersionCannotBeImportedAsCurrent() {
        val document = fixtures.document()
        assertEquals(JsonPrimitive("sunrise-moondrop-catalog"), fixtures.root(document)["format"])
        assertEquals(JsonPrimitive(3), fixtures.root(document)["schemaVersion"])
        for (version in listOf(1, 2, 3)) {
            val legacy = fixtures.changed(
                fixtures.changed(document, "format", JsonPrimitive("sunrise-moondrop-bt")),
                "schemaVersion", JsonPrimitive(version),
            )
            assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(legacy) }
        }
        for (version in listOf(1, 2)) {
            assertFailsWith<IllegalArgumentException> {
                decodeCatalogSnapshot(fixtures.changed(document, "schemaVersion", JsonPrimitive(version)))
            }
        }
    }

    @Test fun duplicateUuidRejectsTheWholeDirectoryIncludingCaseVariants() {
        val uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        for (secondUuid in listOf(uuid, uuid.uppercase())) {
            val catalogue = fixtures.catalogue(listOf(fixtures.product(uuid = uuid), fixtures.product(uuid = secondUuid)))
            assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(fixtures.document(catalogue)) }
        }
    }

    @Test fun unsafePathsRejectAndValidRawPathsAreNotTrimmedOrDecoded() {
        for (path in listOf(
            "/absolute.txt", "//host/file", "https://host/file", "file:curve", "C:/curve", "dir\\curve",
            "curve?query", "curve#fragment", "../curve", "dir/../curve", "./curve", "dir/./curve",
            "curve\u0000", "curve\u001F", "curve\u007F", "curve\u0085", " ",
        )) {
            assertFailsWith<IllegalArgumentException> { validateCatalogResponsePath(path) }
            if (path.isNotBlank()) assertFailsWith<IllegalArgumentException> {
                parseCatalogProducts(fixtures.catalogue(listOf(fixtures.product(path = path))))
            }
        }
        for (path in listOf("中文/频 响%.txt", "BT/%2e%2e/raw%20name.txt", " BT/response.txt ", "BT/\uD83C\uDFB5.txt")) {
            val snapshot = fixtures.snapshot(fixtures.catalogue(listOf(fixtures.product(path = path))), mapOf(path to fixtures.responseBytes))
            assertEquals(path, snapshot.products.single().freqResponse)
            assertEquals(setOf(path), snapshot.responsesByPath.keys)
        }
    }

    @Test fun unpairedUnicodeSurrogatesCannotBecomeDownloadPaths() {
        for (path in listOf("BT/\uD800.txt", "BT/\uDC00.txt", "BT/\uD800\uD800.txt")) {
            assertFailsWith<IllegalArgumentException> { validateCatalogResponsePath(path) }
        }
    }

    @Test fun assetPathsMustBeAnExactNonduplicatedSet() {
        assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(fixtures.document(responseFiles = emptyMap())) }
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.document(responseFiles = mapOf(fixtures.PATH to fixtures.responseBytes, "extra.txt" to fixtures.responseBytes)))
        }
        val document = fixtures.document()
        val file = (fixtures.root(document)["responseFiles"] as JsonArray).single()
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.changed(document, "responseFiles", JsonArray(listOf(file, file))))
        }
        val unsafeFile = JsonObject((file as JsonObject) + ("path" to JsonPrimitive("../curve")))
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.changed(document, "responseFiles", JsonArray(listOf(unsafeFile))))
        }
    }

    @Test fun versionFormatUtcTimestampAndRootStructureAreValidated() {
        val document = fixtures.document()
        for ((field, value) in listOf(
            "format" to JsonPrimitive("different-format"),
            "schemaVersion" to JsonPrimitive(1),
            "schemaVersion" to JsonPrimitive(2),
            "schemaVersion" to JsonPrimitive(CATALOG_SCHEMA_VERSION + 1),
            "schemaVersion" to JsonPrimitive(CATALOG_SCHEMA_VERSION.toString()),
            "schemaVersion" to JsonPrimitive(CATALOG_SCHEMA_VERSION.toDouble()),
            "retrievedAt" to JsonPrimitive("not-a-timeZ"),
            "retrievedAt" to JsonPrimitive("2026-10-04T00:00:00"),
            "retrievedAt" to JsonPrimitive("2026-10-04T00:00:00+01:00"),
            "catalogueUrl" to JsonPrimitive(" "),
            "responseLibraryUrl" to JsonPrimitive(" "),
            "responseLibraryUrl" to JsonNull,
            "cdnBaseUrl" to JsonNull,
            "catalogue" to JsonArray(emptyList()),
            "responseLibrary" to JsonArray(emptyList()),
            "responseLibrary" to JsonNull,
            "responseFiles" to JsonObject(emptyMap()),
            "responseFiles" to JsonArray(listOf(JsonNull)),
        )) assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(fixtures.changed(document, field, value)) }
        for (field in listOf("format", "schemaVersion", "retrievedAt", "catalogueUrl", "responseLibraryUrl", "cdnBaseUrl", "catalogue", "responseLibrary", "responseFiles")) {
            val root = JsonObject(fixtures.root(document) - field)
            assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(root.toString().encodeToByteArray()) }
        }
        assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot("[]".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(byteArrayOf(0xff.toByte())) }
        val utcOffset = fixtures.changed(document, "retrievedAt", JsonPrimitive("2026-10-04T00:00:00+00:00"))
        assertEquals("2026-10-04T00:00:00+00:00", decodeCatalogSnapshot(utcOffset).retrievedAt)
    }

    @Test fun responseTextEncodingStructureAndHashesAreValidated() {
        val document = fixtures.document()
        val original = (fixtures.root(document)["responseFiles"] as JsonArray).single() as JsonObject
        for ((field, value) in listOf(
            "sha256" to JsonPrimitive("0".repeat(64)),
            "sha256" to JsonPrimitive("A".repeat(64)),
            "sha256" to JsonPrimitive("abc"),
            "encoding" to JsonPrimitive("unsupported"),
            "encoding" to JsonNull,
            "lines" to JsonNull,
            "lines" to JsonPrimitive("100 40"),
            "lines" to JsonArray(emptyList()),
            "lines" to JsonArray(listOf(JsonNull)),
            "lines" to JsonArray(listOf(JsonPrimitive(100))),
            "lines" to JsonArray(listOf(JsonPrimitive("100 40\n1000 60"))),
            "lines" to JsonArray(listOf(JsonPrimitive("changed bytes"))),
        )) {
            val file = JsonObject(original + (field to value))
            assertFailsWith<IllegalArgumentException> {
                decodeCatalogSnapshot(fixtures.changed(document, "responseFiles", JsonArray(listOf(file))))
            }
        }
        for (field in listOf("sha256", "encoding", "lines")) {
            assertFailsWith<IllegalArgumentException> {
                decodeCatalogSnapshot(fixtures.changed(document, "responseFiles", JsonArray(listOf(JsonObject(original - field)))))
            }
        }
    }

    @Test fun responseTextRoundTripsUnicodeLegacyBytesAndExactLineEndings() {
        val responses = listOf(
            "\uFEFF* 中文备注 🎵\r\n100 40\r\n1000 60\r\n".encodeToByteArray(),
            "* no trailing newline\n100 40\r\n1000 60".encodeToByteArray(),
            "* legacy ".encodeToByteArray() + byteArrayOf(0xff.toByte(), 0x80.toByte()) +
                "\r\n100 40\r\n1000 60\r\n".encodeToByteArray(),
            ByteArray(256) { it.toByte() },
        )
        for (response in responses) {
            val encoded = encodeCatalogSnapshot(fixtures.catalogue(), fixtures.responseLibrary(), mapOf(fixtures.PATH to response))
            val snapshot = decodeCatalogSnapshot(encoded)
            val file = (fixtures.root(snapshot.documentBytes)["responseFiles"] as JsonArray).single() as JsonObject
            assertContentEquals(response, fixtures.assetBytes(file))
            assertEquals(catalogSha256(response), snapshot.responseHashesByPath.getValue(fixtures.PATH))
        }
        for (text in listOf("\u0100", "\uD800", "\uDC00")) {
            val asset = JsonObject(fixtures.asset(fixtures.responseBytes) + mapOf(
                "encoding" to JsonPrimitive("iso-8859-1"),
                "lines" to JsonArray(listOf(JsonPrimitive(text))),
            ))
            assertFailsWith<IllegalArgumentException> {
                decodeCatalogSnapshot(fixtures.changed(fixtures.document(), "responseFiles", JsonArray(listOf(
                    JsonObject(asset + ("path" to JsonPrimitive(fixtures.PATH))),
                ))))
            }
        }
    }

    @Test fun allByteLimitsRejectBeforeAcceptingOrPublishingADocument() {
        assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(ByteArray(MAX_CATALOG_SNAPSHOT_BYTES + 1)) }
        val oversized = ByteArray(MAX_CATALOGUE_BYTES + 1) { ' '.code.toByte() }
        assertFailsWith<IllegalArgumentException> { parseCatalogProducts(oversized) }
        assertFailsWith<IllegalArgumentException> { parseCatalogResponseLibrary(oversized) }
        assertFailsWith<IllegalArgumentException> {
            encodeCatalogSnapshot(oversized, fixtures.responseLibrary(), mapOf(fixtures.PATH to fixtures.responseBytes))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeCatalogSnapshot(fixtures.catalogue(), oversized, mapOf(fixtures.PATH to fixtures.responseBytes))
        }
        val oversizedCatalogue = JsonObject(fixtures.root(fixtures.catalogue()) + ("largeMetadata" to JsonPrimitive("x".repeat(MAX_CATALOGUE_BYTES))))
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.changed(fixtures.document(), "catalogue", oversizedCatalogue))
        }
        val oversizedLibrary = JsonObject(fixtures.root(fixtures.responseLibrary()) + ("largeMetadata" to JsonPrimitive("x".repeat(MAX_CATALOGUE_BYTES))))
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.changed(fixtures.document(), "responseLibrary", oversizedLibrary))
        }
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.document(responseFiles = mapOf(fixtures.PATH to oversized)))
        }
        val products = (0..24).map { index ->
            fixtures.product(uuid = "${index.toString().padStart(8, '0')}-1111-4111-8111-111111111111", path = "$index.txt")
        }
        val fullSizeAsset = ByteArray(MAX_RESPONSE_FILE_BYTES)
        val assets = products.associate { (it["freqResponse"] as JsonPrimitive).content to fullSizeAsset }
        assertFailsWith<IllegalArgumentException> { encodeCatalogSnapshot(fixtures.catalogue(products), fixtures.responseLibrary(), assets) }
        // Neither metadata body alone reaches the remaining 2 MiB; together they exceed it.
        val padding = JsonPrimitive("x".repeat(MAX_CATALOGUE_BYTES / 2 + 1024))
        val nearLimitCatalogue = JsonObject(fixtures.root(fixtures.catalogue(products.take(23))) + ("padding" to padding))
            .toString().encodeToByteArray()
        val nearLimitLibrary = JsonObject(fixtures.root(fixtures.responseLibrary()) + ("padding" to padding))
            .toString().encodeToByteArray()
        val readableFullSizeAsset = ByteArray(MAX_RESPONSE_FILE_BYTES) { 'x'.code.toByte() }
        val nearLimitAssets = products.take(23).associate {
            (it["freqResponse"] as JsonPrimitive).content to readableFullSizeAsset
        }
        assertTrue(nearLimitCatalogue.size <= MAX_CATALOGUE_BYTES)
        assertTrue(nearLimitLibrary.size <= MAX_CATALOGUE_BYTES)
        assertFailsWith<IllegalArgumentException> { encodeCatalogSnapshot(nearLimitCatalogue, nearLimitLibrary, nearLimitAssets) }
        val aggregateDocument = fixtures.document(nearLimitCatalogue, nearLimitAssets, nearLimitLibrary)
        assertTrue(aggregateDocument.size <= MAX_CATALOG_SNAPSHOT_BYTES)
        assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(aggregateDocument) }
    }

    @Test fun encoderPreservesAssetsAndRequiresTheirCompleteSet() {
        val catalogue = fixtures.catalogue()
        val library = fixtures.responseLibrary()
        val importedLibraryUrl = "https://example.invalid/imported-library"
        val response = fixtures.responseBytes
        val document = encodeCatalogSnapshot(
            catalogue, library, mapOf(fixtures.PATH to response), fixtures.RETRIEVED_AT, CATALOG_OVERSEAS_CDN_URL,
            responseLibraryUrl = importedLibraryUrl,
        )
        val snapshot = decodeCatalogSnapshot(document)
        assertEquals(CATALOG_OVERSEAS_CDN_URL, snapshot.cdnBaseUrl)
        assertEquals(fixtures.RETRIEVED_AT, snapshot.retrievedAt)
        assertEquals(importedLibraryUrl, snapshot.responseLibraryUrl)
        val root = fixtures.root(document)
        assertEquals(fixtures.root(catalogue), root["catalogue"])
        assertEquals(fixtures.root(library), root["responseLibrary"])
        assertContentEquals(response, fixtures.assetBytes((root["responseFiles"] as JsonArray).single() as JsonObject))
        assertFailsWith<IllegalArgumentException> { encodeCatalogSnapshot(catalogue, fixtures.responseLibrary(), emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            encodeCatalogSnapshot(catalogue, fixtures.responseLibrary(), mapOf(fixtures.PATH to response, "extra.txt" to response))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeCatalogSnapshot(catalogue, library, mapOf(fixtures.PATH to response), responseLibraryUrl = " \t")
        }
    }

    @Test fun sha256MatchesStandardVectorsAndUsesLowercaseHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", catalogSha256(byteArrayOf()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", catalogSha256("abc".encodeToByteArray()))
    }

    @Test fun badNumericRowsRemainUnavailableInsteadOfBeingDroppedOrReplaced() {
        for (badRow in listOf("1000 NaN", "100 60", "50 60", "1000 60 0")) {
            val original = "* preserved comment\n100 40\n$badRow\n2000 50".encodeToByteArray()
            val snapshot = fixtures.snapshot(responseFiles = mapOf(fixtures.PATH to original))
            val unavailable = assertIs<CatalogResponse.Unavailable>(snapshot.responsesByPath.getValue(fixtures.PATH))
            assertTrue(unavailable.reason.contains("line 3"))
            assertEquals(1, snapshot.products.size)
            val asset = (fixtures.root(snapshot.documentBytes)["responseFiles"] as JsonArray).single() as JsonObject
            assertContentEquals(original, fixtures.assetBytes(asset))
        }
    }

}
