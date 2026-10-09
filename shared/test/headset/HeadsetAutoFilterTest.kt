package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.catalog.CatalogTestFixtures
import ink.lipoly.app.sunrise.catalog.CatalogResponse
import ink.lipoly.app.sunrise.catalog.matchesCatalogDevice
import ink.lipoly.app.sunrise.catalog.resolveCatalogReference
import ink.lipoly.app.sunrise.drop.DropProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class HeadsetAutoFilterTest {
    private val catalogue = CatalogTestFixtures.snapshot()
    private fun installCatalogueFilter(fixture: HeadsetAutoLoopFixture) {
        fixture.client.setAutoDeviceFilter { matchesCatalogDevice(catalogue, it.name) }
    }

    @Test fun strangerNeverConnectsAndDiscoveryStaysRaw() = runBlocking {
        val fixture = HeadsetAutoLoopFixture()
        try {
            val stranger = fixture.add("00:00:00:00:00:11", "Stranger")
            fixture.audio(stranger)
            installCatalogueFilter(fixture)
            fixture.client.startAutoConnect()
            fixture.awaitRefresh()
            fixture.awaitPhase(HeadsetPhase.IDLE)
            assertEquals(0, stranger.connects.value)
            val raw = fixture.client.discoverConnectedDevices()
            assertEquals(listOf(stranger.address), raw.map { it.address })
            assertFalse(matchesCatalogDevice(catalogue, raw.single().name))
            assertEquals(0, stranger.connects.value)
        } finally { fixture.dispose() }
    }

    @Test fun strangerPlusUltraConnectsOnlyUltra() = runBlocking {
        val fixture = HeadsetAutoLoopFixture()
        try {
            val stranger = fixture.add("00:00:00:00:00:11", "Stranger")
            val ultra = fixture.add("00:00:00:00:00:12", "  space   travel 2 ultra ")
            fixture.audio(stranger, ultra)
            installCatalogueFilter(fixture)
            fixture.client.startAutoConnect()
            val ready = fixture.awaitPhase(HeadsetPhase.READY)
            assertEquals(ultra.address, ready.device?.address)
            assertTrue(DropProtocol.GAIA_BLE in ready.controls.protocols)
            assertEquals(0, stranger.connects.value)
            assertEquals(1, ultra.connects.value)
            assertEquals(ultra.storage!!.bands, fixture.client.gaia.getParamEq().bands)
        } finally { fixture.dispose() }
    }

    @Test fun nonBluetoothCatalogueNameDoesNotBecomeAnAutomaticConnectionCandidate() = runBlocking {
        val responseUuid = "33333333-3333-4333-8333-333333333333"
        for (type in listOf("USB", "WIRED", "FUTURE", "bt")) {
            val fixture = HeadsetAutoLoopFixture()
            try {
                val nonBluetooth = fixture.add("00:00:00:00:00:21", "USB model")
                val bluetooth = fixture.add("00:00:00:00:00:22", "SPACE TRAVEL 2 ULTRA")
                val mixed = CatalogTestFixtures.snapshot(
                    catalogueBytes = CatalogTestFixtures.catalogue(listOf(
                        CatalogTestFixtures.product(),
                        CatalogTestFixtures.product(uuid = CatalogTestFixtures.SECOND_UUID, name = "USB model", type = type),
                    )),
                    responseLibraryBytes = CatalogTestFixtures.responseLibrary(listOf(
                        CatalogTestFixtures.responseEntry(uuid = responseUuid, name = "USB model"),
                    )),
                )
                assertFalse(matchesCatalogDevice(mixed, nonBluetooth.info.value.name))
                assertNull(resolveCatalogReference(mixed, nonBluetooth.info.value.name, null).product)
                val manual = resolveCatalogReference(mixed, nonBluetooth.info.value.name, responseUuid)
                assertEquals(responseUuid, manual.product?.uuid)
                assertEquals("Response", manual.product?.type)
                assertIs<CatalogResponse.Ready>(manual.response)
                val physical = resolveCatalogReference(mixed, nonBluetooth.info.value.name, CatalogTestFixtures.SECOND_UUID)
                assertEquals(CatalogTestFixtures.SECOND_UUID, physical.product?.uuid)
                assertIs<CatalogResponse.Ready>(physical.response)
                fixture.audio(nonBluetooth, bluetooth)
                fixture.client.setAutoDeviceFilter { matchesCatalogDevice(mixed, it.name) }
                fixture.client.startAutoConnect()
                assertEquals(bluetooth.address, fixture.awaitPhase(HeadsetPhase.READY).device?.address)
                assertEquals(0, nonBluetooth.connects.value)
                assertEquals(1, bluetooth.connects.value)
                assertEquals(setOf(nonBluetooth.address, bluetooth.address),
                    fixture.client.discoverConnectedDevices().map { it.address }.toSet())
            } finally { fixture.dispose() }
        }
    }

    @Test fun twoCatalogueMatchesRequireSelectionWithoutGatt() = runBlocking {
        val fixture = HeadsetAutoLoopFixture()
        try {
            val first = fixture.add("00:00:00:00:00:12", "SPACE TRAVEL 2 ULTRA")
            val second = fixture.add("00:00:00:00:00:13", "space travel 2 ultra")
            fixture.audio(first, second)
            installCatalogueFilter(fixture)
            fixture.client.startAutoConnect()
            val selection = fixture.awaitPhase(HeadsetPhase.SELECTION_REQUIRED)
            assertNull(selection.device)
            assertEquals(0, first.connects.value)
            assertEquals(0, second.connects.value)
            assertEquals(2, fixture.client.discoverConnectedDevices().size)
        } finally { fixture.dispose() }
    }

    @Test fun initialGenericPolicyAcceptsAllDevices() = runBlocking {
        val fixture = HeadsetAutoLoopFixture()
        try {
            val stranger = fixture.add("00:00:00:00:00:11", null)
            fixture.audio(stranger)
            fixture.client.startAutoConnect()
            assertEquals(stranger.address, fixture.awaitPhase(HeadsetPhase.READY).device?.address)
            assertEquals(1, stranger.connects.value)
        } finally { fixture.dispose() }
    }

    @Test fun showAllPredicateWakesIdleAndStrangerCanBeManuallySelected() = runBlocking {
        val fixture = HeadsetAutoLoopFixture()
        try {
            val stranger = fixture.add("00:00:00:00:00:11", "Stranger")
            val renamed = fixture.add("00:00:00:00:00:14", "Renamed")
            fixture.audio(stranger, renamed)
            installCatalogueFilter(fixture)
            fixture.client.startAutoConnect()
            fixture.awaitRefresh()
            fixture.awaitPhase(HeadsetPhase.IDLE)
            assertEquals(0, stranger.connects.value)
            assertEquals(0, renamed.connects.value)
            fixture.client.setAutoDeviceFilter { true }
            fixture.awaitPhase(HeadsetPhase.SELECTION_REQUIRED)
            val shown = fixture.client.discoverConnectedDevices()
            assertEquals(2, shown.size)
            fixture.client.connect(shown.first { it.address == stranger.address })
            assertEquals(stranger.address, fixture.client.state.value.device?.address)
            assertEquals(HeadsetPhase.READY, fixture.client.state.value.phase)
            assertEquals(1, stranger.connects.value)
            assertEquals(0, renamed.connects.value)
        } finally { fixture.dispose() }
    }

    @Test fun restrictiveAutoFilterNeverBlocksExplicitManualConnect() = runBlocking {
        val fixture = HeadsetAutoLoopFixture()
        try {
            val stranger = fixture.add("00:00:00:00:00:11", "Stranger")
            fixture.audio(stranger)
            installCatalogueFilter(fixture)
            fixture.client.connect(fixture.client.discoverConnectedDevices().single())
            assertEquals(HeadsetPhase.READY, fixture.client.state.value.phase)
            assertEquals(1, stranger.connects.value)
            assertEquals(stranger.storage!!.bands, fixture.client.gaia.getParamEq().bands)
        } finally { fixture.dispose() }
    }

    @Test fun predicateChangeRetainsReadyTargetSessionAndControlBinding() = runBlocking {
        val fixture = HeadsetAutoLoopFixture()
        try {
            val ultra = fixture.add("00:00:00:00:00:12", "SPACE TRAVEL 2 ULTRA")
            fixture.audio(ultra)
            installCatalogueFilter(fixture)
            fixture.client.startAutoConnect()
            fixture.awaitPhase(HeadsetPhase.READY)
            val session = ultra.gatt.state.value.session
            val gaia = fixture.client.gaia
            val readback = gaia.getParamEq()
            fixture.client.setAutoDeviceFilter { false }
            delay(150.milliseconds) // Give a mistaken restart/disconnect a chance to execute on the client's real dispatcher.
            assertEquals(HeadsetPhase.READY, fixture.client.state.value.phase)
            assertEquals(ultra.address, fixture.client.state.value.device?.address)
            assertSame(session, ultra.gatt.state.value.session)
            assertSame(gaia, fixture.client.gaia)
            assertEquals(readback, gaia.getParamEq()) // An old epoch would reject this actual byte-level request.
            assertEquals(1, ultra.connects.value)
            assertEquals(0, ultra.disconnects.value)
        } finally { fixture.dispose() }
    }

    @Test fun predicateChangePreservesCurrentEpochAfterManualReplacement() = runBlocking {
        val fixture = HeadsetAutoLoopFixture()
        try {
            val previous = fixture.add("00:00:00:00:00:12", "SPACE TRAVEL 2 ULTRA")
            val current = fixture.add("00:00:00:00:00:11", "Stranger")
            fixture.audio(previous, current)
            fixture.client.connect(previous.address)
            val obsoleteGaia = fixture.client.gaia
            fixture.client.connect(current.address)
            val currentGaia = fixture.client.gaia
            val session = current.gatt.state.value.session
            installCatalogueFilter(fixture)
            delay(150.milliseconds)
            assertEquals(HeadsetPhase.READY, fixture.client.state.value.phase)
            assertEquals(current.address, fixture.client.state.value.device?.address)
            assertSame(session, current.gatt.state.value.session)
            assertSame(currentGaia, fixture.client.gaia)
            assertNotSame(obsoleteGaia, currentGaia)
            assertEquals(current.storage!!.bands, currentGaia.getParamEq().bands)
            assertEquals(1, previous.disconnects.value)
            assertEquals(1, current.connects.value)
            assertEquals(0, current.disconnects.value)
        } finally { fixture.dispose() }
    }
}
