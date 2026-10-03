package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class GaiaCodecStateTest {
    @Test fun independentCodecOptionsAreVerifiedWithoutSetAck() = runBlocking {
        val device = GaiaGattDeviceFixture()
        val binding = readyBinding(device)
        try {
            assertTrue(binding.state.value.codecStates.isEmpty())
            assertFalse(binding.gaia.isCodecEnabled(AudioCodec.LDAC))
            assertEquals(false, binding.state.value.codecStates[AudioCodec.LDAC])
            assertTrue(binding.gaia.setCodecEnabled(AudioCodec.LC3, true))
            assertTrue(binding.gaia.setCodecEnabled(AudioCodec.LHDC, true))
            assertEquals(mapOf(AudioCodec.LC3 to true, AudioCodec.LDAC to false, AudioCodec.LHDC to true), binding.state.value.codecStates)
            assertEquals(device.codecStorage, binding.state.value.codecStates)
            assertTrue(binding.gaia.setCodecEnabled(AudioCodec.LDAC, true))
            assertEquals(listOf(2, 3, 1, 6, 5, 4, 2), device.commands.filter { it.feature == 16 }.map { it.command })
        } finally { binding.close() }
    }

    @Test fun mismatchKeepsActualFalseNotUnknownAndDoesNotEraseOtherCodecs() = runBlocking {
        val device = GaiaGattDeviceFixture()
        val binding = readyBinding(device)
        try {
            binding.gaia.setCodecEnabled(AudioCodec.LC3, true)
            device.acceptCodecWrites = false
            val error = assertFailsWith<DropException.CodecStateMismatch> {
                binding.gaia.setCodecEnabled(AudioCodec.LDAC, true)
            }
            assertEquals(AudioCodec.LDAC, error.codec)
            assertTrue(error.requested)
            assertFalse(error.observed)
            assertEquals(false, binding.state.value.codecStates[AudioCodec.LDAC])
            assertEquals(true, binding.state.value.codecStates[AudioCodec.LC3])
        } finally { binding.close() }
    }

    @Test fun malformedGetterClearsOnlyAffectedKey() = runBlocking {
        val device = GaiaGattDeviceFixture()
        val binding = readyBinding(device)
        try {
            binding.gaia.setCodecEnabled(AudioCodec.LC3, true)
            binding.gaia.isCodecEnabled(AudioCodec.LDAC)
            for (payload in listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(255.toByte()))) {
                device.beforeReply = { packet, actual -> if (packet.feature == 16 && packet.command == 2) payload else actual }
                assertFailsWith<DropException.Protocol> { binding.gaia.isCodecEnabled(AudioCodec.LDAC) }
                assertFalse(AudioCodec.LDAC in binding.state.value.codecStates)
                assertEquals(true, binding.state.value.codecStates[AudioCodec.LC3])
            }
        } finally { binding.close() }
    }

    @Test fun sentWriteThenGetTimeoutIsUnverifiedAndUnknown() = runBlocking {
        val device = GaiaGattDeviceFixture()
        val binding = readyBinding(device)
        try {
            binding.gaia.setCodecEnabled(AudioCodec.LC3, true)
            binding.gaia.isCodecEnabled(AudioCodec.LDAC)
            device.beforeReply = { packet, payload -> if (packet.feature == 16 && packet.command == 2) null else payload }
            val error = assertFailsWith<DropException.Unverified> {
                withTimeout(8_000) { binding.gaia.setCodecEnabled(AudioCodec.LDAC, true) }
            }
            assertIs<DropException.Timeout>(error.cause)
            assertEquals(true, device.codecStorage[AudioCodec.LDAC])
            assertFalse(AudioCodec.LDAC in binding.state.value.codecStates)
            assertEquals(true, binding.state.value.codecStates[AudioCodec.LC3])
            device.beforeReply = { _, payload -> payload }
            assertTrue(binding.gaia.isCodecEnabled(AudioCodec.LDAC)) // Failed pending was cleared.
        } finally { binding.close() }
    }

    @Test fun callerCancellationAndBindingCloseAreNotUnverified() = runBlocking {
        for (disconnect in listOf(false, true)) {
            val device = GaiaGattDeviceFixture()
            val binding = readyBinding(device)
            try {
                val received = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                device.beforeReply = { packet, payload ->
                    if (packet.feature == 16 && packet.command == 2) {
                        received.complete(Unit)
                        release.await()
                    }
                    payload
                }
                supervisorScope {
                    val call = async { runCatching { binding.gaia.setCodecEnabled(AudioCodec.LDAC, true) } }
                    withTimeout(2_000) { received.await() }
                    assertEquals(true, device.codecStorage[AudioCodec.LDAC])
                    if (disconnect) {
                        binding.close()
                        assertIs<DropException.Disconnected>(call.await().exceptionOrNull())
                    } else {
                        call.cancel()
                        assertFailsWith<CancellationException> { call.await() }
                        assertFalse(AudioCodec.LDAC in binding.state.value.codecStates)
                        device.beforeReply = { _, payload -> payload }
                        release.complete(Unit)
                        assertTrue(binding.gaia.isCodecEnabled(AudioCodec.LDAC))
                    }
                }
            } finally { binding.close() }
        }
    }
}
