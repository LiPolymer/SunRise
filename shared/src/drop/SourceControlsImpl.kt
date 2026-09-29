package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal class SourceControlsImpl(private val client: DropControlSession) : SourceControls {
    private suspend fun request(command: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        client.requestSource(command, payload).also {
            val status = SourceCodec.status(it)
            if (status != 0 && !(command == SourceIds.SET_AUDIO_SOURCE && status in setOf(5, 9)))
                throw DropException.Rejected(status, "9ECA command $command")
        }

    override suspend fun getAudioSource(): SourceStatus =
        SourceCodec.sourceStatus(request(SourceIds.GET_AUDIO_SOURCE)).also { status ->
            client.mutate { it.copy(sourceStatus = status) }
        }

    override suspend fun setAudioSource(sourceId: Int, options: SourceSwitchOptions): SourceStatus {
        require(sourceId in 0..255 && options.fadeSeconds in 0..60)
        val first = SourceCodec.sourceStatus(request(SourceIds.SET_AUDIO_SOURCE,
            byteArrayOf(sourceId.toByte(), options.flags().toByte(), options.fadeSeconds.toByte())))
        client.mutate { it.copy(sourceStatus = first) }
        if (!options.awaitStable || (first.stableSuccess && first.currentSource == sourceId)) return first
        val final = withTimeoutOrNull((options.fadeSeconds.coerceAtLeast(1) + 3) * 1_000L) {
            var current = first
            while (current.statusCode == 0 || current.statusCode == 5 || current.statusCode == 9) {
                delay(200)
                current = getAudioSource()
                if (current.stableSuccess && current.currentSource == sourceId) return@withTimeoutOrNull current
            }
            current
        } ?: throw DropException.Timeout("9ECA source switch")
        if (final.statusCode != 0 && final.statusCode != 9)
            throw DropException.Rejected(final.statusCode, "9ECA source switch")
        return final
    }

    override suspend fun getCapabilityPage(page: Int): SourceCapabilityPage {
        require(page in 0..15)
        return SourceCodec.capabilityPage(request(SourceIds.GET_CAPABILITY, byteArrayOf(page.toByte())))
    }

    override suspend fun readSourceCapability(): SourceCapability =
        SourceCodec.sourceCapability(client.readSourceCapability())

    override suspend fun getFirmwareInfo(): SourceFirmwareInfo {
        val direct = try { SourceCodec.firmware(client.readSourceInfo()) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        val firmware = direct ?: SourceCodec.firmware(request(SourceIds.GET_FW_VERSION), 1)
        client.mutate { it.copy(capabilities = it.capabilities.copy(sourceFeatures = firmware.features)) }
        return firmware
    }

    override suspend fun getVolume(): SourceVolume =
        SourceCodec.volume(request(SourceIds.GET_VOLUME)).also { volume ->
            client.mutate { it.copy(volume = volume) }
        }

    override suspend fun setVolume(mode: Int, value: Int, flags: Int): SourceVolume {
        require(mode in 0..255 && value in 0..255 && flags in 0..255)
        return SourceCodec.volume(request(SourceIds.SET_VOLUME,
            byteArrayOf(mode.toByte(), value.toByte(), flags.toByte()))).also { volume ->
            client.mutate { it.copy(volume = volume) }
        }
    }

    override suspend fun getPresetEq(): SourcePresetEq =
        SourceCodec.presetEq(request(SourceIds.GET_PRESET_EQ)).also { eq ->
            client.mutate { it.copy(presetEq = eq) }
        }

    override suspend fun setPresetEq(index: Int): SourcePresetEqChange {
        require(index in 0..255)
        return SourceCodec.presetChange(request(SourceIds.SET_PRESET_EQ, byteArrayOf(index.toByte()))).also { change ->
            client.mutate { state ->
                state.copy(presetEq = state.presetEq?.copy(current = change.current))
            }
        }
    }

    override suspend fun getPeqConfig(): SourcePeqConfig =
        SourceCodec.peqConfig(request(SourceIds.GET_PEQ_CONFIG))

    override suspend fun setPeqPreGain(centiDb: Int): SourcePeqPreGain {
        require(centiDb in -12800..12799)
        return SourceCodec.peqPreGain(request(SourceIds.SET_PEQ_PREGAIN,
            byteArrayOf(SourceIds.USER_PEQ_PRESET.toByte()) + SourceCodec.le16(centiDb)))
    }

    override suspend fun getPeqPoint(index: Int): SourcePeqPoint {
        require(index in 0..31)
        return SourceCodec.peqPoint(request(SourceIds.GET_PEQ_POINT, byteArrayOf(index.toByte())))
    }

    override suspend fun setPeqPoint(point: SourcePeqPoint): SourcePeqPoint {
        require(point.index in 0..31 && point.frequencyHz in 20..20_000 &&
            point.gainCentiDb in -32768..32767 && point.qRaw in 1..65535 && point.filterId in 0..7)
        val payload = byteArrayOf(point.index.toByte()) + SourceCodec.le16(point.frequencyHz) +
            SourceCodec.le16(point.gainCentiDb) + SourceCodec.le16(point.qRaw) +
            byteArrayOf(point.filterId.toByte())
        return SourceCodec.peqPoint(request(SourceIds.SET_PEQ_POINT, payload))
    }

    override suspend fun commitPeq(action: Int, revision: Int): SourcePeqCommit {
        require(action in 0..2 && revision in 0..65535)
        return SourceCodec.peqCommit(request(SourceIds.COMMIT_PEQ,
            byteArrayOf(action.toByte()) + SourceCodec.le16(revision)))
    }

    override suspend fun getMicGain(): SourceMicGain =
        SourceCodec.micGain(request(SourceIds.GET_MIC_GAIN)).also { gain ->
            client.mutate { it.copy(micGain = gain) }
        }

    override suspend fun setMicGain(deciDb: Int): SourceMicGain {
        require(deciDb in -1280..1280)
        return SourceCodec.micGain(request(SourceIds.SET_MIC_GAIN, SourceCodec.le16(deciDb))).also { gain ->
            client.mutate { it.copy(micGain = gain) }
        }
    }

    override suspend fun getEarbudColor(): Int = SourceCodec.earbudInfo(request(SourceIds.GET_COLOR))
    override suspend fun getEarbudLanguage(): Int = SourceCodec.earbudInfo(request(SourceIds.GET_LANGUAGE))

    override suspend fun getEarbudSerial(side: EarbudSide): ByteArray {
        val command = if (side == EarbudSide.LEFT) SourceIds.GET_LEFT_SN else SourceIds.GET_RIGHT_SN
        val first = SourceCodec.snChunk(request(command, byteArrayOf(0)), 0)
        val second = SourceCodec.snChunk(request(command, byteArrayOf(10)), 10)
        return first + second
    }

    override suspend fun ping() { request(SourceIds.PING) }
}
