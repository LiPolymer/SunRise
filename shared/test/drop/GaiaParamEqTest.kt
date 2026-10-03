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
        assertContentEquals(byteArrayOf(0, 0x1d, 0x0a, 6, 0, 0, 0, 0, 3, 0xe8.toByte(), 0x10, 0, 0, 0, 0x78), pdu)
        assertEquals(listOf(band), GaiaBluetrumPeqCodec.decode(payload, 0..0).bands)
        val negative = GaiaBluetrumPeqCodec.encode(listOf(band.copy(gainRaw = -120)), 0)
        assertEquals(0xff, negative[9].toInt() and 0xff)
        assertEquals(0x88, negative[10].toInt() and 0xff)
        val exact = GaiaBluetrumPeqCodec.encode(listOf(band.copy(gainRaw = 119, qRaw = 4097)), -32768)
        val decoded = GaiaBluetrumPeqCodec.decode(exact, 0..0)
        assertContentEquals(exact, GaiaBluetrumPeqCodec.encode(decoded.bands, decoded.totalGainRaw))
    }

    @Test fun officialFiveBandPayloadUsesPeakingWithoutChangingRawParameters() {
        val payload = "00 04 FF 58 00 15 11 99 00 FF 76 00 8C 0B 33 00 00 78 06 40 21 99 00 00 A2 0C 1C 34 CC 00 FF 9A 17 D4 4B 33 00 FF AC"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val decoded = GaiaBluetrumPeqCodec.decode(payload, 0..4)
        assertEquals(-168, decoded.totalGainRaw)
        assertEquals(listOf(
            GaiaPeqBand(0, 21, -138, 4505, PeqFilter.PEAKING),
            GaiaPeqBand(1, 140, 120, 2867, PeqFilter.PEAKING),
            GaiaPeqBand(2, 1600, 162, 8601, PeqFilter.PEAKING),
            GaiaPeqBand(3, 3100, -102, 13516, PeqFilter.PEAKING),
            GaiaPeqBand(4, 6100, -84, 19251, PeqFilter.PEAKING),
        ), decoded.bands)
        assertContentEquals(payload, GaiaBluetrumPeqCodec.encode(decoded.bands, decoded.totalGainRaw))
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
        assertFailsWith<DropException.UnsupportedCapability> { GaiaBluetrumPeqCodec.decode(good.copyOf().also { it[8] = 29 }, 0..0) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.decode(good.copyOf().also { it[6] = 0; it[7] = 0 }, 0..0) }
        assertFailsWith<DropException.Protocol> { GaiaBluetrumPeqCodec.batchSize(14) }
        assertEquals(1, GaiaBluetrumPeqCodec.batchSize(20))
        assertEquals(7, GaiaBluetrumPeqCodec.batchSize(244))
    }

    @Test fun completeDeviceReadThenSendOnlyActivationAndMtuChunking() = runBlocking {
        for ((count, mtu, expected) in listOf(Triple(5, 247, listOf(0..4)), Triple(10, 247, listOf(0..6, 7..9)), Triple(5, 23, (0..4).map { it..it }))) {
            val device = GaiaGattDeviceFixture(count, mtu)
            val binding = readyBinding(device)
            try {
                val loaded = binding.gaia.getParamEq()
                assertEquals(2, loaded.currentPreset)
                assertEquals(loaded, binding.state.value.paramEq)
                assertTrue(writes(device).isEmpty())
                device.beforeReply = { packet, payload ->
                    if (packet.feature == GaiaIds.MUSIC_PROCESSING && packet.command == GaiaIds.Eq.GET_USER_CONFIG)
                        byteArrayOf(7) // Automatic configuration readback would fail.
                    else payload
                }
                val target = loaded.bands.map { it.copy(gainRaw = 119, qRaw = 4097) }
                binding.gaia.setParamEq(target)
                assertEquals(target, device.bands)
                assertEquals(63, device.currentPreset)
                assertNull(binding.state.value.paramEq)
                assertEquals(expected, ranges(writes(device)))
                assertTrue(device.writes.all { it.size <= mtu - 3 })
                assertTrue(device.commands.none { it.feature == 5 && it.command in 7..8 })
                device.beforeReply = { _, payload -> payload }
                binding.gaia.setParamEq(loaded.bands)
                assertEquals(loaded.bands, device.bands)
                assertNull(binding.state.value.paramEq)
                val actual = binding.gaia.getParamEq()
                assertEquals(loaded.bands, actual.bands)
                assertEquals(63, actual.currentPreset)
                assertEquals(actual, binding.state.value.paramEq)
                binding.gaia.setEqualizerPreset(2)
                assertNull(binding.state.value.paramEq)
                val afterPreset = device.commands.size
                assertFailsWith<DropException.NotReady> { binding.gaia.setParamEq(target) }
                assertEquals(afterPreset, device.commands.size)
                binding.gaia.getParamEq()
                binding.gaia.setParamEq(target)
                assertNull(binding.state.value.paramEq)
            } finally { binding.close() }
        }
    }

    @Test fun latestSnapshotHeadroomIsGlobalAcrossBatchesAndFlatteningClearsIt() = runBlocking {
        for ((mtu, expectedRanges) in listOf(
            23 to (0..9).map { it..it },
            247 to listOf(0..6, 7..9),
        )) {
            val device = GaiaGattDeviceFixture(10, mtu)
            val binding = readyBinding(device)
            try {
                val loaded = binding.gaia.getParamEq()
                assertEquals(-17, loaded.totalGainRaw) // A stale device header must not drive new uploads.
                val flat = loaded.bands.map { it.copy(frequencyHz = 2345 + it.index, qRaw = 4097) }
                val singleBoost = flat.toMutableList().also {
                    it[7] = it[7].copy(frequencyHz = 1000, gainRaw = 360, qRaw = 4096)
                }
                val overlappingBoosts = singleBoost.toMutableList().also {
                    it[0] = it[0].copy(frequencyHz = 500, gainRaw = 360, qRaw = 4096)
                }
                device.beforeReply = { packet, payload ->
                    if (packet.feature == GaiaIds.MUSIC_PROCESSING && packet.command == GaiaIds.Eq.GET_USER_CONFIG)
                        byteArrayOf(7) // Sending must not add an automatic readback.
                    else payload
                }
                for ((snapshot, expectedHeader) in listOf(
                    singleBoost to -366,
                    overlappingBoosts to -491,
                    flat to 0,
                )) {
                    val before = device.commands.size
                    binding.gaia.setParamEq(snapshot)
                    val commands = device.commands.drop(before)
                    // Selection precedes every band write and no preset query is interleaved.
                    assertEquals(
                        listOf(GaiaIds.Eq.GET_BAND_COUNT, GaiaIds.Eq.SET_PRESET) +
                            List(expectedRanges.size) { GaiaIds.Eq.SET_USER_CONFIG },
                        commands.map { it.command },
                    )
                    val batches = commands.filter { it.command == GaiaIds.Eq.SET_USER_CONFIG }
                    assertEquals(expectedRanges, ranges(batches))
                    val decoded = batches.zip(expectedRanges).map { (packet, range) ->
                        GaiaBluetrumPeqCodec.decode(packet.payload, range)
                    }
                    assertEquals(List(expectedRanges.size) { expectedHeader }, decoded.map { it.totalGainRaw })
                    assertEquals(snapshot, decoded.flatMap { it.bands })
                    assertEquals(snapshot, device.bands)
                    assertEquals(expectedHeader, device.totalGainRaw)
                    assertNull(binding.state.value.paramEq)
                }
                assertTrue(device.writes.all { it.size <= mtu - 3 })
            } finally { binding.close() }
        }
    }

    @Test fun unrepresentableGlobalHeadroomRejectsBeforeAnyCommand() = runBlocking {
        val device = GaiaGattDeviceFixture(10, 23)
        val binding = readyBinding(device)
        try {
            val loaded = binding.gaia.getParamEq()
            val target = loaded.bands.map {
                it.copy(frequencyHz = 1000, gainRaw = 3600, qRaw = 4096)
            }
            val before = device.commands.size
            assertFailsWith<IllegalArgumentException> { binding.gaia.setParamEq(target) }
            assertEquals(before, device.commands.size)
            assertEquals(loaded.bands, device.bands)
            assertEquals(-17, device.totalGainRaw)
            assertEquals(loaded, binding.state.value.paramEq)
        } finally { binding.close() }
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
            for (filter in PeqFilter.entries.filter { it != PeqFilter.PEAKING }) {
                val unsupported = loaded.bands.toMutableList().also { it[9] = it[9].copy(filter = filter) }
                assertFailsWith<IllegalArgumentException> { binding.gaia.setParamEq(unsupported) }
                assertEquals(before, device.commands.size)
            }
            device.bands = device.bands.dropLast(1)
            assertFailsWith<DropException.Protocol> { binding.gaia.setParamEq(loaded.bands) }
            assertTrue(writes(device).isEmpty())
            assertNull(binding.state.value.paramEq)
        } finally { binding.close() }
    }

    @Test fun clampingAndPresetRefusalRemainUnverifiedUntilManualRead() = runBlocking {
        for (refusePreset in listOf(false, true)) {
            val device = GaiaGattDeviceFixture(10)
            val binding = readyBinding(device)
            try {
                val loaded = binding.gaia.getParamEq()
                if (refusePreset) device.acceptPresetWrites = false
                else device.clampBand = { if (it.index == 9) it.copy(gainRaw = 60) else it }
                val target = loaded.bands.map { it.copy(gainRaw = 180) }
                binding.gaia.setParamEq(target)
                assertNull(binding.state.value.paramEq)
                assertEquals(listOf(0..6, 7..9), ranges(writes(device)))
                val actual = binding.gaia.getParamEq()
                assertEquals(device.bands, actual.bands)
                assertEquals(actual, binding.state.value.paramEq)
                if (refusePreset) {
                    assertEquals(2, actual.currentPreset)
                    assertEquals(target, actual.bands)
                } else {
                    assertEquals(63, actual.currentPreset)
                    assertEquals(60, actual.bands.last().gainRaw)
                    assertNotEquals(target, actual.bands)
                }
            } finally { binding.close() }
        }
    }

    @Test fun originalTransportFailureStopsRemainingWritesAndInvalidatesMetadata() = runBlocking {
        for (failActivation in listOf(false, true)) {
            val device = GaiaGattDeviceFixture(17)
            val binding = readyBinding(device)
            try {
                val loaded = binding.gaia.getParamEq()
                val failure = DropException.Protocol("Injected transport failure")
                val before = device.commands.size
                device.beforeWrite = { packet ->
                    if (packet.feature == GaiaIds.MUSIC_PROCESSING &&
                        if (failActivation) packet.command == GaiaIds.Eq.SET_PRESET
                        else packet.command == GaiaIds.Eq.SET_USER_CONFIG && packet.payload[0].toInt() == 7)
                        throw failure
                }
                val error = assertFailsWith<DropException.Protocol> {
                    binding.gaia.setParamEq(loaded.bands.map { it.copy(gainRaw = 180) })
                }
                assertSame(failure, error)
                assertNull(binding.state.value.paramEq)
                if (failActivation) {
                    // Selection is the first write, so a failed activation leaves every band untouched.
                    assertTrue(writes(device).isEmpty())
                    assertEquals(0, device.bands[0].gainRaw)
                    assertEquals(2, device.currentPreset)
                    assertEquals(
                        listOf(GaiaIds.Eq.GET_BAND_COUNT, GaiaIds.Eq.SET_PRESET),
                        device.commands.drop(before).map { it.command },
                    )
                } else {
                    // The failing batch is recorded by the transport before it throws.
                    assertEquals(listOf(0..6, 7..13), ranges(writes(device)))
                    assertEquals(63, device.currentPreset)
                    assertEquals(180, device.bands[6].gainRaw)
                    assertEquals(0, device.bands[7].gainRaw)
                    assertEquals(
                        listOf(
                            GaiaIds.Eq.GET_BAND_COUNT, GaiaIds.Eq.SET_PRESET,
                            GaiaIds.Eq.SET_USER_CONFIG, GaiaIds.Eq.SET_USER_CONFIG,
                        ),
                        device.commands.drop(before).map { it.command },
                    )
                }
                device.beforeWrite = {}
                val afterFailure = device.commands.size
                assertFailsWith<DropException.NotReady> { binding.gaia.setParamEq(loaded.bands) }
                assertEquals(afterFailure, device.commands.size)
                binding.gaia.getParamEq()
                binding.gaia.setParamEq(loaded.bands)
                assertEquals(loaded.bands, device.bands)
                assertEquals(63, device.currentPreset)
                assertNull(binding.state.value.paramEq)
            } finally { binding.close() }
        }
    }

    @Test fun unsupportedAndMalformedConfigurationsNeverWrite() = runBlocking {
        for (mode in 0..6) {
            val device = GaiaGattDeviceFixture()
            when (mode) {
                0 -> device.present = false
                1 -> device.presets = listOf(0, 2)
                2 -> device.presets = listOf(63, 63)
                3 -> device.bands = emptyList()
                4 -> device.beforeReply = { packet, payload -> if (packet.feature == 5 && packet.command == 5) payload.copyOf(8) else payload }
                5 -> device.beforeReply = { packet, payload -> if (packet.feature == 5 && packet.command == 5) payload.copyOf().also { it[8] = 99 } else payload }
                6 -> device.beforeReply = { packet, payload -> if (packet.feature == 5 && packet.command == 5) payload.copyOf().also { it[8] = 29 } else payload }
            }
            val binding = readyBinding(device)
            try {
                assertFailsWith<DropException> { binding.gaia.getParamEq() }
                val afterRead = device.commands.size
                assertFailsWith<DropException.NotReady> { binding.gaia.setParamEq(device.bands.ifEmpty {
                    listOf(GaiaPeqBand(0, 1000, 0, 4096, PeqFilter.PEAKING))
                }) }
                assertEquals(afterRead, device.commands.size)
                assertNull(binding.state.value.paramEq)
                assertTrue(writes(device).isEmpty())
                assertTrue(device.commands.none { it.feature == 5 && it.command in setOf(3, 6, 7, 8) })
            } finally { binding.close() }
        }
    }

    @Test fun unchangedBandsArePreservedAndMixedHeadersReject() = runBlocking {
        val device = GaiaGattDeviceFixture(10)
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
            val afterRead = device.commands.size
            assertFailsWith<DropException.NotReady> { binding.gaia.setParamEq(target) }
            assertEquals(afterRead, device.commands.size)
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
            device.beforeWrite = { packet ->
                if (packet.feature == 5 && packet.command == 6 && packet.payload[0].toInt() == 7) {
                    waiting.complete(Unit)
                    release.await()
                }
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
                val afterCancellation = device.commands.size
                assertFailsWith<DropException.NotReady> { binding.gaia.setParamEq(loaded.bands) }
                assertEquals(afterCancellation, device.commands.size)
                device.beforeWrite = {}
                binding.gaia.getParamEq()
                binding.gaia.setParamEq(loaded.bands)
                assertEquals(loaded.bands, device.bands)
            }
        } finally { binding.close() }
    }

    @Test fun oldEpochSendCannotContinueOrClearNewBindingMetadata() = runBlocking {
        val oldDevice = GaiaGattDeviceFixture(10)
        var oldCurrent = true
        val old = readyBinding(oldDevice, { oldCurrent })
        val newDevice = GaiaGattDeviceFixture(id = 2)
        val fresh = readyBinding(newDevice)
        try {
            val loaded = old.gaia.getParamEq()
            val freshActual = fresh.gaia.getParamEq()
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            oldDevice.beforeWrite = { packet ->
                if (packet.feature == GaiaIds.MUSIC_PROCESSING && packet.command == GaiaIds.Eq.SET_USER_CONFIG &&
                    packet.payload[0].toInt() == 7) {
                    waiting.complete(Unit)
                    release.await()
                }
            }
            supervisorScope {
                val inflight = async {
                    runCatching { old.gaia.setParamEq(loaded.bands.map { it.copy(gainRaw = 180) }) }
                }
                withTimeout(2_000) { waiting.await() }
                oldCurrent = false
                old.close()
                assertIs<DropException.Disconnected>(withTimeout(2_000) { inflight.await() }.exceptionOrNull())
                release.complete(Unit)
            }
            assertEquals(180, oldDevice.bands[0].gainRaw)
            assertEquals(0, oldDevice.bands[9].gainRaw)
            // Selection is sent first, so it already applied before the connection dropped.
            assertEquals(63, oldDevice.currentPreset)
            assertEquals(freshActual, fresh.state.value.paramEq)
            assertTrue(writes(newDevice).isEmpty())
            assertFailsWith<DropException.Disconnected> { old.gaia.setParamEq(loaded.bands) }
            fresh.gaia.setParamEq(freshActual.bands.map { it.copy(gainRaw = 60) })
            assertTrue(newDevice.bands.all { it.gainRaw == 60 })
            assertNull(fresh.state.value.paramEq)
        } finally { old.close(); fresh.close() }
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
