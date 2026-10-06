package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.blueConnector.BtException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** 经典 RFCOMM 写入路径：组帧、顺序、间隔与失败处理；读取仍在 BLE GATT。 */
class GaiaClassicEqTest {
    private val officialPdu = ("001d0a060004feb0003c0ccc0000b400640e6600009600" +
        "96100000007807d00b330000002ee00b33000000")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun writes(device: GaiaGattDeviceFixture) = device.commands.filter {
        it.feature == GaiaIds.MUSIC_PROCESSING && it.command == GaiaIds.Eq.SET_USER_CONFIG
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    @Test fun classicChannelCarriesOfficialFramingAndKeepsBleForReads() = runBlocking {
        val official = GaiaBluetrumPeqCodec.decode(officialPdu.copyOfRange(4, officialPdu.size), 0..4)
        val channel = FakeRfcomm()
        val device = GaiaGattDeviceFixture(5, 512).apply {
            bands = official.bands
            totalGainRaw = official.totalGainRaw
            rfcomm = channel
        }
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            assertEquals(official.bands, loaded.bands)
            device.commands.clear()
            binding.gaia.setParamEq(loaded.bands)
            // 读取仍在 BLE：只有段数查询，不再有 BLE 的激活或配置写入。
            assertEquals(listOf(GaiaIds.Eq.GET_BAND_COUNT), device.commands.map { it.command })
            assertTrue(writes(device).isEmpty())
            assertNull(binding.state.value.paramEq)
            assertEquals(1, channel.opened)
            assertEquals(listOf(
                "ff040001001d0a033f",
                "ff040027" + hex(officialPdu),
            ), channel.frames.map(::hex))
            val gapMs = (channel.writtenAt[1] - channel.writtenAt[0]) / 1_000_000.0
            assertTrue(gapMs >= 90.0, "activation gap $gapMs ms")
        } finally { binding.close() }
    }

    @Test fun classicChannelChunksLongConfigurationAndNeverFallsBackToBle() = runBlocking {
        val channel = FakeRfcomm(failWrite = BtException.Transport("RFCOMM write failed"))
        val device = GaiaGattDeviceFixture(10, 512).apply { rfcomm = channel }
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            val target = loaded.bands.map { it.copy(gainRaw = 180) }
            device.commands.clear()
            assertFailsWith<DropException.Transport> { binding.gaia.setParamEq(target) }
            // 失败发生在经典链路：不重试、不回退 BLE，且失效写入元数据。
            assertEquals(listOf(GaiaIds.Eq.GET_BAND_COUNT), device.commands.map { it.command })
            assertTrue(writes(device).isEmpty())
            assertNull(binding.state.value.paramEq)
            assertEquals(-17, device.totalGainRaw)
            val afterFailure = device.commands.size
            assertFailsWith<DropException.NotReady> { binding.gaia.setParamEq(target) }
            assertEquals(afterFailure, device.commands.size)
            channel.failWrite = null
            binding.gaia.getParamEq()
            binding.gaia.setParamEq(target)
            // 10 段拆成两块，每块 7 段与 3 段，长度字段只计 GAIA 负载。
            assertEquals(listOf(4 + 4 + 4 + 49, 4 + 4 + 4 + 21), channel.frames.drop(1).map { it.size })
            assertEquals(0xff, channel.frames[1][0].toInt() and 0xff)
            assertEquals(0x04, channel.frames[1][1].toInt() and 0xff)
            assertEquals(0x00, channel.frames[1][2].toInt() and 0xff)
            assertEquals(53, channel.frames[1][3].toInt() and 0xff)
            assertEquals(25, channel.frames[2][3].toInt() and 0xff)
            assertNull(binding.state.value.paramEq)
        } finally { binding.close() }
    }

    @Test fun transientWriteFailuresReconnectAndRetryTheSameFrame() = runBlocking {
        // 设备侧偶发关闭 SPP 通道：控件层每帧最多尝试三次，较晚的尝试会关闭并重开通道。
        val channel = FakeRfcomm()
        val device = GaiaGattDeviceFixture(5, 512).apply { rfcomm = channel }
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            channel.failWritesRemaining = 2
            binding.gaia.setParamEq(loaded.bands)
            // 前两次激活帧写入失败，第三次重连后成功；配置帧随后一次成功。
            assertEquals(3, channel.opened)
            assertEquals(2, channel.closed)
            assertEquals(2, channel.frames.size)
            assertEquals("ff040001001d0a033f", hex(channel.frames[0]))
            assertEquals(0xff, channel.frames[1][0].toInt() and 0xff)
            assertEquals(0x27, channel.frames[1][3].toInt() and 0xff)
            assertNull(binding.state.value.paramEq)
        } finally { binding.close() }
    }

    @Test fun exhaustedFrameAttemptsFailWithoutBleFallback() = runBlocking {
        val channel = FakeRfcomm(failWritesRemaining = 3)
        val device = GaiaGattDeviceFixture(5, 512).apply { rfcomm = channel }
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            device.commands.clear()
            val target = loaded.bands.map { it.copy(gainRaw = 180) }
            assertFailsWith<DropException.Transport> { binding.gaia.setParamEq(target) }
            // 三次尝试用尽后不再继续：每次重试各关闭并重开一次，且不写 BLE。
            assertEquals(3, channel.opened)
            assertEquals(2, channel.closed)
            assertTrue(channel.frames.isEmpty())
            assertEquals(listOf(GaiaIds.Eq.GET_BAND_COUNT), device.commands.map { it.command })
            assertNull(binding.state.value.paramEq)
            val afterFailure = device.commands.size
            assertFailsWith<DropException.NotReady> { binding.gaia.setParamEq(target) }
            assertEquals(afterFailure, device.commands.size)
        } finally { binding.close() }
    }

    @Test fun rejectedFramesKeepSingleByteLengthBound() {
        val payload = ByteArray(GaiaRfcomm.MAX_PAYLOAD + 1)
        assertFailsWith<IllegalArgumentException> {
            GaiaRfcomm.frame(GaiaCommand(5, 6, payload))
        }
        val maximal = GaiaRfcomm.frame(GaiaCommand(5, 6, ByteArray(GaiaRfcomm.MAX_PAYLOAD)))
        assertEquals(4 + 4 + GaiaRfcomm.MAX_PAYLOAD, maximal.size)
        assertEquals(GaiaRfcomm.MAX_PAYLOAD, maximal[3].toInt() and 0xff)
    }
}
