package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.blueConnector.BtException
import ink.lipoly.app.sunrise.blueConnector.BtRfcomm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Single binding controls: codec verification and Bluetrum PEQ reads/writes each own one transaction.
 * PEQ writes complete transport only; confirmed snapshots come exclusively from explicit device reads.
 * ANC retains its bounded GET verification; other existing operations are unchanged.
 */
internal class GaiaControlsImpl(
    private val client: DropControlSession,
    /** 经典 RFCOMM 通道；提供时结构化 EQ 写入走该通道，BLE 只用于读取与控件。 */
    private val classicEq: BtRfcomm? = null,
) : GaiaControls {
    /** Transaction-confined sizing only, never a verified current device configuration. */
    private var paramEqWriteBandCount = 0

    private companion object {
        /**
         * Official community apply selects USER63 and calls the bulk write from a 100 ms timer,
         * without awaiting the selection reply. Measured on device: 105 ms between the two frames.
         */
        const val PRESET_ACTIVATION_DELAY_MILLIS = 100L

        /**
         * 经典通道没有 ATT MTU，可用写入预算是单字节长度字段上限加固定头部；换算后仍为 7 段，
         * 与官版 `sendBluetrumSetGainsChunk` 的块大小一致。
         */
        const val CLASSIC_WRITE_SIZE = GaiaRfcomm.MAX_PAYLOAD + 8

        /** 单帧写入尝试次数：首次之外每次都在重连后重试。实机首帧偶发失败一次以上，故留三次。 */
        const val CLASSIC_WRITE_ATTEMPTS = 3

        /** 重连前的等待：对端释放刚断开的 SPP 通道需要时间，立即重连容易再次失败。 */
        const val CLASSIC_RETRY_DELAY_MILLIS = 150L
    }

    private fun forgetParamEq() {
        paramEqWriteBandCount = 0
        clearState { it.copy(paramEq = null) }
    }

    private suspend fun request(feature: Int, command: Int, payload: ByteArray = byteArrayOf()): GaiaPacket =
        client.requestGaia(GaiaCommand(feature, command, payload))

    private suspend fun value(feature: Int, command: Int): Int =
        request(feature, command).payload.firstOrNull()?.toInt()?.and(0xff)
            ?: throw DropException.Protocol("Empty GAIA $feature/$command response")

    private suspend fun boolean(feature: Int, command: Int): Boolean = value(feature, command) != 0

    /** 严格要求非空偶数负载；只替换出现的组件，未知编号忽略，原始电量字节不裁剪。 */
    override suspend fun getBattery(): EarbudBattery {
        val answer = request(GaiaIds.BATTERY, GaiaIds.Battery.LEVELS)
        if (answer.payload.size < 2 || answer.payload.size % 2 != 0)
            throw DropException.Protocol("Invalid GAIA battery response")
        var left: Int? = client.state.value.battery.left
        var right: Int? = client.state.value.battery.right
        var case: Int? = client.state.value.battery.case
        for (index in 0 until answer.payload.size - 1 step 2) {
            val level = answer.payload[index + 1].toInt() and 0xff
            when (answer.payload[index].toInt() and 0xff) {
                1 -> left = level
                2 -> right = level
                3 -> case = level
            }
        }
        return EarbudBattery(left, right, case).also { battery ->
            client.mutate { it.copy(battery = battery) }
        }
    }

    /** 绑定选中的路径固定于 epoch，不在操作中改变协议路径或自动重新探测。 */
    private fun ancCommand(get: Boolean): Pair<Int, Int> = when (client.ancPath()) {
        AncPath.V1 -> GaiaIds.ANC_V1 to (if (get) GaiaIds.Anc.V1_GET else GaiaIds.Anc.V1_SET)
        AncPath.AUDIO_CURATION -> GaiaIds.AUDIO_CURATION to
            (if (get) GaiaIds.AudioCuration.GET_MODE else GaiaIds.AudioCuration.SET_MODE)
        AncPath.V2 -> GaiaIds.ANC_V2 to (if (get) GaiaIds.Anc.GET_MODE else GaiaIds.Anc.SET_MODE)
        AncPath.UNKNOWN -> throw DropException.UnsupportedCapability("ANC")
    }

    override suspend fun getAncMode(): AncMode {
        val (feature, command) = ancCommand(true)
        val raw = value(feature, command)
        val mode = DropProfiles.fromDevice(client.ancPath(), raw, client.profile())
            ?: throw DropException.Protocol("Unknown ANC mode $raw")
        client.mutate { it.copy(ancMode = mode) }
        return mode
    }

    /** 写 map 与读 map 独立；模式缺失在写前拒绝，读回失败后避免保留虚假的目标值。 */
    override suspend fun setAncMode(mode: AncMode): AncMode {
        val deviceValue = DropProfiles.toDevice(client.ancPath(), mode, client.profile())
            ?: throw DropException.UnsupportedCapability("ANC mode $mode")
        val (feature, command) = ancCommand(false)
        return try {
            writeAndVerifyAncMode(
                requested = mode,
                write = { client.sendGaia(GaiaCommand(feature, command, byteArrayOf(deviceValue.toByte()))) },
                read = { getAncMode() },
            )
        } catch (e: DropException.Unverified) {
            client.mutate { it.copy(ancMode = null) }
            throw e
        }
    }

    override suspend fun getGain(): GainLevel {
        val raw = value(GaiaIds.DAC_GAIN, GaiaIds.Device.GET)
        val gain = DropProfiles.gainFromDevice(raw, client.profile())
            ?: throw DropException.Protocol("Unknown DAC gain $raw")
        client.mutate { it.copy(gain = gain) }
        return gain
    }

    override suspend fun setGain(level: GainLevel): GainLevel {
        request(GaiaIds.DAC_GAIN, GaiaIds.Device.SET,
            byteArrayOf(DropProfiles.gainToDevice(level, client.profile()).toByte()))
        return getGain()
    }

    override suspend fun isLedOn(): Boolean = boolean(GaiaIds.LED, GaiaIds.Device.GET).also { on ->
        client.mutate { it.copy(ledOn = on) }
    }

    override suspend fun setLedOn(on: Boolean): Boolean {
        request(GaiaIds.LED, GaiaIds.Device.SET, byteArrayOf(if (on) 1 else 0))
        return isLedOn()
    }

    override suspend fun isSpatialOn(): Boolean = boolean(GaiaIds.SPATIAL_AUDIO, GaiaIds.Spatial.GET).also { on ->
        client.mutate { it.copy(spatialOn = on) }
    }

    override suspend fun setSpatialOn(on: Boolean): Boolean {
        request(GaiaIds.SPATIAL_AUDIO, GaiaIds.Spatial.SET, byteArrayOf(if (on) 1 else 0))
        return isSpatialOn()
    }

    override suspend fun getHeadTracking(): HeadTrackingMode {
        val raw = value(GaiaIds.SPATIAL_AUDIO, GaiaIds.Spatial.GET_TRACKING)
        val mode = HeadTrackingMode.entries.getOrNull(raw)
            ?: throw DropException.Protocol("Unknown head tracking mode $raw")
        client.mutate { it.copy(headTracking = mode) }
        return mode
    }

    override suspend fun setHeadTracking(mode: HeadTrackingMode): HeadTrackingMode {
        request(GaiaIds.SPATIAL_AUDIO, GaiaIds.Spatial.SET_TRACKING, byteArrayOf(mode.ordinal.toByte()))
        return getHeadTracking()
    }

    private fun codecCommands(codec: AudioCodec): Pair<Int, Int> = when (codec) {
        AudioCodec.LC3 -> GaiaIds.Codec.GET_LC3 to GaiaIds.Codec.SET_LC3
        AudioCodec.LDAC -> GaiaIds.Codec.GET_LDAC to GaiaIds.Codec.SET_LDAC
        AudioCodec.LHDC -> GaiaIds.Codec.GET_LHDC to GaiaIds.Codec.SET_LHDC
    }

    private fun forgetCodec(codec: AudioCodec) = clearState { it.copy(codecStates = it.codecStates - codec) }

    private fun clearState(block: (DropState) -> DropState) {
        try {
            client.mutate(block)
        } catch (_: DropException.Disconnected) {
            // Retired bindings must not publish over the next device's state.
        }
    }

    private suspend fun GaiaTransaction.readCodec(codec: AudioCodec): Boolean {
        val enabled = GaiaBluetrumPeqCodec.boolean(
            request(GaiaCommand(GaiaIds.CODEC_TYPE, codecCommands(codec).first)).payload, "$codec")
        client.mutate { it.copy(codecStates = it.codecStates + (codec to enabled)) }
        return enabled
    }

    override suspend fun isCodecEnabled(codec: AudioCodec): Boolean = try {
        client.withGaiaTransaction { readCodec(codec) }
    } catch (e: Exception) {
        forgetCodec(codec)
        throw e
    }

    override suspend fun setCodecEnabled(codec: AudioCodec, enabled: Boolean): Boolean {
        var sent = false
        return try {
            client.withGaiaTransaction {
                send(GaiaCommand(GaiaIds.CODEC_TYPE, codecCommands(codec).second,
                    byteArrayOf(if (enabled) 1 else 0)))
                sent = true
                val actual = readCodec(codec)
                if (actual != enabled) throw DropException.CodecStateMismatch(codec, enabled, actual)
                actual
            }
        } catch (e: DropException.CodecStateMismatch) {
            throw e
        } catch (e: CancellationException) {
            forgetCodec(codec)
            throw e
        } catch (e: DropException.Disconnected) {
            forgetCodec(codec)
            throw e
        } catch (e: Exception) {
            forgetCodec(codec)
            if (sent) throw DropException.Unverified("$codec option", e)
            throw e
        }
    }

    override suspend fun isDynamicBassOn(): Boolean = boolean(GaiaIds.DYNAMIC_BASS, GaiaIds.Device.GET)
    override suspend fun setDynamicBassOn(on: Boolean): Boolean {
        request(GaiaIds.DYNAMIC_BASS, GaiaIds.Device.SET, byteArrayOf(if (on) 1 else 0))
        return isDynamicBassOn()
    }

    override suspend fun isLeftRightReversed(): Boolean = boolean(GaiaIds.LR_CHANNEL, GaiaIds.Device.GET)
    override suspend fun setLeftRightReversed(reversed: Boolean): Boolean {
        request(GaiaIds.LR_CHANNEL, GaiaIds.Device.SET, byteArrayOf(if (reversed) 1 else 0))
        return isLeftRightReversed()
    }

    override suspend fun getEqualizerPreset(): Int = value(GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.GET_PRESET)
    override suspend fun setEqualizerPreset(index: Int): Int {
        require(index in 0..255)
        return client.withGaiaTransaction {
            forgetParamEq()
            request(GaiaCommand(GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.SET_PRESET, byteArrayOf(index.toByte())))
            GaiaBluetrumPeqCodec.byteValue(
                request(GaiaCommand(GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.GET_PRESET)).payload, "EQ preset")
        }
    }

    private suspend fun GaiaTransaction.eqValue(command: Int): Int =
        GaiaBluetrumPeqCodec.byteValue(
            request(GaiaCommand(GaiaIds.MUSIC_PROCESSING, command)).payload, "EQ $command")

    private suspend fun GaiaTransaction.readRange(range: IntRange): GaiaBluetrumPeqCodec.Configuration =
        GaiaBluetrumPeqCodec.decode(request(GaiaCommand(
            GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.GET_USER_CONFIG,
            byteArrayOf(range.first.toByte(), range.last.toByte())), range).payload, range)

    private suspend fun GaiaTransaction.readParamEq(): GaiaParamEqState {
        val present = GaiaBluetrumPeqCodec.boolean(
            request(GaiaCommand(GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.GET_STATE)).payload, "EQ state")
        if (!present) throw DropException.UnsupportedCapability("GAIA parametric EQ")
        val presets = GaiaBluetrumPeqCodec.presets(
            request(GaiaCommand(GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.GET_PRESETS)).payload)
        if (GaiaBluetrumPeqCodec.USER_PRESET !in presets)
            throw DropException.UnsupportedCapability("Bluetrum USER63 EQ")
        val preset = eqValue(GaiaIds.Eq.GET_PRESET)
        val count = eqValue(GaiaIds.Eq.GET_BAND_COUNT)
        if (count !in 1..255) throw DropException.Protocol("Invalid Bluetrum band count $count")
        val bands = ArrayList<GaiaPeqBand>(count)
        var totalGain: Int? = null
        var start = 0
        while (start < count) {
            val end = start + minOf(GaiaBluetrumPeqCodec.batchSize(maxWriteSize), count - start) - 1
            val configuration = readRange(start..end)
            if (totalGain != null && configuration.totalGainRaw != totalGain)
                throw DropException.Protocol("Bluetrum header changed between ranges")
            totalGain = configuration.totalGainRaw
            bands.addAll(configuration.bands)
            start = end + 1
        }
        return GaiaParamEqState(presets, preset, totalGain!!, bands).also { actual ->
            client.mutate { it.copy(paramEq = actual) }
            paramEqWriteBandCount = actual.bandCount
        }
    }

    override suspend fun getParamEq(): GaiaParamEqState = client.withGaiaTransaction {
        try {
            readParamEq()
        } catch (e: Exception) {
            // Invalidate before releasing the mutex, not after a later successful transaction.
            forgetParamEq()
            throw e
        }
    }

    override suspend fun setParamEq(bands: List<GaiaPeqBand>) {
        // Snapshot and validate the entire input before issuing any command, including the last band.
        val target = bands.toList()
        val totalGainRaw = PeqHeadroom.preGainRaw(target)
        client.withGaiaTransaction {
            try {
                val expectedCount = paramEqWriteBandCount
                if (expectedCount == 0) throw DropException.NotReady()
                val count = eqValue(GaiaIds.Eq.GET_BAND_COUNT)
                if (count != expectedCount || target.size != count)
                    throw DropException.Protocol("EQ band count changed; reload before editing")
                client.mutate { it.copy(paramEq = null) }
                val channel = classicEq
                if (channel == null) {
                    emitParamEq(GaiaBluetrumPeqCodec.batchSize(maxWriteSize), count, target, totalGainRaw) {
                        send(it)
                    }
                } else {
                    // 官版抓包中 EQ 配置走经典 RFCOMM；同样的字节经 BLE GATT 写入会把设备置于
                    // 单侧无声状态，因此有经典通道时不再回退到 BLE 写入。
                    channel.open()
                    emitParamEq(GaiaBluetrumPeqCodec.batchSize(CLASSIC_WRITE_SIZE), count, target, totalGainRaw) {
                        channel.writeFrame(it)
                    }
                }
            } catch (e: Exception) {
                forgetParamEq()
                throw e
            }
        }
    }

    /**
     * 产生一次结构化写入的命令序列：先无条件选择 USER63，等 [PRESET_ACTIVATION_DELAY_MILLIS]，
     * 再按 [blockBands] 分块发送全部参数。发送通道由 [emit] 提供（BLE 事务或经典 RFCOMM）；
     * 本方法只负责顺序与分块，不等待任何回执，也不证明 DSP 已应用。
     */
    private suspend fun emitParamEq(
        blockBands: Int,
        count: Int,
        target: List<GaiaPeqBand>,
        totalGainRaw: Int,
        emit: suspend (GaiaCommand) -> Unit,
    ) {
        emit(GaiaCommand(GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.SET_PRESET,
            byteArrayOf(GaiaBluetrumPeqCodec.USER_PRESET.toByte())))
        delay(PRESET_ACTIVATION_DELAY_MILLIS)
        var start = 0
        while (start < count) {
            val end = start + minOf(blockBands, count - start) - 1
            val payload = GaiaBluetrumPeqCodec.encode(target.subList(start, end + 1), totalGainRaw)
            emit(GaiaCommand(GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.SET_USER_CONFIG, payload))
            start = end + 1
        }
    }

    /**
     * 经典通道的单帧写入：组帧后写出。传输失败时关闭通道、等待后重连，重试同一帧，最多
     * [CLASSIC_WRITE_ATTEMPTS] 次尝试——只有整帧写出或整帧未写出，不重发已成功的帧，也不回退 BLE；
     * 权限/未打开等非瞬时错误不做重试。全部尝试失败后按本绑定统一表映射抛出。
     * 平台写入成功只表示字节已交给 RFCOMM，不表示设备应用了配置。
     */
    private suspend fun BtRfcomm.writeFrame(command: GaiaCommand) {
        val frame = GaiaRfcomm.frame(command)
        var last: BtException? = null
        for (attempt in 0 until CLASSIC_WRITE_ATTEMPTS) {
            try {
                if (attempt > 0) {
                    close()
                    delay(CLASSIC_RETRY_DELAY_MILLIS)
                    open()
                }
                write(frame)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: BtException) {
                last = e
                if (e !is BtException.Transport) break
            }
        }
        throw client.normalizeTransport(last ?: BtException.Transport("RFCOMM frame was not written"))
    }

    override suspend fun getGestureConfiguration(gesture: Int, context: Int): GaiaPacket {
        require(gesture in 0..255 && context in 0..255)
        return request(GaiaIds.GESTURE_CONFIGURATION, GaiaIds.Gesture.GET_CONFIG,
            byteArrayOf(gesture.toByte(), context.toByte()))
    }

    override suspend fun resetGestureConfiguration(): GaiaPacket =
        request(GaiaIds.GESTURE_CONFIGURATION, GaiaIds.Gesture.RESET)

    override suspend fun getBasicInfo(command: Int): GaiaPacket {
        require(command in setOf(GaiaIds.Basic.VERSION, GaiaIds.Basic.FEATURES, GaiaIds.Basic.SERIAL,
            GaiaIds.Basic.VARIANT, GaiaIds.Basic.APP_VERSION, GaiaIds.Basic.COLOR,
            GaiaIds.Basic.LANGUAGE, GaiaIds.Basic.LEFT_SN, GaiaIds.Basic.RIGHT_SN,
            GaiaIds.Basic.TWS_STATUS))
        return request(GaiaIds.BASIC, command)
    }

    override suspend fun getAudioCuration(command: Int): GaiaPacket {
        require(command in setOf(0, 2, 3, 5, 7, 8, 10, 12, 13, 15, 17, 18, 20, 22, 23,
            25, 26, 28, 30, 31, 33, 34, 35, 37, 38, 39, 41))
        return request(GaiaIds.AUDIO_CURATION, command)
    }

    override suspend fun setAudioCuration(command: Int, payload: ByteArray): GaiaPacket {
        require(command in setOf(1, 4, 6, 9, 11, 14, 16, 19, 21, 24, 27, 29, 32, 36, 40, 42) &&
            payload.isNotEmpty())
        return request(GaiaIds.AUDIO_CURATION, command, payload.copyOf())
    }

    override suspend fun powerOff() {
        client.sendGaia(GaiaCommand(GaiaIds.POWER_CONTROL, GaiaIds.Device.POWER_OFF))
    }

    override suspend fun requestRaw(command: GaiaCommand): GaiaPacket = client.requestGaia(command)
    override suspend fun sendRaw(command: GaiaCommand) = client.sendGaia(command)
}
