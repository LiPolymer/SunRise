package ink.lipoly.app.sunrise.presentation

import ink.lipoly.app.sunrise.catalog.*
import ink.lipoly.app.sunrise.di.*
import ink.lipoly.app.sunrise.settings.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

class RuntimeIsolationTest {
    private class Storage : CatalogStorage {
        var bytes: ByteArray? = null
        override suspend fun readActive() = bytes
        override suspend fun writeActive(bytes: ByteArray) { this.bytes = bytes.copyOf() }
    }
    private class Settings : UiSettingsStore {
        private val mutable = MutableStateFlow(UiSettings())
        override val state = mutable.asStateFlow()
        override fun update(next: UiSettings) { mutable.value = next }
    }
    private class Fetcher : CatalogByteFetcher {
        var closes = 0
        override suspend fun fetch(url: String, maxBytes: Int): ByteArray = error("Unexpected network")
        override fun close() { closes++ }
    }
    private fun runtime(scope: TestScope, storage: Storage, fetcher: Fetcher, document: ByteArray): SunRiseRuntime {
        val platformModule = module {
            single<UiSettingsStore> { Settings() }
            single { CatalogEnvironment({ storage }, { document }, { fetcher }, StandardTestDispatcher(scope.testScheduler)) }
            single { ConnectionCoordinator(null, get(), get(), get<ApplicationLifetime>().scope) }
        }
        return assembleRuntime(koinApplication { modules(applicationModule, platformModule) })
    }

    @Test fun closingOneContainerCannotDrainOrReplaceAnotherContainersCatalog() = runTest {
        val fixtures = CatalogTestFixtures
        val firstBytes = fixtures.document()
        val secondBytes = fixtures.document(fixtures.catalogue(listOf(fixtures.product(uuid = fixtures.SECOND_UUID))))
        val storageA = Storage()
        val storageB = Storage()
        val fetchA = Fetcher()
        val fetchB = Fetcher()
        val a = runtime(this, storageA, fetchA, firstBytes)
        val b = runtime(this, storageB, fetchB, secondBytes)
        try {
            a.awaitInitialized(); b.awaitInitialized()
            a.catalog.state.first { !it.loading }
            b.catalog.state.first { !it.loading }
            assertEquals(listOf(fixtures.UUID), a.catalog.getAll().map { it.uuid })
            assertEquals(listOf(fixtures.SECOND_UUID), b.catalog.getAll().map { it.uuid })
            a.settings.update(a.settings.current.copy(targetProductUuid = fixtures.UUID))
            assertNull(b.settings.current.targetProductUuid)
            a.catalog.importSnapshot(a.catalog.prepareImport(secondBytes))
            assertContentEquals(secondBytes, a.catalog.exportBytes())
            assertContentEquals(secondBytes, b.catalog.exportBytes())
            a.close()
            assertEquals(1, fetchA.closes)
            assertEquals(0, fetchB.closes)
            assertContentEquals(secondBytes, b.catalog.exportBytes())
            b.catalog.importSnapshot(b.catalog.prepareImport(firstBytes))
            assertContentEquals(firstBytes, storageB.bytes)
            assertEquals(listOf(fixtures.UUID), b.catalog.getAll().map { it.uuid })
            a.close()
            assertEquals(1, fetchA.closes)
            assertEquals(0, fetchB.closes)
        } finally {
            a.close(); b.close()
        }
        assertEquals(1, fetchB.closes)
    }
}
