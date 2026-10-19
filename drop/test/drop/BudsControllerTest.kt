package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.bluetooth.GattPhase
import ink.lipoly.app.sunrise.drop.gaia.GaiaControls
import ink.lipoly.app.sunrise.drop.gaia.GaiaIds
import ink.lipoly.app.sunrise.drop.gaia.GaiaPacket
import ink.lipoly.app.sunrise.drop.gaia.openGaia
import ink.lipoly.app.sunrise.drop.moondrop.openMoondrop
import ink.lipoly.app.sunrise.drop.source.SourceControls
import ink.lipoly.app.sunrise.drop.source.SourceIds
import ink.lipoly.app.sunrise.drop.source.openSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** 会话级控制器：自有传输、能力 cast 与关闭语义；不证明 Android 无线或实体设备行为。 */
class BudsControllerTest {

    @Test
    fun ownedSessionConnectsCastsAndReleasesTransport() = runBlocking<Unit> {
        val fixture = GaiaGattDeviceFixture()
        val controller = openMoondrop(fixture.device)
        assertEquals(DropPhase.READY, controller.state.value.phase)
        assertEquals(GattPhase.CONNECTED, fixture.device.gatt.state.value.phase)
        assertEquals(setOf(DropProtocol.GAIA_BLE), controller.state.value.protocols)
        assertTrue(AncMode.OFF in controller.state.value.capabilities.ancModes)

        val battery = assertIs<BatteryControls>(controller)
        val reading = battery.readBattery()
        assertEquals(BatteryReading(raw = 82, percent = 82), reading.left)
        assertEquals(BatteryReading(raw = 76, percent = 76), reading.right)
        assertEquals(BatteryReading(raw = 64, percent = 64), reading.case)
        assertEquals(reading, controller.state.value.battery)

        val noise = assertIs<NoiseControls>(controller)
        assertEquals(AncMode.OFF, noise.readNoise())
        // Device keeps answering OFF: verification must fail on readback, not report success.
        val mismatch = assertFailsWith<DropException.AncModeMismatch> {
            noise.writeNoise(AncMode.NOISE_CANCELLING)
        }
        assertEquals(AncMode.NOISE_CANCELLING, mismatch.requested)
        assertEquals(AncMode.OFF, mismatch.observed)

        controller.close()
        // 幂等关闭；第二次不得重新打开或改动传输。
        controller.close()
        assertEquals(GattPhase.DISCONNECTED, fixture.device.gatt.state.value.phase)
        assertEquals(DropState(), controller.state.value)
        assertFailsWith<DropException.Disconnected> { battery.readBattery() }
        assertFailsWith<DropException.Disconnected> { noise.readNoise() }
    }

    @Test
    fun closingOneSessionLeavesAnotherIndependentSessionUsable() = runBlocking<Unit> {
        val first = openMoondrop(GaiaGattDeviceFixture(id = 1).device)
        val second = openMoondrop(GaiaGattDeviceFixture(id = 2).device)
        try {
            first.close()
            assertEquals(DropState(), first.state.value)
            assertFailsWith<DropException.Disconnected> { assertIs<NoiseControls>(first).readNoise() }
            assertEquals(DropPhase.READY, second.state.value.phase)
            assertEquals(AncMode.OFF, assertIs<NoiseControls>(second).readNoise())
            assertIs<BatteryControls>(second).readBattery()
        } finally {
            second.close()
        }
    }

    @Test
    fun optionalProbeFailuresDoNotRemovePresentFamilyInterfaces() = runBlocking {
        withTimeout(5_000) {
            val fixture = GaiaGattDeviceFixture(source = true, readableSourceInfo = false).apply {
                beforeReply = { packet, payload ->
                    if (packet.feature == GaiaIds.BASIC) byteArrayOf(0, 0) else payload
                }
                beforeSourceReply = { _, _ ->
                    throw ink.lipoly.app.sunrise.bluetooth.BtException.Transport("Optional firmware unavailable")
                }
            }
            val controller = openMoondrop(fixture.device)
            try {
                assertEquals(DropPhase.READY, controller.state.value.phase)
                assertEquals(setOf(DropProtocol.GAIA_BLE, DropProtocol.SOURCE_9ECA), controller.state.value.protocols)
                assertFalse(controller.state.value.capabilities.complete)
                assertIs<GaiaControls>(controller)
                assertIs<NoiseControls>(controller)
                assertEquals(82, assertIs<BatteryControls>(controller).readBattery().left?.percent)
                fixture.beforeSourceReply = { _, payload -> payload }
                assertEquals(2, assertIs<SourceControls>(controller).getAudioSource().currentSource)
            } finally { controller.close() }
        }
    }

    @Test
    fun featurePairsDetermineExactConsumerInterfaces() = runBlocking {
        withTimeout(5_000) {
            for ((gaia, source) in listOf(true to false, false to true, true to true)) {
                val fixture = GaiaGattDeviceFixture(gaia = gaia, source = source)
                val controller = openMoondrop(fixture.device)
                try {
                    assertEquals(DropPhase.READY, controller.state.value.phase)
                    assertEquals(buildSet {
                        if (gaia) add(DropProtocol.GAIA_BLE)
                        if (source) add(DropProtocol.SOURCE_9ECA)
                    }, controller.state.value.protocols)
                    assertEquals(gaia, controller is GaiaControls)
                    assertEquals(gaia, controller is BatteryControls)
                    assertEquals(gaia, controller is NoiseControls)
                    assertEquals(source, controller is SourceControls)
                    assertTrue(controller.state.value.capabilities.complete)
                    assertEquals(1, fixture.connectCount)
                    if (source) {
                        val controls = assertIs<SourceControls>(controller)
                        assertEquals(2, controls.getAudioSource().currentSource)
                        assertEquals(37, controls.getVolume().current)
                        assertEquals(0x1234, controls.getFirmwareInfo().featureFlags)
                        controls.readSourceCapability()
                    }
                } finally {
                    controller.close()
                }
                assertEquals(1, fixture.closeCount)
                assertEquals(GattPhase.DISCONNECTED, fixture.device.gatt.state.value.phase)
            }
            val absent = GaiaGattDeviceFixture(gaia = false)
            assertFailsWith<DropException.UnsupportedDevice> { openMoondrop(absent.device) }
            assertEquals(1, absent.connectCount)
            assertEquals(1, absent.closeCount)
            assertEquals(GattPhase.DISCONNECTED, absent.device.gatt.state.value.phase)
        }
    }

    @Test
    fun publicFamilyFactoriesRequireTheirOwnFeaturePairAndReleaseFailures() = runBlocking {
        withTimeout(5_000) {
            val profile = DropProfile(DropProfileMatch.NameContains("fixture"))
            val gaiaDevice = GaiaGattDeviceFixture(gaia = true, source = true)
            val gaia = openGaia(gaiaDevice.device, profile)
            try {
                assertEquals(DropPhase.READY, gaia.state.value.phase)
                assertEquals(setOf(DropProtocol.GAIA_BLE), gaia.state.value.protocols)
                assertIs<GaiaControls>(gaia)
                assertFalse(gaia is SourceControls)
                assertEquals(82, assertIs<BatteryControls>(gaia).readBattery().left?.percent)
            } finally { gaia.close() }

            val sourceDevice = GaiaGattDeviceFixture(gaia = true, source = true, readableSourceInfo = false)
            val source = openSource(sourceDevice.device)
            try {
                assertEquals(DropPhase.READY, source.state.value.phase)
                assertEquals(setOf(DropProtocol.SOURCE_9ECA), source.state.value.protocols)
                assertFalse(source is GaiaControls)
                assertFalse(source is BatteryControls)
                assertFalse(source is NoiseControls)
                val controls = assertIs<SourceControls>(source)
                assertEquals(2, controls.getAudioSource().currentSource)
                assertEquals(37, controls.getVolume().current)
                assertTrue(sourceDevice.sourceCommands.any { it.commandId == SourceIds.GET_FW_VERSION })
            } finally { source.close() }

            val noGaia = GaiaGattDeviceFixture(gaia = false, source = true)
            assertFailsWith<DropException.UnsupportedDevice> { openGaia(noGaia.device, profile) }
            assertEquals(1, noGaia.closeCount)
            assertEquals(GattPhase.DISCONNECTED, noGaia.device.gatt.state.value.phase)
            val noSource = GaiaGattDeviceFixture()
            assertFailsWith<DropException.UnsupportedDevice> { openSource(noSource.device) }
            assertEquals(1, noSource.closeCount)
            assertEquals(GattPhase.DISCONNECTED, noSource.device.gatt.state.value.phase)
        }
    }

    @Test
    fun combinedRequestsShareMutexAndSourceUpdatesPreserveBattery() = runBlocking {
        withTimeout(5_000) {
            val fixture = GaiaGattDeviceFixture(source = true)
            val controller = openMoondrop(fixture.device)
            try {
                val waiting = CompletableDeferred<GaiaPacket>()
                fixture.beforeReply = { packet, payload ->
                    if (packet.feature == GaiaIds.BATTERY) {
                        waiting.complete(packet)
                        null
                    } else payload
                }
                val batteryRequest = async(start = CoroutineStart.UNDISPATCHED) {
                    assertIs<BatteryControls>(controller).readBattery()
                }
                val pending = waiting.await()
                val source = assertIs<SourceControls>(controller)
                val sourceRequest = async(start = CoroutineStart.UNDISPATCHED) { source.getAudioSource() }
                assertFalse(sourceRequest.isCompleted)
                assertTrue(fixture.sourceWrites.isEmpty(), "Source must not write while GAIA owns the transaction")
                fixture.emitResponse(pending, fixture.batteryLevels)
                val battery = batteryRequest.await()
                assertEquals(2, sourceRequest.await().currentSource)
                assertEquals(battery, controller.state.value.battery)
                assertEquals(37, source.getVolume().current)
                assertEquals(battery, controller.state.value.battery)

                fixture.sourceId = 4
                fixture.emitSourceStatus()
                controller.state.first { it.sourceStatus?.currentSource == 4 }
                assertEquals(battery, controller.state.value.battery)
            } finally { controller.close() }
        }
    }

    @Test
    fun closingCombinedSessionFailsPendingAndLateFramesCannotPublish() = runBlocking {
        withTimeout(5_000) {
            val fixture = GaiaGattDeviceFixture(source = true)
            val controller = openMoondrop(fixture.device)
            val waiting = CompletableDeferred<GaiaPacket>()
            fixture.beforeReply = { packet, payload ->
                if (packet.feature == GaiaIds.BATTERY) {
                    waiting.complete(packet)
                    null
                } else payload
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<DropException.Disconnected> { assertIs<BatteryControls>(controller).readBattery() }
            }
            val request = waiting.await()
            controller.close()
            pending.await()
            fixture.emitResponse(request, byteArrayOf(1, 99, 2, 99, 3, 99))
            fixture.sourceId = 4
            fixture.emitSourceStatus()
            yield()
            assertEquals(DropState(), controller.state.value)
            assertFailsWith<DropException.Disconnected> { assertIs<SourceControls>(controller).getVolume() }
            controller.close()
            assertEquals(1, fixture.closeCount)
            assertEquals(GattPhase.DISCONNECTED, fixture.device.gatt.state.value.phase)
        }
    }

    @Test
    fun cancellingInitializationReleasesOwnedTransportAndClassicChannel() = runBlocking {
        withTimeout(5_000) {
            val fixture = GaiaGattDeviceFixture()
            val classic = FakeRfcomm()
            val entered = CompletableDeferred<Unit>()
            fixture.beforeReply = { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }
            val opening = async(start = CoroutineStart.UNDISPATCHED) {
                openGaia(fixture.device, DropProfile(DropProfileMatch.NameContains("fixture")),
                    GaiaGattDeviceFixture(id = 2).apply { rfcomm = classic }.device)
            }
            entered.await()
            opening.cancel()
            assertFailsWith<CancellationException> { opening.await() }
            assertEquals(1, fixture.closeCount)
            assertEquals(GattPhase.DISCONNECTED, fixture.device.gatt.state.value.phase)
            assertEquals(1, classic.closed)
        }
    }

    @Test
    fun replacementConnectionRetiresOldCapabilitiesWithoutClosingNewEpoch() = runBlocking {
        withTimeout(5_000) {
            val endpoint = GaiaGattDeviceFixture(id = 1)
            val old = openMoondrop(endpoint.device)
            val oldBattery = assertIs<BatteryControls>(old)
            val replacement = GaiaGattDeviceFixture(id = 2).apply {
                batteryLevels = byteArrayOf(1, 53, 2, 54, 3, 55)
            }
            endpoint.replaceConnection(replacement)
            old.state.first { it.phase == DropPhase.IDLE }
            val current = openMoondrop(endpoint.device)
            try {
                val writes = replacement.writes.size
                assertFailsWith<DropException.Disconnected> { oldBattery.readBattery() }
                assertEquals(writes, replacement.writes.size)
                old.close()
                assertEquals(DropPhase.READY, current.state.value.phase)
                assertEquals(53, assertIs<BatteryControls>(current).readBattery().left?.percent)
                assertEquals(0, replacement.closeCount)
            } finally {
                current.close()
            }
        }
    }
}
