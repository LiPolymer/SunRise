package ink.lipoly.app.sunrise.catalog

import ink.lipoly.app.sunrise.resources.Res
import kotlinx.coroutines.test.runTest
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
            "type" to JsonPrimitive("USB"),
            "type" to JsonPrimitive("bt"),
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
            "schemaVersion" to JsonPrimitive(CATALOG_SCHEMA_VERSION + 1),
            "schemaVersion" to JsonPrimitive(CATALOG_SCHEMA_VERSION.toString()),
            "schemaVersion" to JsonPrimitive(CATALOG_SCHEMA_VERSION.toDouble()),
            "retrievedAt" to JsonPrimitive("not-a-timeZ"),
            "retrievedAt" to JsonPrimitive("2026-10-04T00:00:00"),
            "retrievedAt" to JsonPrimitive("2026-10-04T00:00:00+01:00"),
            "catalogueUrl" to JsonPrimitive(" "),
            "cdnBaseUrl" to JsonNull,
            "catalogue" to JsonArray(emptyList()),
            "responseFiles" to JsonObject(emptyMap()),
            "responseFiles" to JsonArray(listOf(JsonNull)),
        )) assertFailsWith<IllegalArgumentException> { decodeCatalogSnapshot(fixtures.changed(document, field, value)) }
        for (field in listOf("format", "schemaVersion", "retrievedAt", "catalogueUrl", "cdnBaseUrl", "catalogue", "responseFiles")) {
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
            val encoded = encodeCatalogSnapshot(fixtures.catalogue(), mapOf(fixtures.PATH to response))
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
        val oversizedCatalogue = JsonObject(fixtures.root(fixtures.catalogue()) + ("largeMetadata" to JsonPrimitive("x".repeat(MAX_CATALOGUE_BYTES))))
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.changed(fixtures.document(), "catalogue", oversizedCatalogue))
        }
        assertFailsWith<IllegalArgumentException> {
            decodeCatalogSnapshot(fixtures.document(responseFiles = mapOf(fixtures.PATH to oversized)))
        }
        val products = (0..24).map { index ->
            fixtures.product(uuid = "${index.toString().padStart(8, '0')}-1111-4111-8111-111111111111", path = "$index.txt")
        }
        val fullSizeAsset = ByteArray(MAX_RESPONSE_FILE_BYTES)
        val assets = products.associate { (it["freqResponse"] as JsonPrimitive).content to fullSizeAsset }
        assertFailsWith<IllegalArgumentException> { encodeCatalogSnapshot(fixtures.catalogue(products), assets) }
    }

    @Test fun encoderPreservesAssetsAndRequiresTheirCompleteSet() {
        val catalogue = fixtures.catalogue()
        val response = fixtures.responseBytes
        val document = encodeCatalogSnapshot(catalogue, mapOf(fixtures.PATH to response), fixtures.RETRIEVED_AT, CATALOG_OVERSEAS_CDN_URL)
        val snapshot = decodeCatalogSnapshot(document)
        assertEquals(CATALOG_OVERSEAS_CDN_URL, snapshot.cdnBaseUrl)
        assertEquals(fixtures.RETRIEVED_AT, snapshot.retrievedAt)
        val root = fixtures.root(document)
        assertEquals(fixtures.root(catalogue), root["catalogue"])
        assertContentEquals(response, fixtures.assetBytes((root["responseFiles"] as JsonArray).single() as JsonObject))
        assertFailsWith<IllegalArgumentException> { encodeCatalogSnapshot(catalogue, emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            encodeCatalogSnapshot(catalogue, mapOf(fixtures.PATH to response, "extra.txt" to response))
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

    @Test fun bundledSnapshotActuallyDecodesWithEveryReferencedAssetPresent() = runTest {
        val bytes = Res.readBytes("files/moondrop-bt.snapshot.json")
        val snapshot = decodeCatalogSnapshot(bytes)
        assertTrue(snapshot.products.isNotEmpty())
        assertContentEquals(bytes, snapshot.documentBytes)
        assertEquals(snapshot.products.mapNotNull { it.freqResponse }.toSet(), snapshot.responsesByPath.keys)
        val root = fixtures.root(bytes)
        for (asset in root["responseFiles"] as JsonArray) {
            val file = asset as JsonObject
            val path = (file["path"] as JsonPrimitive).content
            assertEquals(catalogSha256(fixtures.assetBytes(file)), snapshot.responseHashesByPath.getValue(path))
            assertNotNull(snapshot.responsesByPath[path])
        }
        val readyCount = snapshot.responsesByPath.values.count { it is CatalogResponse.Ready }
        println("Bundled MOONDROP snapshot: ${snapshot.products.size} records, ${snapshot.productsByNormalizedName.size} names, ${snapshot.responsesByPath.size} assets, $readyCount parseable curves")
    }
}
