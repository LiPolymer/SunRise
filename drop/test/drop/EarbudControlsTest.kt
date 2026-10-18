package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** 门面只投影绑定已确认的能力：电量读数、降噪委托，以及旧引用失效。 */
class EarbudControlsTest {

    @Test
    fun batteryReadingsKeepTheRawByteAndUpdateTheBindingSnapshot() = runBlocking {
        val device = GaiaGattDeviceFixture()
        device.batteryLevels = byteArrayOf(1, 82, 2, 0xE4.toByte(), 3, 101)
        val binding = readyBinding(device)
        try {
            val battery = binding.controls.battery!!.read()
            assertEquals(BatteryReading(raw = 82, percent = 82), battery.left)
            assertEquals(BatteryReading(raw = 0xE4, percent = 0xE4), battery.right)
            assertEquals(BatteryReading(raw = 101, percent = 101), battery.case)
            assertEquals(battery, binding.state.value.battery)
        } finally {
            binding.close()
        }
    }

    @Test
    fun capabilitiesExposeTheDetectedProtocolAndWritableModes() = runBlocking {
        val binding = readyBinding(GaiaGattDeviceFixture())
        try {
            val capabilities = binding.controls.capabilities
            assertEquals(setOf(DropProtocol.GAIA_BLE), capabilities.protocols)
            assertTrue(AncMode.OFF in capabilities.noiseModes)
        } finally {
            binding.close()
        }
    }

    @Test
    fun noiseDelegatesToGaiaAndReportsTheObservedMismatch() = runBlocking {
        val binding = readyBinding(GaiaGattDeviceFixture())
        try {
            val noise = binding.controls.noise!!
            assertEquals(AncMode.OFF, noise.read())
            // Device keeps answering OFF: verification must fail on readback, not report success.
            val mismatch = assertFailsWith<DropException.AncModeMismatch> {
                noise.write(AncMode.NOISE_CANCELLING)
            }
            assertEquals(AncMode.NOISE_CANCELLING, mismatch.requested)
            assertEquals(AncMode.OFF, mismatch.observed)
        } finally {
            binding.close()
        }
    }

    @Test
    fun closedBindingInvalidatesTheFacade() = runBlocking<Unit> {
        val binding = readyBinding(GaiaGattDeviceFixture())
        val controls = binding.controls
        val noise = controls.noise!!
        val battery = controls.battery!!
        binding.close()
        assertFailsWith<DropException.Disconnected> { controls.capabilities }
        assertFailsWith<DropException.Disconnected> { noise.read() }
        assertFailsWith<DropException.Disconnected> { battery.read() }
    }
}
