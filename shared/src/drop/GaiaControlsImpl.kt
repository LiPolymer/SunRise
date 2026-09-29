package ink.lipoly.app.sunrise.drop

internal class GaiaControlsImpl(private val client: DropControlSession) : GaiaControls {
    private suspend fun request(feature: Int, command: Int, payload: ByteArray = byteArrayOf()): GaiaPacket =
        client.requestGaia(GaiaCommand(feature, command, payload))

    private suspend fun value(feature: Int, command: Int): Int =
        request(feature, command).payload.firstOrNull()?.toInt()?.and(0xff)
            ?: throw DropException.Protocol("Empty GAIA $feature/$command response")

    private suspend fun boolean(feature: Int, command: Int): Boolean = value(feature, command) != 0

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

    override suspend fun isCodecEnabled(codec: AudioCodec): Boolean =
        boolean(GaiaIds.CODEC_TYPE, codecCommands(codec).first)

    override suspend fun setCodecEnabled(codec: AudioCodec, enabled: Boolean): Boolean {
        request(GaiaIds.CODEC_TYPE, codecCommands(codec).second, byteArrayOf(if (enabled) 1 else 0))
        return isCodecEnabled(codec)
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
        request(GaiaIds.MUSIC_PROCESSING, GaiaIds.Eq.SET_PRESET, byteArrayOf(index.toByte()))
        return getEqualizerPreset()
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
