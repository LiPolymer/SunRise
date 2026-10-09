package ink.lipoly.app.sunrise.catalog

import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class CatalogMatchingTest {
    private fun product(uuid: String, name: String = "SPACE TRAVEL 2 ULTRA", language: String? = "en-US", path: String? = null, type: String = "BT") =
        CatalogProduct(uuid, name, type, "Ultra", language, path, JsonObject(emptyMap()))

    private val ready = CatalogResponse.Ready(FrequencyResponse(doubleArrayOf(100.0, 1000.0), doubleArrayOf(40.0, 60.0)))
    private val unavailable = CatalogResponse.Unavailable("Unsupported data at line 2")
    private fun snapshot(products: List<CatalogProduct>, responses: Map<String, CatalogResponse> = emptyMap()) =
        CatalogSnapshot(byteArrayOf(), "2026-10-04T00:00:00Z", CATALOGUE_URL, CATALOG_CHINA_CDN_URL,
            products, responses, emptyMap(), CATALOG_RESPONSE_LIBRARY_URL, emptySet())

    @Test fun exactNameOnlyWithUnicodeWhitespaceAndCaseNormalization() {
        val catalogue = snapshot(listOf(product("a")))
        for (name in listOf("  SPACE   TRAVEL 2 ULTRA ", "space\u00a0travel\u20032\tultra", "SPACE TRAVEL 2 ULTRA"))
            assertTrue(matchesCatalogDevice(catalogue, name))
        for (name in listOf(null, "", " \t\n\u2003", "Ultra", "Space Travel 2 Ultra XYZ",
            "MOONDROP SPACE TRAVEL 2 ULTRA", "SPACE-TRAVEL 2 ULTRA"))
            assertFalse(matchesCatalogDevice(catalogue, name))
        assertFalse(matchesCatalogDevice(null, "SPACE TRAVEL 2 ULTRA"))
    }

    @Test fun bluetoothMatchingIgnoresSameNameOtherTypesButManualReferencesAndBrowsingRetainThem() {
        val fixtures = CatalogTestFixtures
        val usbUuid = fixtures.UUID
        val bluetoothUuid = fixtures.SECOND_UUID
        val responseUuid = "33333333-3333-4333-8333-333333333333"
        val lowerUuid = "44444444-4444-4444-8444-444444444444"
        val futureUuid = "55555555-5555-4555-8555-555555555555"
        val physical = listOf(
            fixtures.product(uuid = usbUuid, type = "USB"),
            fixtures.product(uuid = lowerUuid, name = "Lower case type", type = "bt", path = null),
            fixtures.product(uuid = futureUuid, name = "Future model", type = "FUTURE", path = null),
        )
        val library = fixtures.responseLibrary(listOf(fixtures.responseEntry(uuid = responseUuid)))
        val nonBluetooth = fixtures.snapshot(fixtures.catalogue(physical), responseLibraryBytes = library)
        for (name in listOf("SPACE TRAVEL 2 ULTRA", "Lower case type", "Future model")) {
            assertFalse(matchesCatalogDevice(nonBluetooth, name))
            assertNull(resolveCatalogReference(nonBluetooth, name, null).product)
        }
        val files = mapOf(
            fixtures.PATH to fixtures.responseBytes,
            "broken.txt" to "100 40 0\n1000 60 0\n".encodeToByteArray(),
        )
        val all = fixtures.snapshot(
            fixtures.catalogue(physical + fixtures.product(uuid = bluetoothUuid, language = "zh-CN", path = "broken.txt")),
            files, library,
        )
        assertTrue(matchesCatalogDevice(all, " space   travel 2 ultra "))
        val automatic = resolveCatalogReference(all, "SPACE TRAVEL 2 ULTRA", null)
        assertEquals(bluetoothUuid, automatic.product?.uuid)
        assertIs<CatalogResponse.Unavailable>(automatic.response)
        for (uuid in listOf(usbUuid, responseUuid)) {
            val manual = resolveCatalogReference(all, "SPACE TRAVEL 2 ULTRA", uuid)
            assertEquals(uuid, manual.product?.uuid)
            val response = assertIs<CatalogResponse.Ready>(manual.response)
            assertContentEquals(doubleArrayOf(40.0, 60.0), response.response.splDb)
            assertFalse(manual.invalidManualBinding)
        }
        assertEquals("Response", all.productsByUuid.getValue(responseUuid).type)
        assertEquals(setOf(usbUuid, bluetoothUuid, responseUuid, lowerUuid, futureUuid),
            orderedCatalogProducts(all, "SPACE TRAVEL 2 ULTRA").map { it.uuid }.toSet())
    }

    @Test fun usableCurveOutranksPreferredLanguageAndNeverBorrowsAnotherName() {
        val englishMissing = product("a", language = "en-US")
        val chineseBroken = product("b", language = "zh-CN", path = "broken")
        val otherReady = product("c", language = "ja-JP", path = "ready")
        val unrelated = product("d", name = "Ultra Plus", path = "ready")
        val catalogue = snapshot(listOf(englishMissing, chineseBroken, otherReady, unrelated),
            mapOf("ready" to ready, "broken" to unavailable))
        val selection = resolveCatalogReference(catalogue, "space travel 2 ultra", null)
        assertSame(otherReady, selection.product)
        assertSame(ready, selection.response)
        assertFalse(selection.invalidManualBinding)
        assertNull(resolveCatalogReference(catalogue, "Ultra", null).product)
    }

    @Test fun chineseThenEmptyThenOtherThenUuidBreakTies() {
        val en = product("f", language = "en-US", path = "ready")
        val zh = product("e", language = "zh-CN", path = "ready")
        val blank = product("d", language = null, path = "ready")
        val otherB = product("b", language = "de-DE", path = "ready")
        val otherA = product("a", language = "ja-JP", path = "ready")
        val all = listOf(otherB, otherA, blank, zh, en)
        val responses = mapOf("ready" to ready)
        assertSame(zh, resolveCatalogReference(snapshot(all, responses), en.name, null).product)
        assertSame(blank, resolveCatalogReference(snapshot(all.take(3), responses), en.name, null).product)
        assertSame(otherA, resolveCatalogReference(snapshot(all.take(2), responses), en.name, null).product)
    }

    @Test fun explicitUuidOverridesNameLanguageAndCurveAvailabilityWithoutChangingFilter() {
        val auto = product("a", path = "ready")
        val manual = product("b", name = "Garden", language = "zh-CN", path = "broken")
        val noCurve = product("c", name = "Garden")
        val catalogue = snapshot(listOf(auto, manual, noCurve), mapOf("ready" to ready, "broken" to unavailable))
        val selected = resolveCatalogReference(catalogue, "Renamed headphones", manual.uuid)
        assertSame(manual, selected.product)
        assertSame(unavailable, selected.response)
        assertFalse(selected.invalidManualBinding)
        assertFalse(matchesCatalogDevice(catalogue, "Renamed headphones"))
        val missingCurve = resolveCatalogReference(catalogue, auto.name, noCurve.uuid)
        assertSame(noCurve, missingCurve.product)
        assertNull(missingCurve.response)
    }

    @Test fun vanishedUuidFallsBackAutomaticallyWithoutMigratingBinding() {
        val prior = product("old", language = "zh-CN", path = "broken")
        val replacement = product("new", language = "en-US", path = "ready")
        val before = snapshot(listOf(prior, replacement), mapOf("ready" to ready, "broken" to unavailable))
        assertSame(prior, resolveCatalogReference(before, prior.name, prior.uuid).product)
        val after = snapshot(listOf(replacement), mapOf("ready" to ready))
        val fallback = resolveCatalogReference(after, prior.name, prior.uuid)
        assertSame(replacement, fallback.product)
        assertSame(ready, fallback.response)
        assertTrue(fallback.invalidManualBinding)
        val noMatch = resolveCatalogReference(after, "Renamed", prior.uuid)
        assertNull(noMatch.product)
        assertTrue(noMatch.invalidManualBinding)
        assertFalse(resolveCatalogReference(null, prior.name, prior.uuid).invalidManualBinding)
    }

    @Test fun unavailableCurveAndNoCurveRemainRealMetadataSelections() {
        val broken = product("b", language = "zh-CN", path = "broken")
        val missing = product("a", language = "en-US")
        val catalogue = snapshot(listOf(broken, missing), mapOf("broken" to unavailable))
        val selection = resolveCatalogReference(catalogue, missing.name, null)
        assertSame(broken, selection.product)
        assertSame(unavailable, selection.response)
        val manual = resolveCatalogReference(catalogue, missing.name, missing.uuid)
        assertSame(missing, manual.product)
        assertNull(manual.response)
    }

    @Test fun selectorRetainsEveryRecordAndOrdersCurrentNameThenNameLanguageUuid() {
        val products = listOf(
            product("z", name = "Zeta", path = "ready"),
            product("ultra-other-b", language = "ja-JP"),
            product("alpha-zh", name = "Alpha", language = "zh-CN", path = "ready"),
            product("ultra-null", language = null),
            product("ultra-en-b", language = "en-US"),
            product("ultra-other-a", language = "de-DE", path = "ready"),
            product("alpha-en", name = " alpha ", language = "en-US"),
            product("ultra-zh", language = "zh-CN", path = "broken"),
            product("ultra-en-a", language = "en-US", path = "ready"),
        )
        val catalogue = snapshot(products, mapOf("ready" to ready, "broken" to unavailable))
        assertEquals(products.size, catalogue.products.size)
        assertEquals(6, catalogue.productsByNormalizedName.getValue("space travel 2 ultra").size)
        val ordered = orderedCatalogProducts(catalogue, " space   travel 2 ultra ")
        assertEquals(listOf("ultra-zh", "ultra-null", "ultra-en-a", "ultra-en-b", "ultra-other-a", "ultra-other-b",
            "alpha-zh", "alpha-en", "z"), ordered.map { it.uuid })
        assertEquals(products.map { it.uuid }.toSet(), ordered.map { it.uuid }.toSet())
        assertEquals(listOf("alpha-zh", "alpha-en"), orderedCatalogProducts(catalogue, null).take(2).map { it.uuid })
    }
}
