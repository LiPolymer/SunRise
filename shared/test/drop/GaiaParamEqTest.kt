package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class GaiaParamEqTest {
    private fun writes(device: GaiaGattDeviceFixture) = device.commands.filter {
        it.feature == GaiaIds.MUSIC_PROCESSING && it.command == GaiaIds.Eq.SET_USER_CONFIG
    }
    private fun ranges(packets: List<GaiaPacket>) = packets.map {
        (it.payload[0].toInt() and 0xff)..(it.payload[1].toInt() and 0xff)
    }

    @Test fun knownVectorAndRawRoundTrip() {
        val band = GaiaPeqBand(0, 1000, 120, 4096, PeqFilter.PEAKING)
        val payload = GaiaBluetrumPeqCodec.encode(listOf(band), 0)
        val pdu = GaiaCodec.encode(GaiaCommand(5, 6, payload))
        assertContentEquals(byteArrayOf(0, 0x1d, 0x0a, 6, 0, 0, 0, 0, 3, 0xe8.toByte(), 0x10, 0, 0x0d, 0, 0x78), pdu)
        assertEquals(listOf(band), GaiaBluetrumPeqCodec.decode(payload, 0..0).bands)
        val negative = GaiaBluetrumPeqCodec.encode(listOf(band.copy(gainRaw = -120)), 0)
        assertEquals(0xff, negative[9].toInt() and 0xff)
        assertEquals(0x88, negative[10].toInt() and 0xff)
        val exact = GaiaBluetrumPeqCodec.encode(listOf(band.copy(gainRaw = 119, qRaw = 4097)), -32768)
        val decoded = GaiaBluetrumPeqCodec.decode(exact, 0..0)
        assertContentEquals(exact, GaiaBluetrumPeqCodec.encode(decoded.bands, decoded.totalGainRaw))
        val unused = GaiaPeqBand(0, 0, -11, 0, PeqFilter.BYPASS)
        assertEquals(listOf(unused), GaiaBluetrumPeqCodec.decode(GaiaBluetrumPeqCodec.encode(listOf(unused), 32767), 0..0).bands)
    }

    @Test fun strictParsingAndNumericUnits() {
        assertEquals(409, GaiaBluetrumPeqCodec.qRaw(0.1))
        assertEquals(119, GaiaBluetrumPeqCodec.gainRaw(119 / 60.0))
        assertEquals(1001, GaiaBluetrumPeqCodec.frequencyHz(1000.5))
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { GaiaBluetrumPeqCodec.frequencyHz(value) }
            assertFailsWith<IllegalArgumentException> { GaiaBluetrumPeqCodec.gainRaw(value) }
            assertFailsWith<IllegalArgumentException> { GaiaBluetrumPeqCodec.qRaw(value) }
        }
        for (value in listOf(0.0, 0.00001, 16.0)) assertFailsWith<IllegalArgumentException> { GaiaBluetrumPeqCodec.qRaw(value) }
        assertFailsWith<IllegalArgumentException> { GaiaBluetrumPeqCodec.gainRaw(32768 / 60.0) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.presets(byteArrayOf(2, 63)) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.presets(byteArrayOf(2, 63, 63)) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.boolean(byteArrayOf(2), "EQ") }
        val good = GaiaBluetrumPeqCodec.encode(listOf(GaiaPeqBand(0, 1000, 0, 4096, PeqFilter.PEAKING)), 0)
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.decode(good.copyOf(10), 0..0) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.decode(good, 1..1) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.decode(good.copyOf().also { it[1] = 255.toByte() }, 0..0) }
        assertFailsWith<DropException.UnsupportedCapability> { GaiaBluetrumPeqCodec.decode(good.copyOf().also { it[8] = 99 }, 0..0) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.decode(good.copyOf().also { it[6] = 0; it[7] = 0 }, 0..0) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.batchSize(14) }
        assertEquals(1, GaiaBluetrumPeqCodec.batchSize(20))
        assertEquals(7, GaiaBluetrumPeqCodec.batchSize(244))
    }

    @Test fun completeDeviceReadThenActivationAndMtuChunking() = runBlocking {
        for ((count, mtu, expected) in listOf(Triple(5, 247, listOf(0..4)), Triple(10, 247, listOf(0..6, 7..9)), Triple(5, 23, (0..4).map { it..it }))) {
            val device = GaiaGattDeviceFixture(count, mtu)
            val binding = readyBinding(device)
            try {
                val loaded = binding.gaia.getParamEq()
                assertEquals(2, loaded.currentPreset)
                assertTrue(writes(device).isEmpty())
                val target = loaded.bands.map { it.copy(gainRaw = 119, qRaw = 4097) }
                val actual = binding.gaia.setParamEq(target)
                assertEquals(63, actual.currentPreset)
                assertEquals(target, device.bands)
                assertEquals(target, actual.bands)
                assertEquals(-17, actual.totalGainRaw)
                assertEquals(actual, binding.state.value.paramEq)
                assertEquals(expected, ranges(writes(device)))
                device.commands.forEachIndexed { index, packet ->
                    if (packet.feature == 5 && packet.command == 6) {
                        val next = device.commands[index + 1]
                        assertEquals(5, next.feature)
                        assertEquals(5, next.command)
                        assertContentEquals(packet.payload.copyOfRange(0, 2), next.payload)
                    }
                }
                assertTrue(device.writes.all { it.size <= mtu - 3 })
                assertTrue(device.commands.none { it.feature == 5 && it.command in 7..8 })
                assertEquals(1, device.commands.count { it.feature == 5 && it.command == 3 })
                binding.gaia.setEqualizerPreset(2)
                assertNull(binding.state.value.paramEq)
            } finally { binding.close() }
        }
    }

    @Test fun invalidLastBandAndChangedCountRejectBeforeAnyWrite() = runBlocking {
        val device = GaiaGattDeviceFixture(10)
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            val invalid = loaded.bands.toMutableList().also { it[9] = it[9].copy(qRaw = 0) }
            val before = device.commands.size
            assertFailsWith<IllegalArgumentException> { binding.gaia.setParamEq(invalid) }
            assertEquals(before, device.commands.size)
            assertTrue(writes(device).isEmpty())
            assertFailsWith<IllegalArgumentException> { binding.gaia.setParamEq(loaded.bands.reversed()) }
            assertFailsWith<IllegalArgumentException> { binding.gaia.setParamEq(emptyList()) }
            device.bands = device.bands.dropLast(1)
            assertFailsWith<DropException.Protocol> { binding.gaia.setParamEq(loaded.bands) }
            assertTrue(writes(device).isEmpty())
            assertNull(binding.state.value.paramEq)
        } finally { binding.close() }
    }

    @Test fun rejectionPublishesActualAndStopsRemainingBatches() = runBlocking {
        val device = GaiaGattDeviceFixture(10)
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            device.clampBand = { if (it.index == 6) it.copy(gainRaw = 60) else it }
            val error = assertFailsWith<DropException.ParamEqMismatch> {
                binding.gaia.setParamEq(loaded.bands.map { it.copy(gainRaw = 180) })
            }
            assertEquals(device.bands, error.observed.bands)
            assertEquals(error.observed, binding.state.value.paramEq)
            assertEquals(60, error.observed.bands[6].gainRaw)
            assertEquals(0, error.observed.bands[9].gainRaw)
            assertEquals(listOf(0..6), ranges(writes(device)))
            assertEquals(2, device.currentPreset)
        } finally { binding.close() }
    }

    @Test fun lastBandClampingAndPresetRefusalAreNotApplied() = runBlocking {
        for (refusePreset in listOf(false, true)) {
            val device = GaiaGattDeviceFixture(10)
            val binding = readyBinding(device)
            try {
                val loaded = binding.gaia.getParamEq()
                if (refusePreset) device.acceptPresetWrites = false
                else device.clampBand = { if (it.index == 9) it.copy(gainRaw = 60) else it }
                val error = assertFailsWith<DropException.ParamEqMismatch> {
                    binding.gaia.setParamEq(loaded.bands.map { it.copy(gainRaw = 180) })
                }
                assertEquals(device.bands, error.observed.bands)
                assertEquals(2, error.observed.currentPreset)
                assertEquals(error.observed, binding.state.value.paramEq)
                assertEquals(listOf(0..6, 7..9), ranges(writes(device)))
            } finally { binding.close() }
        }
    }

    @Test fun secondBatchMalformedReadbackIsUnverifiedAndClearsConfirmed() = runBlocking {
        val device = GaiaGattDeviceFixture(10)
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            device.beforeReply = { packet, payload ->
                if (packet.feature == 5 && packet.command == 5 && packet.payload[0].toInt() == 7) byteArrayOf(7) else payload
            }
            val error = assertFailsWith<DropException.Unverified> {
                binding.gaia.setParamEq(loaded.bands.map { it.copy(gainRaw = 180) })
            }
            assertIs<DropException.Protocol>(error.cause)
            assertNull(binding.state.value.paramEq)
            assertEquals(2, device.currentPreset)
            assertEquals(180, device.bands.last().gainRaw) // Device accepted data before the broken GET.
        } finally { binding.close() }
    }

    @Test fun mismatchWithoutCompleteActualReadbackIsUnknown() = runBlocking {
        val device = GaiaGattDeviceFixture(10)
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            device.clampBand = { it.copy(gainRaw = 60) }
            device.beforeReply = { packet, payload ->
                if (packet.feature == 5 && packet.command == 5 && packet.payload[0].toInt() == 7)
                    payload.copyOf(5) else payload
            }
            val error = assertFailsWith<DropException.Unverified> {
                binding.gaia.setParamEq(loaded.bands.map { it.copy(gainRaw = 180) })
            }
            assertIs<DropException.Protocol>(error.cause)
            assertNull(binding.state.value.paramEq)
            assertEquals(listOf(0..6), ranges(writes(device)))
        } finally { binding.close() }
    }

    @Test fun unsupportedAndMalformedConfigurationsNeverWrite() = runBlocking {
        for (mode in 0..5) {
            val device = GaiaGattDeviceFixture()
            when (mode) {
                0 -> device.present = false
                1 -> device.presets = listOf(0, 2)
                2 -> device.presets = listOf(63, 63)
                3 -> device.bands = emptyList()
                4 -> device.beforeReply = { packet, payload -> if (packet.feature == 5 && packet.command == 5) payload.copyOf(8) else payload }
                5 -> device.beforeReply = { packet, payload -> if (packet.feature == 5 && packet.command == 5) payload.copyOf().also { it[8] = 99 } else payload }
            }
            val binding = readyBinding(device)
            try {
                assertFailsWith<DropException> { binding.gaia.getParamEq() }
                assertNull(binding.state.value.paramEq)
                assertTrue(writes(device).isEmpty())
                assertTrue(device.commands.none { it.feature == 5 && it.command in setOf(3, 6, 7, 8) })
            } finally { binding.close() }
        }
    }

    @Test fun bypassUnusedFieldsArePreservedAndMixedHeadersReject() = runBlocking {
        val device = GaiaGattDeviceFixture(10)
        device.bands = device.bands.map { if (it.index == 0) it.copy(frequencyHz = 0, qRaw = 0, filter = PeqFilter.BYPASS) else it }
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            val target = loaded.bands.map { if (it.index == 9) it.copy(gainRaw = 60) else it }
            binding.gaia.setParamEq(target)
            assertEquals(loaded.bands[0], device.bands[0])
            device.beforeReply = { packet, payload ->
                if (packet.feature == 5 && packet.command == 5 && packet.payload[0].toInt() == 7)
                    payload.copyOf().also { it[3] = 0 } else payload
            }
            assertFailsWith<DropException.Protocol> { binding.gaia.getParamEq() }
            assertNull(binding.state.value.paramEq)
        } finally { binding.close() }
    }

    @Test fun wrongRangeAndSetAckCannotCompleteRangeRead() = runBlocking {
        val device = GaiaGattDeviceFixture(10)
        val binding = readyBinding(device)
        try {
            device.beforeReply = { packet, payload ->
                if (packet.feature == 5 && packet.command == 5 && packet.payload[0].toInt() == 7) {
                    device.emitResponse(packet, device.configurationBytes(0..6))
                    device.emitResponse(packet, byteArrayOf(), command = 6)
                }
                payload
            }
            assertEquals(device.bands, binding.gaia.getParamEq().bands)
        } finally { binding.close() }
    }

    @Test fun entirePeqWriteOwnsMutexAndCancellationDoesNotRollback() = runBlocking {
        val device = GaiaGattDeviceFixture(10)
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            device.beforeReply = { packet, payload ->
                if (packet.feature == 5 && packet.command == 5) {
                    waiting.complete(Unit)
                    release.await()
                }
                payload
            }
            supervisorScope {
                val peq = async { binding.gaia.setParamEq(loaded.bands.map { it.copy(gainRaw = 180) }) }
                withTimeout(2_000) { waiting.await() }
                val codec = async(start = CoroutineStart.UNDISPATCHED) { binding.gaia.isCodecEnabled(AudioCodec.LDAC) }
                assertTrue(device.commands.none { it.feature == GaiaIds.CODEC_TYPE })
                peq.cancel()
                assertFailsWith<kotlinx.coroutines.CancellationException> { peq.await() }
                assertNull(binding.state.value.paramEq)
                assertEquals(180, device.bands[0].gainRaw)
                assertEquals(0, device.bands[9].gainRaw)
                release.complete(Unit)
                assertFalse(withTimeout(2_000) { codec.await() })
            }
        } finally { binding.close() }
    }

    @Test fun oldEpochReadbackCannotUpdateNewBinding() = runBlocking {
        val oldDevice = GaiaGattDeviceFixture()
        var oldCurrent = true
        val old = readyBinding(oldDevice, { oldCurrent })
        val newDevice = GaiaGattDeviceFixture(id = 2)
        val fresh = readyBinding(newDevice)
        try {
            val loaded = old.gaia.getParamEq()
            val waiting = CompletableDeferred<GaiaPacket>()
            val release = CompletableDeferred<Unit>()
            oldDevice.beforeReply = { packet, payload ->
                if (packet.feature == 5 && packet.command == 5) {
                    waiting.complete(packet)
                    release.await()
                }
                payload
            }
            supervisorScope {
                val inflight = async { runCatching { old.gaia.getParamEq() } }
                val request = withTimeout(2_000) { waiting.await() }
                oldCurrent = false
                old.close()
                assertIs<DropException.Disconnected>(inflight.await().exceptionOrNull())
                oldDevice.emitResponse(request, oldDevice.configurationBytes(0..4))
                release.complete(Unit)
            }
            assertFailsWith<DropException.Disconnected> { old.gaia.setParamEq(loaded.bands) }
            assertNull(fresh.state.value.paramEq)
            assertTrue(writes(newDevice).isEmpty())
            assertEquals(newDevice.bands, fresh.gaia.getParamEq().bands)
        } finally { old.close(); fresh.close() }
    }
}
