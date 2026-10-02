package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GaiaControlsImplTest {
    @Test fun ancWriteUsesReadbackAndUpdatesState() = runBlocking {
        val session = FakeSession()
        val controls = GaiaControlsImpl(session)

        assertEquals(AncMode.NOISE_CANCELLING, controls.setAncMode(AncMode.NOISE_CANCELLING))
        assertEquals(AncMode.NOISE_CANCELLING, session.state.value.ancMode)
        assertEquals(GaiaIds.ANC_V1, session.sent.single().feature)
    }

    @Test fun unreadableAncClearsPreviouslyKnownMode() = runBlocking {
        val session = FakeSession().apply { readFails = true }
        val controls = GaiaControlsImpl(session)

        assertFailsWith<DropException.Unverified> {
            controls.setAncMode(AncMode.NOISE_CANCELLING)
        }
        assertNull(session.state.value.ancMode)
    }

    private class FakeSession : DropControlSession {
        private val mutableState = MutableStateFlow(DropState(phase = DropPhase.READY, ancMode = AncMode.OFF))
        override val state = mutableState.asStateFlow()
        val sent = mutableListOf<GaiaCommand>()
        var readFails = false
        private var rawMode = 0

        override fun profile(): DropProfile = DropProfiles.resolve(DropOptions(), null, null)
        override fun ancPath(): AncPath = AncPath.V1
        override fun mutate(block: (DropState) -> DropState) { mutableState.update(block) }

        override suspend fun requestGaia(command: GaiaCommand): GaiaPacket {
            if (readFails) throw DropException.Timeout("ANC GET")
            return GaiaPacket(command.vendor, command.feature, GaiaCodec.RESPONSE, command.command,
                byteArrayOf(rawMode.toByte()))
        }

        override suspend fun sendGaia(command: GaiaCommand) {
            sent += command
            rawMode = command.payload.first().toInt() and 0xff
        }

        override suspend fun requestSource(commandId: Int, payload: ByteArray): ByteArray = error("Not used")
        override suspend fun readSourceCapability(): ByteArray = error("Not used")
        override suspend fun readSourceInfo(): ByteArray = error("Not used")
    }
}
