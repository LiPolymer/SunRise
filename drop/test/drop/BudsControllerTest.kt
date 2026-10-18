package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.bluetooth.GattPhase
import ink.lipoly.app.sunrise.drop.moondrop.openMoondrop
import kotlinx.coroutines.runBlocking
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
}
