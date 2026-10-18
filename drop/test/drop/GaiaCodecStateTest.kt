package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class GaiaCodecStateTest {
    @Test fun independentCodecOptionsAreVerifiedWithoutSetAck() = runBlocking {
        val device = GaiaGattDeviceFixture()
        val controller = readyController(device)
        try {
            assertTrue(controller.state.value.codecStates.isEmpty())
            assertFalse(controller.isCodecEnabled(AudioCodec.LDAC))
            assertEquals(false, controller.state.value.codecStates[AudioCodec.LDAC])
            assertTrue(controller.setCodecEnabled(AudioCodec.LC3, true))
            assertTrue(controller.setCodecEnabled(AudioCodec.LHDC, true))
            assertEquals(mapOf(AudioCodec.LC3 to true, AudioCodec.LDAC to false, AudioCodec.LHDC to true), controller.state.value.codecStates)
            assertEquals(device.codecStorage, controller.state.value.codecStates)
            assertTrue(controller.setCodecEnabled(AudioCodec.LDAC, true))
            assertEquals(listOf(2, 3, 1, 6, 5, 4, 2), device.commands.filter { it.feature == 16 }.map { it.command })
        } finally { controller.close() }
    }

    @Test fun mismatchKeepsActualFalseNotUnknownAndDoesNotEraseOtherCodecs() = runBlocking {
        val device = GaiaGattDeviceFixture()
        val controller = readyController(device)
        try {
            controller.setCodecEnabled(AudioCodec.LC3, true)
            device.acceptCodecWrites = false
            val error = assertFailsWith<DropException.CodecStateMismatch> {
                controller.setCodecEnabled(AudioCodec.LDAC, true)
            }
            assertEquals(AudioCodec.LDAC, error.codec)
            assertTrue(error.requested)
            assertFalse(error.observed)
            assertEquals(false, controller.state.value.codecStates[AudioCodec.LDAC])
            assertEquals(true, controller.state.value.codecStates[AudioCodec.LC3])
        } finally { controller.close() }
    }

    @Test fun malformedGetterClearsOnlyAffectedKey() = runBlocking {
        val device = GaiaGattDeviceFixture()
        val controller = readyController(device)
        try {
            controller.setCodecEnabled(AudioCodec.LC3, true)
            controller.isCodecEnabled(AudioCodec.LDAC)
            for (payload in listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(255.toByte()))) {
                device.beforeReply = { packet, actual -> if (packet.feature == 16 && packet.command == 2) payload else actual }
                assertFailsWith<DropException.Protocol> { controller.isCodecEnabled(AudioCodec.LDAC) }
                assertFalse(AudioCodec.LDAC in controller.state.value.codecStates)
                assertEquals(true, controller.state.value.codecStates[AudioCodec.LC3])
            }
        } finally { controller.close() }
    }

    @Test fun sentWriteThenGetTimeoutIsUnverifiedAndUnknown() = runBlocking {
        val device = GaiaGattDeviceFixture()
        val controller = readyController(device)
        try {
            controller.setCodecEnabled(AudioCodec.LC3, true)
            controller.isCodecEnabled(AudioCodec.LDAC)
            device.beforeReply = { packet, payload -> if (packet.feature == 16 && packet.command == 2) null else payload }
            val error = assertFailsWith<DropException.Unverified> {
                withTimeout(8_000.milliseconds) { controller.setCodecEnabled(AudioCodec.LDAC, true) }
            }
            assertIs<DropException.Timeout>(error.cause)
            assertEquals(true, device.codecStorage[AudioCodec.LDAC])
            assertFalse(AudioCodec.LDAC in controller.state.value.codecStates)
            assertEquals(true, controller.state.value.codecStates[AudioCodec.LC3])
            device.beforeReply = { _, payload -> payload }
            assertTrue(controller.isCodecEnabled(AudioCodec.LDAC)) // Failed pending was cleared.
        } finally { controller.close() }
    }

    @Test fun callerCancellationAndBindingCloseAreNotUnverified() = runBlocking {
        for (disconnect in listOf(false, true)) {
            val device = GaiaGattDeviceFixture()
            val controller = readyController(device)
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
                    val call = async { runCatching { controller.setCodecEnabled(AudioCodec.LDAC, true) } }
                    withTimeout(2_000.milliseconds) { received.await() }
                    assertEquals(true, device.codecStorage[AudioCodec.LDAC])
                    if (disconnect) {
                        controller.close()
                        assertIs<DropException.Disconnected>(call.await().exceptionOrNull())
                    } else {
                        call.cancel()
                        assertFailsWith<CancellationException> { call.await() }
                        assertFalse(AudioCodec.LDAC in controller.state.value.codecStates)
                        device.beforeReply = { _, payload -> payload }
                        release.complete(Unit)
                        assertTrue(controller.isCodecEnabled(AudioCodec.LDAC))
                    }
                }
            } finally { controller.close() }
        }
    }
}
