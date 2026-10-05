package ink.lipoly.app.sunrise.catalog

import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class CatalogMatchingTest {
    private fun product(uuid: String, name: String = "SPACE TRAVEL 2 ULTRA", language: String? = "en-US", path: String? = null) =
        CatalogProduct(uuid, name, "Ultra", language, path, JsonObject(emptyMap()))

    private val ready = CatalogResponse.Ready(FrequencyResponse(doubleArrayOf(100.0, 1000.0), doubleArrayOf(40.0, 60.0)))
    private val unavailable = CatalogResponse.Unavailable("Unsupported data at line 2")
    private fun snapshot(products: List<CatalogProduct>, responses: Map<String, CatalogResponse> = emptyMap()) =
        CatalogSnapshot(byteArrayOf(), "2026-10-04T00:00:00Z", CATALOGUE_URL, CATALOG_CHINA_CDN_URL,
            products, responses, emptyMap())

    @Test fun exactNameOnlyWithUnicodeWhitespaceAndCaseNormalization() {
        val catalogue = snapshot(listOf(product("a")))
        for (name in listOf("  SPACE   TRAVEL 2 ULTRA ", "space\u00a0travel\u20032\tultra", "SPACE TRAVEL 2 ULTRA"))
            assertTrue(matchesCatalogDevice(catalogue, name))
        for (name in listOf(null, "", " \t\n\u2003", "Ultra", "Space Travel 2 Ultra XYZ",
            "MOONDROP SPACE TRAVEL 2 ULTRA", "SPACE-TRAVEL 2 ULTRA"))
            assertFalse(matchesCatalogDevice(catalogue, name))
        assertFalse(matchesCatalogDevice(null, "SPACE TRAVEL 2 ULTRA"))
    }

    @Test fun usableCurveOutranksPreferredLanguageAndNeverBorrowsAnotherName() {
        val englishMissing = product("a", language = "en-US")
        val chineseBroken = product("b", language = "zh-CN", path = "broken")
        val otherReady = product("c", language = "ja-JP", path = "ready")
        val unrelated = product("d", name = "Ultra Plus", path = "ready")
        val catalogue = snapshot(listOf(englishMissing, chineseBroken, otherReady, unrelated),
            mapOf("ready" to ready, "broken" to unavailable))
        val selection = resolveCatalogReference(catalogue, "space travel 2 ultra", null, true)
        assertSame(otherReady, selection.product)
        assertSame(ready, selection.response)
        assertFalse(selection.invalidManualBinding)
        assertNull(resolveCatalogReference(catalogue, "Ultra", null, true).product)
    }

    @Test fun languageThenEmptyThenOtherThenUuidBreakTies() {
        val en = product("f", language = "en-US", path = "ready")
        val zh = product("e", language = "zh-CN", path = "ready")
        val blank = product("d", language = null, path = "ready")
        val otherB = product("b", language = "de-DE", path = "ready")
        val otherA = product("a", language = "ja-JP", path = "ready")
        val all = listOf(otherB, otherA, blank, zh, en)
        val responses = mapOf("ready" to ready)
        assertSame(en, resolveCatalogReference(snapshot(all, responses), en.name, null, true).product)
        assertSame(zh, resolveCatalogReference(snapshot(all, responses), en.name, null, false).product)
        assertSame(blank, resolveCatalogReference(snapshot(all.take(3), responses), en.name, null, true).product)
        assertSame(otherA, resolveCatalogReference(snapshot(all.take(2), responses), en.name, null, true).product)
    }

    @Test fun explicitUuidOverridesNameLanguageAndCurveAvailabilityWithoutChangingFilter() {
        val auto = product("a", path = "ready")
        val manual = product("b", name = "Garden", language = "zh-CN", path = "broken")
        val noCurve = product("c", name = "Garden")
        val catalogue = snapshot(listOf(auto, manual, noCurve), mapOf("ready" to ready, "broken" to unavailable))
        val selected = resolveCatalogReference(catalogue, "Renamed headphones", manual.uuid, true)
        assertSame(manual, selected.product)
        assertSame(unavailable, selected.response)
        assertFalse(selected.invalidManualBinding)
        assertFalse(matchesCatalogDevice(catalogue, "Renamed headphones"))
        val missingCurve = resolveCatalogReference(catalogue, auto.name, noCurve.uuid, true)
        assertSame(noCurve, missingCurve.product)
        assertNull(missingCurve.response)
    }

    @Test fun vanishedUuidFallsBackAutomaticallyWithoutMigratingBinding() {
        val prior = product("old", language = "zh-CN", path = "broken")
        val replacement = product("new", language = "en-US", path = "ready")
        val before = snapshot(listOf(prior, replacement), mapOf("ready" to ready, "broken" to unavailable))
        assertSame(prior, resolveCatalogReference(before, prior.name, prior.uuid, true).product)
        val after = snapshot(listOf(replacement), mapOf("ready" to ready))
        val fallback = resolveCatalogReference(after, prior.name, prior.uuid, true)
        assertSame(replacement, fallback.product)
        assertSame(ready, fallback.response)
        assertTrue(fallback.invalidManualBinding)
        val noMatch = resolveCatalogReference(after, "Renamed", prior.uuid, true)
        assertNull(noMatch.product)
        assertTrue(noMatch.invalidManualBinding)
        assertFalse(resolveCatalogReference(null, prior.name, prior.uuid, true).invalidManualBinding)
    }

    @Test fun unavailableCurveAndNoCurveRemainRealMetadataSelections() {
        val broken = product("b", language = "zh-CN", path = "broken")
        val missing = product("a", language = "en-US")
        val catalogue = snapshot(listOf(broken, missing), mapOf("broken" to unavailable))
        assertSame(missing, resolveCatalogReference(catalogue, missing.name, null, true).product)
        val chinese = resolveCatalogReference(catalogue, missing.name, null, false)
        assertSame(broken, chinese.product)
        assertSame(unavailable, chinese.response)
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
        val english = orderedCatalogProducts(catalogue, " space   travel 2 ultra ", true)
        assertEquals(listOf("ultra-en-a", "ultra-en-b", "ultra-null", "ultra-other-a", "ultra-other-b", "ultra-zh",
            "alpha-en", "alpha-zh", "z"), english.map { it.uuid })
        assertEquals(products.map { it.uuid }.toSet(), english.map { it.uuid }.toSet())
        val chinese = orderedCatalogProducts(catalogue, "SPACE TRAVEL 2 ULTRA", false)
        assertEquals(listOf("ultra-zh", "ultra-null", "ultra-en-a", "ultra-en-b", "ultra-other-a", "ultra-other-b",
            "alpha-zh", "alpha-en", "z"), chinese.map { it.uuid })
        assertEquals(listOf("alpha-en", "alpha-zh"), orderedCatalogProducts(catalogue, null, true).take(2).map { it.uuid })
    }
}
