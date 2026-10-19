package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.bluetooth.BtDevice
import ink.lipoly.app.sunrise.drop.DropEvent
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.IBudsController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class HeadsetRouteTest {
    private class ReadyController : IBudsController {
        private val mutableState = MutableStateFlow(DropState(phase = DropPhase.READY))
        private val mutableEvents = MutableSharedFlow<DropEvent>()
        override val state: StateFlow<DropState> = mutableState
        override val events: SharedFlow<DropEvent> = mutableEvents
        override suspend fun close() {
            mutableState.value = DropState()
        }
    }

    @Test fun selectedDeviceRouteSkipsBleSearchAndAssociation() = runBlocking {
        var openedEndpoint: BtDevice? = null
        var openedProfile: BtDevice? = null
        val router = HeadsetRouter {
            HeadsetRoute(searchBleEndpoints = false, rememberAssociation = false) { endpoint, profile ->
                openedEndpoint = endpoint
                openedProfile = profile
                ReadyController()
            }
        }
        val fixture = HeadsetAutoLoopFixture(router)
        try {
            val selected = fixture.add("00:00:00:00:01:00", "Same name")
            val decoy = fixture.add("00:00:00:00:01:01", "Same name")
            fixture.audio(selected)
            val device = fixture.client.discoverConnectedDevices().single()

            fixture.client.connect(device)

            assertEquals(HeadsetPhase.READY, fixture.client.state.value.phase)
            assertSame(device.device, openedEndpoint)
            assertSame(device.device, openedProfile)
            assertEquals(0, selected.connects.value)
            assertEquals(0, decoy.connects.value)
            assertNull(fixture.endpointFor(device.address))
            assertFalse(fixture.client.state.value.device!!.verified)
        } finally {
            fixture.dispose()
        }
    }

    @Test fun selectedDeviceRouteFailureDoesNotRememberAssociation() = runBlocking {
        var opened = 0
        val router = HeadsetRouter {
            HeadsetRoute(searchBleEndpoints = false, rememberAssociation = false) { _, _ ->
                opened++
                throw DropException.UnsupportedDevice()
            }
        }
        val fixture = HeadsetAutoLoopFixture(router)
        try {
            val selected = fixture.add("00:00:00:00:02:00", "Direct")
            fixture.audio(selected)
            val device = fixture.client.discoverConnectedDevices().single()

            assertFailsWith<DropException.UnsupportedDevice> { fixture.client.connect(device) }

            assertEquals(1, opened)
            assertNull(fixture.endpointFor(device.address))
            assertEquals(HeadsetPhase.ERROR, fixture.client.state.value.phase)
        } finally {
            fixture.dispose()
        }
    }
}
