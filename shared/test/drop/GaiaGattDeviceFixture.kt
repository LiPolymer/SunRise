package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.blueConnector.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout

/** In-memory device storage behind real GATT bytes and the production binding's notification collector. */
internal class GaiaGattDeviceFixture(count: Int = 5, negotiatedMtu: Int = 247, override val id: Long = 1) : GattSession {
    private class Characteristic(override val uuid: String, override val properties: Set<GattProperty>) : GattCharacteristic {
        override val serviceUuid = DropGattIds.GAIA_SERVICE
    }
    private val commandCharacteristic = Characteristic(DropGattIds.GAIA_COMMAND, setOf(GattProperty.WRITE))
    private val responseCharacteristic = Characteristic(DropGattIds.GAIA_RESPONSE, setOf(GattProperty.NOTIFY))
    override val services = listOf(GattService(DropGattIds.GAIA_SERVICE, listOf(commandCharacteristic, responseCharacteristic)))
    override val mtu = MutableStateFlow(negotiatedMtu)
    override val events = MutableSharedFlow<GattEvent>()
    private var closed = false
    private var notifications = false
    var present = true
    /** 经典 RFCOMM 通道；非空时结构化 EQ 写入走它，测试不模拟 RFCOMM 组帧。 */
    var rfcomm: BtRfcomm? = null
    var presets = listOf(0, 2, 63)
    var currentPreset = 2
    var totalGainRaw = -17
    var bands = List(count) { GaiaPeqBand(it, 1000 + it * 100, 0, 4096, PeqFilter.PEAKING) }
    val codecStorage = mutableMapOf(AudioCodec.LC3 to false, AudioCodec.LDAC to false, AudioCodec.LHDC to false)
    var clampBand: (GaiaPeqBand) -> GaiaPeqBand = { it }
    var acceptCodecWrites = true
    var acceptPresetWrites = true
    val writes = mutableListOf<ByteArray>()
    val commands = mutableListOf<GaiaPacket>()
    /** May delay or fail a transport send after recording its packet, before touching device storage. */
    var beforeWrite: suspend (GaiaPacket) -> Unit = {}
    /** May inject malformed readback, drop a response, or suspend a GET after device storage was updated. */
    var beforeReply: suspend (GaiaPacket, ByteArray) -> ByteArray? = { _, payload -> payload }

    override val device: BtDevice = object : BtDevice {
        override val address = "00:00:00:00:00:01"
        override val rfcomm: BtRfcomm? get() = this@GaiaGattDeviceFixture.rfcomm
        override val info = MutableStateFlow(BtDeviceInfo(name = "Bluetrum fixture"))
        override val gatt: BtGatt = object : BtGatt {
            override val state = MutableStateFlow(GattState(GattPhase.CONNECTED, this@GaiaGattDeviceFixture))
            override suspend fun connect(): GattSession {
                check(!closed)
                return this@GaiaGattDeviceFixture
            }
            override suspend fun disconnect() {
                close()
                state.value = GattState()
            }
        }
        override val manager: BtManager = object : BtManager {
            override val availability = MutableStateFlow(BtAvailability.ENABLED)
            override val devices = MutableStateFlow<List<BtDevice>>(emptyList())
            override val connectedAudioDevices = MutableStateFlow<List<BtDevice>>(emptyList())
            override val events = MutableSharedFlow<BtEvent>()
            override fun device(address: String): BtDevice {
                require(address == this@GaiaGattDeviceFixture.device.address)
                return this@GaiaGattDeviceFixture.device
            }
            override suspend fun refreshConnectedAudioDevices(): List<BtDevice> = listOf(this@GaiaGattDeviceFixture.device)
            override suspend fun bondedDevices(): List<BtDevice> = listOf(this@GaiaGattDeviceFixture.device)
            override suspend fun scanLe(name: String?, address: String?, timeoutMillis: Long): List<BtDevice> =
                listOf(this@GaiaGattDeviceFixture.device).filter { (name == null || it.info.value.name == name) && (address == null || it.address == address) }
            override fun close() {
                this@GaiaGattDeviceFixture.close()
                availability.value = BtAvailability.CLOSED
            }
        }
        override fun requestBond(): Boolean = true
    }

    override suspend fun read(characteristic: GattCharacteristic): ByteArray =
        throw BtException.UnsupportedOperation("Fixture characteristics are write/notify only")

    override suspend fun setNotifications(characteristic: GattCharacteristic, enabled: Boolean) {
        if (characteristic !== responseCharacteristic) throw BtException.InvalidGattHandle()
        notifications = enabled
    }
    override suspend fun requestMtu(value: Int): Int = mtu.value // Device may negotiate less than requested.
    override fun close() { closed = true }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray) {
        if (closed) throw BtException.Disconnected()
        if (characteristic !== commandCharacteristic) throw BtException.InvalidGattHandle()
        if (value.size > mtu.value - 3) throw BtException.PacketTooLarge()
        val packet = GaiaCodec.decode(value) ?: error("Invalid test command")
        check(packet.type == GaiaCodec.COMMAND)
        writes += value.copyOf()
        commands += packet
        beforeWrite(packet)
        val payload: ByteArray? = when (packet.feature) {
            GaiaIds.BASIC -> byteArrayOf(0, GaiaIds.MUSIC_PROCESSING.toByte(), 1, GaiaIds.CODEC_TYPE.toByte(), 1, GaiaIds.ANC_V2.toByte(), 1)
            GaiaIds.CODEC_TYPE -> codecCommand(packet)
            GaiaIds.MUSIC_PROCESSING -> eqCommand(packet)
            GaiaIds.ANC_V2 -> byteArrayOf(0)
            else -> error("Unexpected feature ${packet.feature}")
        }
        if (payload != null) beforeReply(packet, payload)?.let { emitResponse(packet, it) }
    }

    private fun codecCommand(packet: GaiaPacket): ByteArray? {
        val codec = when (packet.command) {
            1, 3 -> AudioCodec.LC3
            2, 4 -> AudioCodec.LDAC
            5, 6 -> AudioCodec.LHDC
            else -> error("Unknown codec command")
        }
        return if (packet.command in setOf(3, 4, 6)) {
            if (acceptCodecWrites) codecStorage[codec] = packet.payload.single().toInt() == 1
            null // No SET ACK: successful behavior must depend on GET, not an echo.
        } else byteArrayOf(if (codecStorage.getValue(codec)) 1 else 0)
    }

    private fun eqCommand(packet: GaiaPacket): ByteArray? = when (packet.command) {
        GaiaIds.Eq.GET_STATE -> byteArrayOf(if (present) 1 else 0)
        GaiaIds.Eq.GET_PRESETS -> ByteArray(1 + presets.size).also { result ->
            result[0] = presets.size.toByte()
            presets.forEachIndexed { index, preset -> result[index + 1] = preset.toByte() }
        }
        GaiaIds.Eq.GET_PRESET -> byteArrayOf(currentPreset.toByte())
        GaiaIds.Eq.SET_PRESET -> {
            if (acceptPresetWrites) currentPreset = packet.payload.single().toInt() and 0xff
            byteArrayOf(currentPreset.toByte())
        }
        GaiaIds.Eq.GET_BAND_COUNT -> byteArrayOf(bands.size.toByte())
        GaiaIds.Eq.GET_USER_CONFIG -> {
            val start = packet.payload[0].toInt() and 0xff
            val end = packet.payload[1].toInt() and 0xff
            configurationBytes(start..end)
        }
        GaiaIds.Eq.SET_USER_CONFIG -> {
            val start = packet.payload[0].toInt() and 0xff
            val end = packet.payload[1].toInt() and 0xff
            require(packet.payload.size == 4 + 7 * (end - start + 1))
            // Independent device-side decoding, not the production codec or returning the input.
            totalGainRaw = u16(packet.payload, 2).toShort().toInt()
            val updated = bands.toMutableList()
            for (index in start..end) {
                val offset = 4 + (index - start) * 7
                val filterId = packet.payload[offset + 4].toInt() and 0xff
                require(filterId == 0) { "Official Bluetrum bulk filter must be 0, received $filterId" }
                updated[index] = clampBand(GaiaPeqBand(index, u16(packet.payload, offset),
                    u16(packet.payload, offset + 5).toShort().toInt(), u16(packet.payload, offset + 2),
                    PeqFilter.PEAKING))
            }
            bands = updated
            null
        }
        else -> error("Unexpected EQ command ${packet.command}; no Flash operations allowed")
    }

    fun configurationBytes(range: IntRange): ByteArray {
        val result = ByteArray(4 + 7 * range.count())
        result[0] = range.first.toByte()
        result[1] = range.last.toByte()
        put16(result, 2, totalGainRaw)
        for (index in range) {
            val offset = 4 + (index - range.first) * 7
            val band = bands[index]
            put16(result, offset, band.frequencyHz)
            put16(result, offset + 2, band.qRaw)
            result[offset + 4] = if (band.filter == PeqFilter.PEAKING) 0 else band.filter.gaiaId.toByte()
            put16(result, offset + 5, band.gainRaw)
        }
        return result
    }

    suspend fun emitResponse(request: GaiaPacket, payload: ByteArray, command: Int = request.command) {
        if (!notifications) return
        val bytes = GaiaCodec.encode(GaiaCommand(request.feature, command, payload, request.vendor))
        // Type RESPONSE occupies bit 8 of the command word (high byte bit 0).
        bytes[2] = (bytes[2].toInt() or 1).toByte()
        events.emit(GattEvent.ValueChanged(responseCharacteristic, bytes))
    }

    private fun u16(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}

/** 测试用经典通道：记录帧与时间戳，可注入打开/写入失败，不模拟 RFCOMM 组帧。 */
internal class FakeRfcomm(
    override val address: String = "00:00:00:00:00:02",
    var failOpen: BtException? = null,
    var failWrite: BtException? = null,
    /** 让接下来若干次写入失败；用于验证重连重试次数与上限。 */
    var failWritesRemaining: Int = 0,
    var failWriteError: BtException = BtException.Transport("socket closed"),
) : BtRfcomm {
    val frames = mutableListOf<ByteArray>()
    val writtenAt = mutableListOf<Long>()
    var opened = 0
    var closed = 0
    override suspend fun open() {
        opened++
        failOpen?.let { throw it }
    }
    override suspend fun write(bytes: ByteArray) {
        if (failWritesRemaining > 0) {
            failWritesRemaining--
            throw failWriteError
        }
        failWrite?.let { throw it }
        frames += bytes.copyOf()
        writtenAt += System.nanoTime()
    }
    override suspend fun close() {
        closed++
    }
}

internal suspend fun CoroutineScope.readyBinding(
    device: GaiaGattDeviceFixture,
    current: () -> Boolean = { true },
    publish: (DropControlBinding) -> Unit = {},
): DropControlBinding {
    val binding = DropControlBinding(device, this, DropProfile(DropProfileMatch.NameContains("fixture")),
        { current() }, publish, { _, _ -> }, device.rfcomm)
    binding.startInitialization()
    try {
        withTimeout(3_000) { binding.ready.await() }
    } catch (e: Exception) {
        binding.close()
        throw e
    }
    return binding
}
