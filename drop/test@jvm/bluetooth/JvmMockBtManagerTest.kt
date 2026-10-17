package ink.lipoly.app.sunrise.bluetooth

import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropController
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.GaiaCodec
import ink.lipoly.app.sunrise.drop.GaiaCommand
import ink.lipoly.app.sunrise.drop.GaiaIds
import ink.lipoly.app.sunrise.drop.GainLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class JvmMockBtManagerTest {
    private fun newManager(): BtManager = createBtManager(object : BtHost() {})
    private fun GattSession.command(): GattCharacteristic = services.single().characteristics.single {
        GattProperty.WRITE in it.properties
    }
    private fun GattSession.response(): GattCharacteristic = services.single().characteristics.single {
        GattProperty.NOTIFY in it.properties
    }

    @Test fun discoveryUsesOrMatchingAndUnknownAddressesDoNotBecomeDevices() = runTest {
        val manager = newManager()
        try {
            val unknown = manager.device("ab:cd:ef:01:23:45")
            assertSame(unknown, manager.device("AB:CD:EF:01:23:45"))
            assertEquals(BtDeviceInfo(), unknown.info.value)
            assertFalse(unknown.requestBond())
            assertFailsWith<BtException.Transport> { unknown.gatt.connect() }
            assertEquals(GattPhase.ERROR, unknown.gatt.state.value.phase)
            assertEquals(BtDeviceInfo(), unknown.info.value)
            assertFailsWith<BtException.InvalidDevice> { manager.device(" AB:CD:EF:01:23:45") }

            val device = manager.refreshConnectedAudioDevices().single()
            assertEquals(BtDeviceKind.DUAL, device.info.value.kind)
            assertEquals(BtBondState.BONDED, device.info.value.bondState)
            assertNull(device.rfcomm)
            assertSame(device, manager.bondedDevices().single())
            assertSame(device, manager.scanLe(name = "unmatched", address = device.address, timeoutMillis = 1).single())
            assertSame(device, manager.scanLe(name = "moondrop moca", address = unknown.address, timeoutMillis = 1).single())
            assertTrue(manager.scanLe(name = "unmatched", address = unknown.address, timeoutMillis = 1).isEmpty())
            assertTrue(manager.scanLe(name = "MOCA", timeoutMillis = 1).isEmpty())
            assertSame(device, manager.scanLe(timeoutMillis = 1).single())
            assertFailsWith<IllegalArgumentException> { manager.scanLe(timeoutMillis = 0) }
            assertFailsWith<BtException.InvalidDevice> { manager.scanLe(address = "invalid", timeoutMillis = 1) }
        } finally {
            manager.close()
        }
    }

    @Test fun reconnectCreatesFreshHandlesAndOldCloseCannotRetireReplacement() = runTest {
        val manager = newManager()
        val otherManager = newManager()
        try {
            val device = manager.refreshConnectedAudioDevices().single()
            val first = device.gatt.connect()
            assertSame(first, device.gatt.connect())
            val oldCommand = first.command()
            val oldResponse = first.response()
            device.gatt.disconnect()
            val replacement = device.gatt.connect()
            assertNotSame(first, replacement)
            assertTrue(replacement.id > first.id)
            assertNotSame(oldCommand, replacement.command())
            assertFailsWith<BtException.Disconnected> { first.requestMtu(247) }
            assertFailsWith<BtException.Disconnected> { first.write(oldCommand, byteArrayOf()) }
            assertFailsWith<BtException.InvalidGattHandle> { replacement.write(oldCommand, byteArrayOf()) }
            assertFailsWith<BtException.InvalidGattHandle> { replacement.setNotifications(oldResponse, true) }
            assertFailsWith<BtException.InvalidGattHandle> { replacement.read(oldResponse) }

            val foreign = otherManager.refreshConnectedAudioDevices().single().gatt.connect().command()
            assertFailsWith<BtException.InvalidGattHandle> { replacement.write(foreign, byteArrayOf()) }
            val fabricated = object : GattCharacteristic {
                override val serviceUuid = replacement.command().serviceUuid
                override val uuid = replacement.command().uuid
                override val properties = replacement.command().properties
            }
            assertFailsWith<BtException.InvalidGattHandle> { replacement.write(fabricated, byteArrayOf()) }
            first.close()
            assertSame(replacement, device.gatt.state.value.session)
            assertEquals(247, replacement.requestMtu(247))
            replacement.close()
            assertEquals(GattPhase.DISCONNECTED, device.gatt.state.value.phase)
        } finally {
            manager.close()
            otherManager.close()
        }
    }

    @Test fun closedManagerInvalidatesEveryCachedGattAndCannotReconnect() = runTest {
        val manager = newManager()
        val device = manager.refreshConnectedAudioDevices().single()
        val unknown = manager.device("AB:CD:EF:01:23:45")
        val session = device.gatt.connect()
        manager.close()
        manager.close()
        assertEquals(BtAvailability.CLOSED, manager.availability.value)
        assertTrue(manager.connectedAudioDevices.value.isEmpty())
        assertEquals(GattPhase.CLOSED, device.gatt.state.value.phase)
        assertEquals(GattPhase.CLOSED, unknown.gatt.state.value.phase)
        assertFailsWith<BtException.Disconnected> { device.gatt.connect() }
        assertFailsWith<BtException.Disconnected> { unknown.gatt.connect() }
        assertFailsWith<BtException.Disconnected> { session.write(session.command(), byteArrayOf()) }
        assertFailsWith<BtException.Disconnected> { session.setNotifications(session.response(), true) }
        assertFailsWith<BtException.Disconnected> { manager.device(device.address) }
        assertFailsWith<BtException.Disconnected> { manager.refreshConnectedAudioDevices() }
        assertFailsWith<BtException.Disconnected> { manager.bondedDevices() }
        assertFailsWith<BtException.Disconnected> { manager.scanLe(timeoutMillis = 1) }
        assertFailsWith<BtException.Disconnected> { device.requestBond() }
        session.close()
        device.gatt.disconnect()
        assertEquals(GattPhase.CLOSED, device.gatt.state.value.phase)
    }

    @Test fun gattChecksPropertiesMtuAndNotificationSubscription() = runTest {
        val manager = newManager()
        try {
            val session = manager.refreshConnectedAudioDevices().single().gatt.connect()
            val command = session.command()
            val response = session.response()
            val notifications = mutableListOf<GattEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                session.events.collect { notifications += it }
            }
            val getter = GaiaCodec.encode(GaiaCommand(GaiaIds.BATTERY, GaiaIds.Battery.LEVELS))
            session.write(command, getter)
            assertTrue(notifications.isEmpty())
            session.setNotifications(response, true)
            session.write(command, getter)
            assertEquals(1, notifications.size)
            val event = notifications.single() as GattEvent.ValueChanged
            assertSame(response, event.characteristic)
            assertEquals(GaiaCodec.RESPONSE, assertNotNull(GaiaCodec.decode(event.value)).type)
            session.setNotifications(response, false)
            session.write(command, getter)
            assertEquals(1, notifications.size)

            assertFailsWith<BtException.UnsupportedOperation> { session.read(command) }
            assertFailsWith<BtException.UnsupportedOperation> { session.write(response, getter) }
            assertFailsWith<BtException.UnsupportedOperation> { session.setNotifications(command, true) }
            assertFailsWith<BtException.PacketTooLarge> { session.write(command, ByteArray(21)) }
            assertFailsWith<IllegalArgumentException> { session.requestMtu(22) }
            assertFailsWith<IllegalArgumentException> { session.requestMtu(518) }
            assertEquals(517, session.requestMtu(517))
            assertEquals(517, session.mtu.value)
            session.close()
            assertFailsWith<BtException.Disconnected> { session.write(command, getter) }
            assertEquals(1, notifications.size)
        } finally {
            manager.close()
        }
    }

    @Test fun productionControllerReadsWritesAndRetainsOnlyItsOwnPeripheralOnReconnect() = runBlocking {
        val manager = newManager()
        val independent = newManager()
        val device = manager.refreshConnectedAudioDevices().single()
        val otherDevice = independent.refreshConnectedAudioDevices().single()
        val controller = DropController(device)
        val otherController = DropController(otherDevice)
        try {
            withTimeout(5.seconds) {
                device.gatt.connect()
                controller.awaitReady()
                val originalControls = controller.gaia
                val battery = originalControls.getBattery()
                assertNotNull(battery.left)
                assertNotNull(battery.right)
                assertNotNull(battery.case)
                val initialMode = originalControls.getAncMode()
                val desiredMode = if (initialMode == AncMode.TRANSPARENCY) AncMode.OFF else AncMode.TRANSPARENCY
                assertEquals(desiredMode, originalControls.setAncMode(desiredMode))
                assertEquals(GainLevel.HIGH, originalControls.setGain(GainLevel.HIGH))
                val initialEq = originalControls.getParamEq()
                val changedBands = initialEq.bands.mapIndexed { index, band ->
                    if (index == 0) band.copy(gainRaw = 120) else band
                }
                originalControls.setParamEq(changedBands)
                assertEquals(changedBands, originalControls.getParamEq().bands)

                device.gatt.disconnect()
                device.gatt.connect()
                controller.awaitReady()
                assertFailsWith<DropException.Disconnected> { originalControls.getBattery() }
                assertEquals(desiredMode, controller.gaia.getAncMode())
                assertEquals(GainLevel.HIGH, controller.gaia.getGain())
                assertEquals(changedBands, controller.gaia.getParamEq().bands)

                otherDevice.gatt.connect()
                otherController.awaitReady()
                assertEquals(initialMode, otherController.gaia.getAncMode())
                assertEquals(initialEq.bands, otherController.gaia.getParamEq().bands)
                manager.close()
                assertEquals(initialMode, otherController.gaia.getAncMode())
            }
        } finally {
            controller.close()
            otherController.close()
            manager.close()
            independent.close()
        }
    }
}
