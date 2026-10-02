package ink.lipoly.app.sunrise.headset

import ink.lipoly.app.sunrise.blueConnector.*
import ink.lipoly.app.sunrise.drop.*
import ink.lipoly.app.sunrise.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class HeadsetClientTest {
    @Test fun autoWithoutAudioCandidatesActuallyDiscoversThenReturnsToIdle() = scenario {
        val refresh = manager.holdAudioRefresh()
        client.startAutoConnect()
        refresh.started.await()
        assertEquals(HeadsetPhase.DISCOVERING, client.state.value.phase)
        refresh.release()
        refresh.drained.await()
        val idle = client.state.first { it.phase == HeadsetPhase.IDLE }
        assertNull(idle.device)
        assertNull(idle.error)
        assertFailsWith<DropException.NotReady> { client.gaia }
    }

    @Test fun autoConnectsSingleAudioCandidateAndPollsRealBattery() = scenario {
        val selected = manager.addDevice(A, "Another brand", FakeDeviceConfig(battery = EarbudBattery(61, 62)))
        manager.audioCandidates = listOf(selected)
        client.startAutoConnect()
        val ready = client.state.first {
            it.phase == HeadsetPhase.READY && it.controls.battery == EarbudBattery(61, 62)
        }
        assertEquals(A, ready.device?.address)
        assertEquals("Another brand", ready.device?.name)
        assertEquals(GattPhase.CONNECTED, selected.gatt.state.value.phase)
        assertEquals(EarbudBattery(61, 62), client.gaia.getBattery())
        assertEquals(A, associations.endpoint(A))
    }

    @Test fun multipleAudioCandidatesRequireSelectionWithoutFilteringBrandsOrConnecting() = scenario {
        val first = manager.addDevice(A, "Unrelated headphones")
        val second = manager.addDevice(B, "Different maker")
        associations.remember(B, BLE)
        manager.audioCandidates = listOf(first, second)
        client.startAutoConnect()
        val selection = client.state.first { it.phase == HeadsetPhase.SELECTION_REQUIRED }
        assertNull(selection.device)
        assertEquals(0, first.gatt.connectCalls)
        assertEquals(0, second.gatt.connectCalls)
        assertFailsWith<DropException.NotReady> { client.source }
        val candidates = client.discoverConnectedDevices()
        assertEquals(listOf(A, B), candidates.map { it.address })
        assertEquals(listOf("Unrelated headphones", "Different maker"), candidates.map { it.name })
        assertEquals(listOf(false, true), candidates.map { it.verified })
    }

    @Test fun manualSelectionDisplaysAudioIdentityWhileUsingRememberedBleEndpoint() = scenario {
        val audio = manager.addDevice(A, "Audio identity", FakeDeviceConfig(gaia = false, source = false))
        val endpoint = manager.addDevice(BLE, "BLE endpoint", FakeDeviceConfig(battery = EarbudBattery(71, 72)))
        manager.audioCandidates = listOf(audio)
        associations.remember(A, BLE)
        client.connect(client.discoverConnectedDevices().single())
        val ready = client.state.first {
            it.phase == HeadsetPhase.READY && it.controls.battery == EarbudBattery(71, 72)
        }
        assertEquals(A, ready.device?.address)
        assertEquals("Audio identity", ready.device?.name)
        assertTrue(requireNotNull(ready.device).verified)
        assertEquals(0, audio.gatt.connectCalls)
        assertEquals(GattPhase.DISCONNECTED, audio.gatt.state.value.phase)
        assertEquals(GattPhase.CONNECTED, endpoint.gatt.state.value.phase)
        assertEquals(EarbudBattery(71, 72), client.gaia.getBattery())
        assertEquals(BLE, associations.endpoint(A))
    }

    @Test fun disconnectCancelsAutoAttemptAndRequiresExplicitRestart() = scenario {
        val selected = manager.addDevice(A)
        manager.audioCandidates = listOf(selected)
        val firstAttempt = selected.gatt.holdConnect()
        client.startAutoConnect()
        firstAttempt.started.await()
        client.disconnect()
        firstAttempt.drained.await()
        assertEquals(HeadsetPhase.IDLE, client.state.value.phase)
        assertNull(client.state.value.device)
        assertEquals(GattPhase.DISCONNECTED, selected.gatt.state.value.phase)
        assertFailsWith<DropException.NotReady> { client.gaia }
        manager.publishAudioCandidates()
        // A new explicit start is the next accepted connection, not the cancelled radio attempt.
        client.startAutoConnect()
        client.state.first { it.phase == HeadsetPhase.READY }
        assertEquals(2, selected.gatt.connectCalls)
        assertEquals(1, selected.gatt.sessions.size)
    }

    @Test fun closeReleasesOwnConnectionButKeepsManagerAndIndependentControllerUsable() = scenario {
        val selected = manager.addDevice(A)
        val other = manager.addDevice(B)
        other.gatt.connect()
        val independent = controller(other)
        independent.awaitReady()
        client.connect(A)
        client.close()
        selected.gatt.state.first { it.phase == GattPhase.DISCONNECTED }
        assertFalse(manager.closed)
        assertEquals(GattPhase.CONNECTED, other.gatt.state.value.phase)
        independent.source.ping()
        assertEquals(EarbudBattery(42, 43), independent.gaia.getBattery())
        client.close()
        assertEquals(GattPhase.CONNECTED, other.gatt.state.value.phase)
    }

    @Test fun closeCancelsAnIndependentRadioAttemptEvenBeforeConnectReturns() = scenario {
        val selected = manager.addDevice(A)
        val other = manager.addDevice(B)
        other.gatt.connect()
        val independent = controller(other)
        independent.awaitReady()
        val gate = selected.gatt.holdConnect()
        val connection = scope.async { runCatching { client.connect(A) } }
        gate.started.await()
        client.close()
        gate.drained.await()
        assertTrue(connection.await().isFailure)
        selected.gatt.state.first { it.phase == GattPhase.DISCONNECTED }
        assertFalse(manager.closed)
        assertTrue(selected.gatt.sessions.isEmpty())
        independent.source.ping()
    }

    @Test fun cancellingManualCallerDisconnectsItsRadioAttemptAndAllowsAnotherSelection() = scenario {
        val abandoned = manager.addDevice(A)
        val replacement = manager.addDevice(B, config = FakeDeviceConfig(battery = EarbudBattery(81, 82)))
        val gate = abandoned.gatt.holdConnect()
        val connection = scope.async { client.connect(A) }
        gate.started.await()
        connection.cancelAndJoin()
        gate.drained.await()
        assertTrue(connection.isCancelled)
        abandoned.gatt.state.first { it.phase == GattPhase.DISCONNECTED }
        assertTrue(abandoned.gatt.sessions.isEmpty())
        client.connect(B)
        val ready = client.state.first {
            it.phase == HeadsetPhase.READY && it.controls.battery == EarbudBattery(81, 82)
        }
        assertEquals(B, ready.device?.address)
        assertEquals(GattPhase.CONNECTED, replacement.gatt.state.value.phase)
        assertEquals(1, abandoned.gatt.connectCalls)
    }

    @Test fun switchingWhileRadioConnectIsPendingCancelsOldSelectionWithoutWaitingForItsCallback() = scenario {
        val abandoned = manager.addDevice(A)
        val replacement = manager.addDevice(B)
        val gate = abandoned.gatt.holdConnect()
        val firstSelection = scope.async { runCatching { client.connect(A) } }
        gate.started.await()
        client.connect(B)
        gate.drained.await()
        assertIs<DropException.Disconnected>(firstSelection.await().exceptionOrNull())
        val ready = client.state.first { it.phase == HeadsetPhase.READY }
        assertEquals(B, ready.device?.address)
        assertEquals(GattPhase.DISCONNECTED, abandoned.gatt.state.value.phase)
        assertTrue(abandoned.gatt.sessions.isEmpty())
        assertEquals(GattPhase.CONNECTED, replacement.gatt.state.value.phase)
        client.source.ping()
    }

    @Test fun lossAfterManualReadyReconnectsWithNewSessionAndDiscardsOldFunctionalState() = scenario {
        val selected = manager.addDevice(A)
        client.connect(A)
        client.state.first { it.controls.battery == EarbudBattery(42, 43) }
        val oldSession = selected.gatt.current
        selected.config = selected.config.copy(battery = EarbudBattery(51, 52))
        val reconnect = selected.gatt.holdConnect()
        oldSession.close()
        reconnect.started.await()
        val reconnecting = client.state.value
        assertEquals(HeadsetPhase.RECONNECTING, reconnecting.phase)
        assertEquals(A, reconnecting.device?.address)
        assertEquals(EarbudBattery(), reconnecting.controls.battery)
        assertFailsWith<DropException.NotReady> { client.gaia }
        reconnect.release()
        reconnect.drained.await()
        val restored = client.state.first {
            it.phase == HeadsetPhase.READY && it.controls.battery == EarbudBattery(51, 52)
        }
        assertEquals(A, restored.device?.address)
        assertNotSame(oldSession, selected.gatt.current)
        assertEquals(EarbudBattery(51, 52), client.gaia.getBattery())
    }

    @Test fun initialManualFailurePreservesBluetoothExceptionAndDoesNotRetryUntilAnotherRequest() = scenario {
        val selected = manager.addDevice(A)
        val timeout = BtException.Timeout("connect")
        selected.gatt.connectFailure = timeout
        manager.scanFailure = BtException.MissingPermission(setOf("android.permission.BLUETOOTH_SCAN"))
        val reported = scope.async(start = CoroutineStart.UNDISPATCHED) {
            client.events.first { it is HeadsetEvent.Error && it.cause === timeout }
        }
        val failure = runCatching { client.connect(A) }.exceptionOrNull()
        assertSame(timeout, failure)
        reported.await()
        val error = client.state.first { it.phase == HeadsetPhase.ERROR }
        assertSame(timeout, error.error)
        assertEquals(GattPhase.DISCONNECTED, selected.gatt.state.value.phase)
        assertEquals(1, selected.gatt.connectCalls)
        assertFailsWith<DropException.NotReady> { client.gaia }
        selected.gatt.connectFailure = null
        client.connect(A)
        client.state.first { it.phase == HeadsetPhase.READY }
        assertEquals(2, selected.gatt.connectCalls)
    }

    @Test fun switchingFailsOldRequestsAndOldFramesAndControlsCannotOverwriteNewSelection() = scenario {
        val first = manager.addDevice(A)
        val second = manager.addDevice(B, config = FakeDeviceConfig(battery = EarbudBattery(81, 82)))
        client.connect(A)
        client.state.first { it.controls.battery == EarbudBattery(42, 43) }
        val oldSession = first.gatt.current
        val oldControls = client.gaia
        val held = oldSession.holdGaia(GaiaIds.BATTERY, GaiaIds.Battery.LEVELS)
        val oldRead = scope.async { runCatching { oldControls.getBattery() } }
        val oldRequest = held.request.await()
        client.connect(B)
        client.state.first {
            it.phase == HeadsetPhase.READY && it.controls.battery == EarbudBattery(81, 82)
        }
        assertIs<DropException.Disconnected>(oldRead.await().exceptionOrNull())
        oldSession.replyGaia(oldRequest, oldSession.batteryPayload(EarbudBattery(1, 2)))
        oldSession.emitGaia(feature = GaiaIds.BATTERY, command = GaiaIds.Battery.LEVELS,
            payload = oldSession.batteryPayload(EarbudBattery(3, 4)))
        oldSession.close()
        controlBarrier(second.gatt.current)
        assertFailsWith<DropException.Disconnected> { oldControls.getBattery() }
        val current = client.state.value
        assertEquals(B, current.device?.address)
        assertEquals(HeadsetPhase.READY, current.phase)
        assertEquals(EarbudBattery(81, 82), current.controls.battery)
        assertNull(current.error)
        assertEquals(GattPhase.DISCONNECTED, first.gatt.state.value.phase)
        assertEquals(GattPhase.CONNECTED, second.gatt.state.value.phase)
        client.source.ping()
    }

    @Test fun deviceInfoChangesRebuildDisplayedSnapshotWithoutMutatingDiscoveryResult() = scenario {
        val audio = manager.addDevice(A, "Original audio name")
        manager.audioCandidates = listOf(audio)
        val discovered = client.discoverConnectedDevices().single()
        client.connect(discovered)
        client.state.first { it.phase == HeadsetPhase.READY }
        manager.publishDeviceInfo(audio, audio.info.value.copy(name = "Renamed audio device"))
        val renamed = client.state.first { it.device?.name == "Renamed audio device" }
        assertEquals(A, renamed.device?.address)
        assertEquals(HeadsetPhase.READY, renamed.phase)
        assertEquals("Original audio name", discovered.name)
        assertEquals("Renamed audio device", client.discoverConnectedDevices().single().name)
        assertEquals(1, audio.gatt.connectCalls)
    }

    private class MemoryAssociations : HeadsetAssociations {
        private val endpoints = mutableMapOf<String, String>()
        override fun endpoint(address: String): String? = endpoints[address.uppercase()]
        override fun remember(address: String, endpoint: String) { endpoints[address.uppercase()] = endpoint.uppercase() }
    }

    private class Rig(val scope: CoroutineScope) {
        val manager = FakeBtManager(scope)
        val associations = MemoryAssociations()
        val client = HeadsetClient(manager, associations)
        private val controllers = mutableListOf<DropController>()
        fun controller(device: BtDevice): DropController = DropController(device).also { controllers += it }
        suspend fun controlBarrier(session: FakeGattSession) = coroutineScope {
            val processed = async(start = CoroutineStart.UNDISPATCHED) {
                client.events.first {
                    it is HeadsetEvent.Control && it.event is DropEvent.SourceNotification &&
                        it.event.commandId == 250
                }
            }
            session.emitSource(commandId = 250, payload = byteArrayOf(0))
            processed.await()
        }
        fun close() {
            client.close()
            controllers.forEach { it.close() }
            manager.close()
        }
    }

    private fun scenario(block: suspend Rig.() -> Unit) = runBlocking {
        withTimeout(20_000) {
            val rig = Rig(this)
            try { rig.block() } finally { rig.close() }
        }
    }

    companion object {
        private const val A = "AA:00:00:00:00:01"
        private const val B = "BB:00:00:00:00:02"
        private const val BLE = "CC:00:00:00:00:03"
    }
}
