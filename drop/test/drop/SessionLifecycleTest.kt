package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SessionLifecycleTest {
    @Test fun closeInvalidatesBeforeReleasingExactlyOnce(): Unit = runBlocking {
        var releases = 0
        lateinit var context: ControllerContext
        val lifecycle = SessionLifecycle(
            release = {
                releases++
                assertFalse(context.control.isOpen)
            },
        )
        context = ControllerContext(setOf(DropProtocol.GAIA_BLE), lifecycle) { true }
        val controller = object : SessionController(context) {}

        context.control.mutate { it.copy(phase = DropPhase.READY) }
        assertEquals(DropPhase.READY, controller.state.value.phase)

        controller.close()
        assertEquals(1, releases)
        assertEquals(DropPhase.IDLE, controller.state.value.phase)

        controller.close()
        assertEquals(1, releases)
        assertFailsWith<DropException.Disconnected> { context.control.checkActive() }
        assertFailsWith<DropException.Disconnected> { context.control.transaction { } }
    }

    @Test fun lostTransportClosesAndReleasesOnce(): Unit = runBlocking {
        val lost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val released = CompletableDeferred<Unit>()
        var releases = 0
        val lifecycle = SessionLifecycle(
            lost = lost,
            release = {
                releases++
                released.complete(Unit)
            },
        )
        val context = ControllerContext(setOf(DropProtocol.GAIA_BLE), lifecycle) { true }
        val controller = object : SessionController(context) {}

        controller.observeLoss()
        lost.emit(Unit)
        withTimeout(5.seconds) { released.await() }

        assertTrue(context.closed.value)
        assertEquals(1, releases)
        controller.close()
        assertEquals(1, releases)
    }
}
