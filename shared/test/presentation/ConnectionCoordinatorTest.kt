package ink.lipoly.app.sunrise.presentation

import ink.lipoly.app.sunrise.catalog.*
import ink.lipoly.app.sunrise.headset.*
import ink.lipoly.app.sunrise.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {
    private class Settings : UiSettingsStore {
        private val mutable = MutableStateFlow(UiSettings())
        override val state = mutable.asStateFlow()
        override fun update(next: UiSettings) { mutable.value = next }
    }
    private class Storage(private val read: suspend () -> ByteArray? = { null }) : CatalogStorage {
        override suspend fun readActive() = read()
        override suspend fun writeActive(bytes: ByteArray) = Unit
    }
    private class OfflineFetcher : CatalogByteFetcher {
        override suspend fun fetch(url: String, maxBytes: Int): ByteArray = error("Unexpected network")
        override fun close() = Unit
    }
    private suspend fun pauseForClient() = withContext(Dispatchers.Default) { delay(150.milliseconds) }
    private suspend fun ready(fixture: HeadsetAutoLoopFixture) = withContext(Dispatchers.Default) {
        fixture.awaitPhase(HeadsetPhase.READY)
    }

    @Test fun unreportedPermissionsAndLoadingCannotStartConnections() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = HeadsetAutoLoopFixture()
        val ultra = fixture.add("00:00:00:00:00:12", "SPACE TRAVEL 2 ULTRA")
        fixture.audio(ultra)
        try {
            CatalogTestFixtures.withCatalog(this, Storage { entered.complete(Unit); release.await(); null },
                { CatalogTestFixtures.document() }, OfflineFetcher()) { catalog ->
                val coordinator = ConnectionCoordinator(fixture.client, catalog, Settings(), backgroundScope)
                try {
                    coordinator.start()
                    entered.await()
                    coordinator.retry()
                    runCurrent(); pauseForClient()
                    assertEquals(0, ultra.connects.value)
                    assertNull(coordinator.permissions.value)
                    coordinator.updatePermissions(emptySet())
                    coordinator.retry()
                    runCurrent(); pauseForClient()
                    assertEquals(0, ultra.connects.value)
                    release.complete(Unit)
                    catalog.state.first { !it.loading }
                    runCurrent()
                    assertEquals(ultra.address, ready(fixture).device?.address)
                    assertEquals(1, ultra.connects.value)
                } finally { coordinator.close(); release.complete(Unit) }
            }
        } finally { release.complete(Unit); fixture.dispose() }
    }

    @Test fun policyAndRepeatedReportsPreserveTheSelectedEpochAndReadback() = runTest {
        val fixture = HeadsetAutoLoopFixture()
        val stranger = fixture.add("00:00:00:00:00:11", "Stranger")
        val ultra = fixture.add("00:00:00:00:00:12", " space travel 2 ultra ")
        fixture.audio(stranger, ultra)
        try {
            CatalogTestFixtures.withCatalog(this, Storage(), { CatalogTestFixtures.document() }, OfflineFetcher()) { catalog ->
                val settings = Settings()
                val coordinator = ConnectionCoordinator(fixture.client, catalog, settings, backgroundScope)
                try {
                    coordinator.start()
                    catalog.state.first { !it.loading }
                    runCurrent(); pauseForClient()
                    assertEquals(0, ultra.connects.value)
                    coordinator.updatePermissions(emptySet()); runCurrent()
                    assertEquals(ultra.address, ready(fixture).device?.address)
                    val session = ultra.gatt.state.value.session
                    val controls = fixture.client.gaia
                    // The facade owns a real Default dispatcher; its GATT deadlines must use real time too.
                    val readback = withContext(Dispatchers.Default) { controls.getParamEq() }
                    assertEquals(0, stranger.connects.value)
                    coordinator.start()
                    coordinator.updatePermissions(emptySet())
                    settings.update(settings.current.copy(targetProductUuid = CatalogTestFixtures.UUID, themeMode = ThemeMode.DARK))
                    runCurrent(); pauseForClient()
                    settings.update(settings.current.copy(catalogOnlyDevices = false))
                    catalog.importSnapshot(catalog.prepareImport(CatalogTestFixtures.document(
                        catalogueBytes = CatalogTestFixtures.catalogue(listOf(CatalogTestFixtures.product(name = "Other name"))),
                    )))
                    runCurrent(); pauseForClient()
                    assertSame(session, ultra.gatt.state.value.session)
                    assertSame(controls, fixture.client.gaia)
                    assertEquals(readback, withContext(Dispatchers.Default) { controls.getParamEq() })
                    assertEquals(1, ultra.connects.value)
                    assertEquals(0, ultra.disconnects.value)
                    val presentationScope = CoroutineScope(coroutineContext + Dispatchers.Default)
                    val presentationA = HeadsetPresentationController(coordinator, presentationScope)
                    pauseForClient()
                    presentationA.close()
                    val presentationB = HeadsetPresentationController(coordinator, presentationScope)
                    try {
                        coordinator.updatePermissions(emptySet())
                        runCurrent(); pauseForClient()
                        assertSame(session, ultra.gatt.state.value.session)
                        assertEquals(readback, withContext(Dispatchers.Default) { controls.getParamEq() })
                        assertEquals(1, ultra.connects.value)
                    } finally { presentationB.close() }
                    coordinator.close()
                    assertEquals(readback, withContext(Dispatchers.Default) { controls.getParamEq() })
                } finally { coordinator.close() }
            }
        } finally { fixture.dispose() }
    }

    @Test fun permissionRecoveryAndExplicitRetryAreTheOnlyRestartBoundaries() = runTest {
        val fixture = HeadsetAutoLoopFixture()
        val ultra = fixture.add("00:00:00:00:00:12", "SPACE TRAVEL 2 ULTRA")
        fixture.audio(ultra)
        try {
            CatalogTestFixtures.withCatalog(this, Storage(), { CatalogTestFixtures.document() }, OfflineFetcher()) { catalog ->
                val coordinator = ConnectionCoordinator(fixture.client, catalog, Settings(), backgroundScope)
                try {
                    coordinator.start()
                    catalog.state.first { !it.loading }
                    coordinator.updatePermissions(setOf("BLUETOOTH_CONNECT")); runCurrent()
                    coordinator.retry(); pauseForClient()
                    assertEquals(0, ultra.connects.value)
                    coordinator.updatePermissions(emptySet()); runCurrent(); ready(fixture)
                    val firstSession = ultra.gatt.state.value.session
                    coordinator.updatePermissions(setOf("BLUETOOTH_CONNECT")); runCurrent()
                    coordinator.retry(); pauseForClient()
                    assertSame(firstSession, ultra.gatt.state.value.session)
                    coordinator.updatePermissions(emptySet()); runCurrent()
                    withContext(Dispatchers.Default) { withTimeout(8_000.milliseconds) { ultra.connects.first { it == 2 } } }
                    ready(fixture)
                    assertNotSame(firstSession, ultra.gatt.state.value.session)
                    val secondSession = ultra.gatt.state.value.session
                    coordinator.updatePermissions(emptySet()); runCurrent(); pauseForClient()
                    assertSame(secondSession, ultra.gatt.state.value.session)
                    coordinator.retry()
                    withContext(Dispatchers.Default) { withTimeout(8_000.milliseconds) { ultra.connects.first { it == 3 } } }
                    ready(fixture)
                    assertNotSame(secondSession, ultra.gatt.state.value.session)
                } finally { coordinator.close() }
            }
        } finally { fixture.dispose() }
    }
}
