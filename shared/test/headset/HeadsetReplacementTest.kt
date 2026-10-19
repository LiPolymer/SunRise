package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.IBudsController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class HeadsetReplacementTest {
    private class BlockingCloseController(
        private val closeStarted: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>,
    ) : IBudsController {
        private val mutableState = MutableStateFlow(DropState(phase = DropPhase.READY))
        private val mutableEvents = MutableSharedFlow<DropEvent>()
        override val state: StateFlow<DropState> = mutableState
        override val events: SharedFlow<DropEvent> = mutableEvents

        override suspend fun close() {
            closeStarted.complete(Unit)
            gate.await()
            mutableState.value = DropState()
        }
    }

    @Test fun cancelledReplacementDoesNotLeaveAnUnfinishedEpoch(): Unit = runBlocking {
        val closeStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val router = HeadsetRouter {
            HeadsetRoute(searchBleEndpoints = false, rememberAssociation = false) { _, _ ->
                BlockingCloseController(closeStarted, gate)
            }
        }
        val fixture = HeadsetAutoLoopFixture(router)
        try {
            val first = fixture.add("00:00:00:00:03:00", "First")
            fixture.audio(first)
            fixture.client.connect(fixture.client.discoverConnectedDevices().single())
            assertEquals(HeadsetPhase.READY, fixture.client.state.value.phase)

            val next = HeadsetDevice(fixture.add("00:00:00:00:03:01", "Next"), "Next")
            val replacing = launch(Dispatchers.Default) { fixture.client.connect(next) }
            withTimeout(5.seconds) { closeStarted.await() }

            replacing.cancel()
            gate.complete(Unit)
            withTimeout(5.seconds) { replacing.join() }

            assertEquals(HeadsetPhase.IDLE, fixture.client.state.value.phase)
            withTimeout(5.seconds) { fixture.client.disconnect() }

            val third = HeadsetDevice(fixture.add("00:00:00:00:03:02", "Third"), "Third")
            fixture.client.connect(third)
            assertEquals(HeadsetPhase.READY, fixture.client.state.value.phase)
        } finally {
            gate.complete(Unit)
            fixture.client.close()
            fixture.close()
        }
    }
}
