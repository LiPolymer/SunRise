package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.bluetooth.*
import ink.lipoly.app.sunrise.drop.gaia.GaiaGattIds
import ink.lipoly.app.sunrise.drop.gaia.GaiaCodec
import ink.lipoly.app.sunrise.drop.gaia.GaiaCommand
import ink.lipoly.app.sunrise.drop.gaia.GaiaIds
import ink.lipoly.app.sunrise.drop.gaia.GaiaPacket
import ink.lipoly.app.sunrise.drop.gaia.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.gaia.GaiaController
import ink.lipoly.app.sunrise.drop.source.SourceCodec
import ink.lipoly.app.sunrise.drop.source.SourceFrame
import ink.lipoly.app.sunrise.drop.source.SourceGattIds
import ink.lipoly.app.sunrise.drop.source.SourceIds
import ink.lipoly.app.sunrise.drop.gaia.PeqFilter
import ink.lipoly.app.sunrise.drop.moondrop.openMoondrop
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory device storage behind real GATT bytes and the production binding's notification collector. */
internal class GaiaGattDeviceFixture(
    count: Int = 5,
    negotiatedMtu: Int = 247,
    override val id: Long = 1,
    gaia: Boolean = true,
    source: Boolean = false,
    private val readableSourceInfo: Boolean = true,
) : GattSession {
    private class Characteristic(
        override val uuid: String,
        override val properties: Set<GattProperty>,
        override val serviceUuid: String = GaiaGattIds.GAIA_SERVICE,
    ) : GattCharacteristic
    private val commandCharacteristic = Characteristic(GaiaGattIds.GAIA_COMMAND, setOf(GattProperty.WRITE))
    private val responseCharacteristic = Characteristic(GaiaGattIds.GAIA_RESPONSE, setOf(GattProperty.NOTIFY))
    private fun sourceCharacteristic(uuid: String, property: GattProperty) =
        Characteristic(uuid, setOf(property), SourceGattIds.SOURCE_SERVICE)
    private val sourceCommand = sourceCharacteristic(SourceGattIds.SOURCE_COMMAND, GattProperty.WRITE)
    private val sourceResponse = sourceCharacteristic(SourceGattIds.SOURCE_RESPONSE, GattProperty.NOTIFY)
    private val sourceNotification = sourceCharacteristic(SourceGattIds.SOURCE_NOTIFICATION, GattProperty.NOTIFY)
    private val sourceCapability = sourceCharacteristic(SourceGattIds.SOURCE_CAPABILITY, GattProperty.READ)
    private val sourceInfo = sourceCharacteristic(SourceGattIds.SOURCE_INFO, GattProperty.READ)
    override val services = buildList {
        if (gaia) add(GattService(GaiaGattIds.GAIA_SERVICE, listOf(commandCharacteristic, responseCharacteristic)))
        if (source) add(GattService(SourceGattIds.SOURCE_SERVICE,
            listOf(sourceCommand, sourceResponse, sourceNotification, sourceCapability) +
                if (readableSourceInfo) listOf(sourceInfo) else emptyList()))
    }
    override val mtu = MutableStateFlow(negotiatedMtu)
    override val events = MutableSharedFlow<GattEvent>()
    private var closed = false
    private var notifications = false
    var present = true
    var closeCount = 0
        private set
    var connectCount = 0
        private set
    var disconnectCount = 0
        private set
    private val sourceNotifications = mutableSetOf<GattCharacteristic>()
    var sourceId = 2
    var sourceVolume = 37
    val sourceWrites = mutableListOf<ByteArray>()
    val sourceCommands = mutableListOf<SourceFrame>()
    var beforeSourceReply: suspend (SourceFrame, ByteArray) -> ByteArray? = { _, payload -> payload }
    val firmwareBytes = byteArrayOf(1, 2, 0x34, 0x12, 3, 4, 5, 0, 0, 0, 0, 0)
    /** 经典 RFCOMM 通道；非空时结构化 EQ 写入走它，测试不模拟 RFCOMM 组帧。 */
    var rfcomm: RfcommStream? = null
    var presets = listOf(0, 2, 63)
    var currentPreset = 2
    var totalGainRaw = -17
    var bands = List(count) { GaiaPeqBand(it, 1000 + it * 100, 0, 4096, PeqFilter.PEAKING) }
    /** 组件编号/电量字节对；默认三组件与实机 mock 一致，可修改以验证合并语义。 */
    var batteryLevels = byteArrayOf(1, 82, 2, 76, 3, 64)
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
    private var connectedSession: GattSession = this
    private val gattState = MutableStateFlow(GattState(GattPhase.CONNECTED, this))

    /** Simulate the same transport controller publishing a replacement connection epoch. */
    fun replaceConnection(session: GattSession) {
        connectedSession = session
        gattState.value = GattState(GattPhase.CONNECTED, session)
    }

    override val device: BtDevice = object : BtDevice {
        override val address = "00:00:00:00:00:01"
        override val rfcomm: RfcommStream? get() = this@GaiaGattDeviceFixture.rfcomm
        override val info = MutableStateFlow(BtDeviceInfo(name = "Bluetrum fixture"))
        override val gatt: GattController = object : GattController {
            override val state = gattState
            override suspend fun connect(): GattSession {
                if (connectedSession === this@GaiaGattDeviceFixture) check(!closed)
                connectCount++
                return connectedSession
            }
            override suspend fun disconnect() {
                disconnectCount++
                connectedSession.close()
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

    override suspend fun read(characteristic: GattCharacteristic): ByteArray {
        if (closed) throw BtException.Disconnected()
        return when {
            characteristic === sourceInfo && readableSourceInfo -> firmwareBytes.copyOf()
            characteristic === sourceCapability -> byteArrayOf(1, 1, sourceId.toByte(), 0, sourceId.toByte(), 0)
            else -> throw BtException.UnsupportedOperation("Fixture characteristic is not readable")
        }
    }

    override suspend fun setNotifications(characteristic: GattCharacteristic, enabled: Boolean) {
        when (characteristic) {
            responseCharacteristic -> notifications = enabled
            sourceResponse, sourceNotification ->
                if (enabled) sourceNotifications.add(characteristic) else sourceNotifications.remove(characteristic)
            else -> throw BtException.InvalidGattHandle()
        }
    }
    override suspend fun requestMtu(value: Int): Int = mtu.value // Device may negotiate less than requested.
    override fun close() {
        if (!closed) {
            closed = true
            closeCount++
            if (gattState.value.session === this) gattState.value = GattState()
        }
    }

    override suspend fun write(characteristic: GattCharacteristic, value: ByteArray) {
        if (closed) throw BtException.Disconnected()
        if (characteristic === sourceCommand) {
            writeSource(value)
            return
        }
        if (characteristic !== commandCharacteristic) throw BtException.InvalidGattHandle()
        if (value.size > mtu.value - 3) throw BtException.PacketTooLarge()
        val packet = GaiaCodec.decode(value) ?: error("Invalid test command")
        check(packet.type == GaiaCodec.COMMAND)
        writes += value.copyOf()
        commands += packet
        beforeWrite(packet)
        val payload: ByteArray? = when (packet.feature) {
            GaiaIds.BASIC -> byteArrayOf(0, GaiaIds.MUSIC_PROCESSING.toByte(), 1, GaiaIds.CODEC_TYPE.toByte(), 1, GaiaIds.ANC_V2.toByte(), 1)
            GaiaIds.BATTERY -> batteryCommand(packet)
            GaiaIds.CODEC_TYPE -> codecCommand(packet)
            GaiaIds.MUSIC_PROCESSING -> eqCommand(packet)
            GaiaIds.ANC_V2 -> byteArrayOf(0)
            GaiaIds.AUDIO_CURATION, GaiaIds.ANC_V1 ->
                throw BtException.UnsupportedOperation("Fixture only provides ANC V2")
            else -> error("Unexpected feature ${packet.feature}")
        }
        if (payload != null) beforeReply(packet, payload)?.let { emitResponse(packet, it) }
    }

    private fun batteryCommand(packet: GaiaPacket): ByteArray {
        if (packet.command != GaiaIds.Battery.LEVELS) error("Unexpected battery command ${packet.command}")
        return batteryLevels.copyOf()
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

    private suspend fun writeSource(value: ByteArray) {
        require(value.size >= 6 && value.size <= mtu.value - 3)
        require((value[0].toInt() and 0xff) == 0xa5 && value[1].toInt() == 1 &&
            value[2].toInt() == SourceCodec.COMMAND)
        val length = value[5].toInt() and 0xff
        require(length <= 14 && value.size == 6 + length)
        val frame = SourceFrame(SourceCodec.COMMAND, value[3].toInt() and 0xff,
            value[4].toInt() and 0xff, value.copyOfRange(6, value.size))
        sourceWrites += value.copyOf()
        sourceCommands += frame
        val payload = when (frame.commandId) {
            SourceIds.GET_FW_VERSION -> byteArrayOf(0) + firmwareBytes
            SourceIds.GET_AUDIO_SOURCE -> sourceStatusBytes()
            SourceIds.SET_AUDIO_SOURCE -> {
                sourceId = frame.payload[0].toInt() and 0xff
                sourceStatusBytes()
            }
            SourceIds.GET_VOLUME -> sourceVolumeBytes()
            SourceIds.SET_VOLUME -> {
                sourceVolume = frame.payload[1].toInt() and 0xff
                sourceVolumeBytes()
            }
            SourceIds.PING -> byteArrayOf(0)
            else -> error("Unexpected Source command ${frame.commandId}")
        }
        beforeSourceReply(frame, payload)?.let { reply ->
            if (sourceResponse in sourceNotifications)
                events.emit(GattEvent.ValueChanged(sourceResponse,
                    sourceFrame(SourceCodec.RESPONSE, frame.commandId, frame.sequence, reply)))
        }
    }

    private fun sourceStatusBytes() = byteArrayOf(0, sourceId.toByte(), sourceId.toByte(), 0, 1)
    private fun sourceVolumeBytes() = byteArrayOf(0, sourceVolume.toByte(), 0, 100, 1, 0)

    suspend fun emitSourceStatus() {
        if (sourceNotification in sourceNotifications)
            events.emit(GattEvent.ValueChanged(sourceNotification,
                sourceFrame(SourceCodec.NOTIFICATION, 129, 0, sourceStatusBytes())))
    }

    /** Construct device frames independently of the production request encoder. */
    private fun sourceFrame(type: Int, command: Int, sequence: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xa5.toByte(), 1, type.toByte(), command.toByte(), sequence.toByte(), payload.size.toByte()) + payload

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
) : RfcommStream {
    val frames = mutableListOf<ByteArray>()
    val writtenAt = mutableListOf<Long>()
    var opened = 0
    var closed = 0
    override suspend fun open(serviceUuid: String, inputPolicy: RfcommInputPolicy) {
        require(serviceUuid.equals("00001101-0000-1000-8000-00805f9b34fb", ignoreCase = true))
        require(inputPolicy == RfcommInputPolicy.DRAIN_BEFORE_WRITE)
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
    override suspend fun read(): ByteArray =
        throw BtException.UnsupportedOperation("Fixture RFCOMM has no inbound data")
}

/** 借用 fixture 会话建立 READY 控制器；调用方负责在结束时 close()。 */
internal suspend fun readyController(
    device: GaiaGattDeviceFixture,
    current: () -> Boolean = { true },
): GaiaController = openMoondrop(
    session = device,
    profile = DropProfile(DropProfileMatch.NameContains("fixture")),
    classicEq = device.rfcomm,
    current = current,
) as GaiaController
