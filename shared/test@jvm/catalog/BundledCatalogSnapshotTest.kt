package ink.lipoly.app.sunrise.catalog

import ink.lipoly.app.sunrise.resources.Res
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** Exercises the packaged Compose resource through the real JVM resource reader. */
class BundledCatalogSnapshotTest {
    private val fixtures = CatalogTestFixtures
    @Test fun bundledSnapshotActuallyDecodesWithEveryReferencedAssetPresent() = runTest {
        val bytes = Res.readBytes("files/moondrop-catalog.snapshot.json")
        val snapshot = decodeCatalogSnapshot(bytes)
        val root = fixtures.root(bytes)
        val physical = (root.getValue("catalogue") as JsonObject).getValue("data") as JsonArray
        val library = (root.getValue("responseLibrary") as JsonObject).getValue("data") as JsonArray
        val expectedUuids = (physical + library).map { ((it as JsonObject).getValue("uuid") as JsonPrimitive).content }
        assertEquals(expectedUuids, snapshot.products.map { it.uuid })
        assertEquals(expectedUuids.toSet(), snapshot.productsByUuid.keys)
        assertEquals(library.map { ((it as JsonObject).getValue("uuid") as JsonPrimitive).content }.toSet(), snapshot.responseLibraryUuids)
        val expectedPaths = buildSet {
            for (element in physical) {
                val path = (element as JsonObject)["freqResponse"] as? JsonPrimitive
                if (path != null && path.isString && path.content.isNotBlank()) add(path.content)
            }
            for (element in library) add(((element as JsonObject).getValue("file") as JsonPrimitive).content)
        }
        assertEquals(expectedPaths, snapshot.responsesByPath.keys)
        assertEquals(expectedPaths, snapshot.responseHashesByPath.keys)
        val assets = root.getValue("responseFiles") as JsonArray
        assertEquals(expectedPaths.size, assets.size)
        assertEquals(expectedPaths, assets.map { ((it as JsonObject).getValue("path") as JsonPrimitive).content }.toSet())
        for (asset in assets) {
            val file = asset as JsonObject
            val path = (file.getValue("path") as JsonPrimitive).content
            val hash = catalogSha256(fixtures.assetBytes(file))
            assertEquals(hash, (file.getValue("sha256") as JsonPrimitive).content)
            assertEquals(hash, snapshot.responseHashesByPath.getValue(path))
        }
        val readyCount = snapshot.responsesByPath.values.count { it is CatalogResponse.Ready }
        println("Bundled MOONDROP catalog: ${physical.size} physical records, ${library.size} Response records, ${snapshot.products.size} total records, ${snapshot.products.count { snapshot.responsesByPath[it.freqResponse] is CatalogResponse.Ready }} Ready products, ${snapshot.responsesByPath.size} assets, $readyCount Ready assets")
    }
}
