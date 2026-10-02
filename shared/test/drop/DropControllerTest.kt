package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.blueConnector.*
import ink.lipoly.app.sunrise.support.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DropControllerTest {
    @Test fun constructionNeverConnectsAndBothConstructionOrdersBecomeReady() = scenario {
        val before = manager.addDevice(A)
        val first = controller(before)
        assertEquals(0, before.gatt.connectCalls)
        assertFailsWith<DropException.NotReady> { first.awaitReady() }
        before.gatt.connect()
        first.awaitReady()
        assertEquals(EarbudBattery(42, 43), first.gaia.getBattery())

        val after = manager.addDevice(B, config = FakeDeviceConfig(battery = EarbudBattery(81, 82)))
        after.gatt.connect()
        val second = controller(after)
        second.awaitReady()
        assertEquals(EarbudBattery(81, 82), second.gaia.getBattery())
        assertEquals(DropPhase.READY, first.state.value.phase)
        assertEquals(DropPhase.READY, second.state.value.phase)
        assertEquals(1, before.gatt.connectCalls)
        assertEquals(1, after.gatt.connectCalls)
    }

    @Test fun closingDuringInitializationFailsAllReadyWaitersWithoutClosingGatt() = scenario {
        val device = manager.addDevice(A, config = FakeDeviceConfig(gaia = false))
        val session = device.gatt.connect()
        val native = session.holdNativeRead(FakeGattIds.SOURCE_INFO)
        val a = controller(device)
        val first = scope.async { runCatching { a.awaitReady() } }
        val second = scope.async { runCatching { a.awaitReady() } }
        native.started.await()
        a.close()
        assertIs<DropException.Disconnected>(first.await().exceptionOrNull())
        assertIs<DropException.Disconnected>(second.await().exceptionOrNull())
        assertConnected(device)
        native.release()
        native.drained.await()
        assertEquals(2, SourceCodec.firmware(session.read(session.characteristic(FakeGattIds.SOURCE_INFO))).major)
        assertEquals(DropPhase.IDLE, a.state.value.phase)
    }

    @Test fun ordinaryGattWithoutDropProtocolReportsUnsupportedButStaysConnected() = scenario {
        val device = manager.addDevice(A, config = FakeDeviceConfig(gaia = false, source = false))
        device.gatt.connect()
        val a = controller(device)
        assertFailsWith<DropException.UnsupportedDevice> { a.awaitReady() }
        a.state.first { it.phase == DropPhase.ERROR }
        assertIs<DropException.UnsupportedDevice>(a.state.value.error)
        assertConnected(device)
        a.close()
        assertConnected(device)
    }

    @Test fun callerCancellationDoesNotReleaseTheNativeWriteSlotEarly() = scenario {
        val a = connected(A)
        val session = a.device.gatt.current
        val writeCallback = session.holdNativeWrite()
        val battery = scope.async { a.gaia.getBattery() }
        writeCallback.started.await()
        battery.cancel()
        assertFailsWith<CancellationException> { battery.await() }
        val readCallback = session.holdNativeRead(FakeGattIds.SOURCE_CAPABILITY)
        val otherOwner = scope.async(start = CoroutineStart.UNDISPATCHED) {
            session.read(session.characteristic(FakeGattIds.SOURCE_CAPABILITY))
        }
        assertFalse(readCallback.started.isCompleted)
        writeCallback.release()
        writeCallback.drained.await()
        readCallback.started.await()
        readCallback.release()
        val capability = SourceCodec.sourceCapability(otherOwner.await())
        assertEquals(listOf(SourceEntry(7, 3), SourceEntry(11, 1)), capability.entries)
        assertConnected(a.device)
        a.source.ping()
    }

    @Test fun closingControllerCancelsWriteOwnerButDrainsNativeCallbackWithoutDisconnectingEitherDevice() = scenario {
        val a = connected(A)
        val b = connected(B, FakeDeviceConfig(battery = EarbudBattery(81, 82)))
        val session = a.device.gatt.current
        val native = session.holdNativeWrite()
        val request = scope.async { runCatching { a.gaia.getBattery() } }
        native.started.await()
        a.close()
        assertIs<DropException.Disconnected>(request.await().exceptionOrNull())
        assertConnected(a.device, b.device)
        assertEquals(DropPhase.IDLE, a.state.value.phase)
        b.source.ping()
        assertEquals(EarbudBattery(81, 82), b.gaia.getBattery())

        val readCallback = session.holdNativeRead(FakeGattIds.SOURCE_CAPABILITY)
        val otherOwner = scope.async(start = CoroutineStart.UNDISPATCHED) {
            session.read(session.characteristic(FakeGattIds.SOURCE_CAPABILITY))
        }
        assertFalse(readCallback.started.isCompleted)
        native.release()
        native.drained.await()
        readCallback.started.await()
        readCallback.release()
        val direct = otherOwner.await()
        assertEquals(listOf(SourceEntry(7, 3), SourceEntry(11, 1)), SourceCodec.sourceCapability(direct).entries)
        assertConnected(a.device, b.device)
        assertFailsWith<DropException.Disconnected> { a.gaia.getBattery() }
    }

    @Test fun closingControllerCancelsCapabilityAndInfoReadsWithoutOwningNativeQueue() = scenario {
        val b = connected(B)
        for (uuid in listOf(FakeGattIds.SOURCE_CAPABILITY, FakeGattIds.SOURCE_INFO)) {
            val a = connected(A)
            val source = a.source
            val session = a.device.gatt.current
            val native = session.holdNativeRead(uuid)
            val request = scope.async {
                runCatching {
                    if (uuid == FakeGattIds.SOURCE_CAPABILITY) source.readSourceCapability()
                    else source.getFirmwareInfo()
                }
            }
            native.started.await()
            a.close()
            assertIs<DropException.Disconnected>(request.await().exceptionOrNull())
            assertConnected(a.device, b.device)
            native.release()
            native.drained.await()
            val bytes = session.read(session.characteristic(uuid))
            if (uuid == FakeGattIds.SOURCE_CAPABILITY) {
                assertEquals(listOf(SourceEntry(7, 3), SourceEntry(11, 1)), SourceCodec.sourceCapability(bytes).entries)
            } else {
                assertEquals(2, SourceCodec.firmware(bytes).major)
            }
            b.source.ping()
        }
    }

    @Test fun independentDevicesAndGaiaMatcherRejectWrongVendorFeatureAndCommand() = scenario {
        val a = connected(A)
        val b = connected(B, FakeDeviceConfig(battery = EarbudBattery(81, 82)))
        val sa = a.device.gatt.current
        val sb = b.device.gatt.current
        val gateA = sa.holdGaia(GaiaIds.BATTERY, GaiaIds.Battery.LEVELS)
        val gateB = sb.holdGaia(GaiaIds.BATTERY, GaiaIds.Battery.LEVELS)
        val batteryA = scope.async { a.gaia.getBattery() }
        val batteryB = scope.async { b.gaia.getBattery() }
        val wireA = gateA.request.await()
        val wireB = gateB.request.await()
        sb.replyGaia(wireB, sb.batteryPayload())
        assertEquals(EarbudBattery(81, 82), batteryB.await())
        assertFalse(batteryA.isCompleted)

        sa.replyGaia(wireA, sa.batteryPayload(), vendor = wireA.vendor + 1)
        gaiaBarrier(a, sa)
        assertFalse(batteryA.isCompleted)
        sa.replyGaia(wireA, sa.batteryPayload(), feature = GaiaIds.BASIC)
        gaiaBarrier(a, sa)
        assertFalse(batteryA.isCompleted)
        sa.replyGaia(wireA, sa.batteryPayload(), command = GaiaIds.Battery.SUPPORTED)
        gaiaBarrier(a, sa)
        assertFalse(batteryA.isCompleted)
        sa.replyGaia(wireA, sa.batteryPayload())
        assertEquals(EarbudBattery(42, 43), batteryA.await())
        assertEquals(EarbudBattery(42, 43), a.state.value.battery)
        assertEquals(EarbudBattery(81, 82), b.state.value.battery)
    }

    @Test fun sourceMatcherRequiresBothCommandAndSequence() = scenario {
        val a = connected(A)
        val session = a.device.gatt.current
        val gate = session.holdSource(SourceIds.PING)
        val ping = scope.async { a.source.ping() }
        val wire = gate.request.await()
        session.replySource(wire, byteArrayOf(0), commandId = SourceIds.GET_VOLUME)
        sourceBarrier(a, session)
        assertFalse(ping.isCompleted)
        session.replySource(wire, byteArrayOf(0), sequence = (wire.sequence + 1) and 255)
        sourceBarrier(a, session)
        assertFalse(ping.isCompleted)
        session.replySource(wire, byteArrayOf(0))
        ping.await()
        // A subsequent transaction also has to parse a real reply, not reuse the preceding one.
        assertEquals(listOf(SourceEntry(7, 3), SourceEntry(11, 1)), a.source.getCapabilityPage(0).entries)
    }

    @Test fun disconnectedBindingsAndOldFramesCannotCompleteOrMutateNewSession() = scenario {
        val a = connected(A)
        val b = connected(B, FakeDeviceConfig(battery = EarbudBattery(81, 82)))
        val device = a.device as FakeBtDevice
        val oldSession = device.gatt.current
        val oldControls = a.gaia
        val oldGate = oldSession.holdGaia(GaiaIds.BATTERY, GaiaIds.Battery.LEVELS)
        val oldRequest = scope.async { runCatching { oldControls.getBattery() } }
        val oldWire = oldGate.request.await()
        device.gatt.disconnect()
        assertIs<DropException.Disconnected>(oldRequest.await().exceptionOrNull())
        device.config = device.config.copy(battery = EarbudBattery(64, 65), initialAnc = 2)
        val newSession = device.gatt.connect()
        a.awaitReady()
        assertEquals(AncMode.TRANSPARENCY, a.gaia.getAncMode())
        val newGate = newSession.holdGaia(GaiaIds.BATTERY, GaiaIds.Battery.LEVELS)
        val newRequest = scope.async { a.gaia.getBattery() }
        val newWire = newGate.request.await()
        oldSession.replyGaia(oldWire, oldSession.batteryPayload(EarbudBattery(1, 2)))
        oldSession.emitGaia(feature = GaiaIds.BATTERY, command = GaiaIds.Battery.LEVELS,
            payload = oldSession.batteryPayload(EarbudBattery(3, 4)))
        oldSession.emitGaia(feature = GaiaIds.ANC_V2, command = GaiaIds.Anc.GET_MODE, payload = byteArrayOf(0))
        gaiaBarrier(a, newSession)
        assertFalse(newRequest.isCompleted)
        assertEquals(EarbudBattery(), a.state.value.battery)
        assertEquals(AncMode.TRANSPARENCY, a.state.value.ancMode)
        assertFailsWith<DropException.Disconnected> { oldControls.getBattery() }
        assertFailsWith<DropException.Disconnected> { oldControls.setAncMode(AncMode.OFF) }
        newSession.replyGaia(newWire, newSession.batteryPayload())
        assertEquals(EarbudBattery(64, 65), newRequest.await())
        b.source.ping()
        assertEquals(EarbudBattery(81, 82), b.gaia.getBattery())
        assertConnected(device, b.device)
    }

    @Test fun missingReadableCapabilityDoesNotRemoveSourceProtocolOrParsedCapabilityPages() = scenario {
        val a = connected(A, FakeDeviceConfig(gaia = false, capabilityCharacteristic = false, infoCharacteristic = false))
        assertTrue(DropProtocol.SOURCE_9ECA in a.state.value.protocols)
        assertFailsWith<DropException.UnsupportedCapability> { a.source.readSourceCapability() }
        val page = a.source.getCapabilityPage(0)
        assertEquals(SourceCapabilityPage(0, 1, listOf(SourceEntry(7, 3), SourceEntry(11, 1))), page)
        val selected = page.entries.last().sourceId
        assertEquals(SourceStatus(0, selected, selected, 0, 0), a.source.setAudioSource(selected))
        assertEquals(2, a.source.getFirmwareInfo().major)
        a.source.ping()

        val empty = connected(B, FakeDeviceConfig(gaia = false, sourceEntries = emptyList()))
        assertEquals(SourceCapabilityPage(0, 1, emptyList()), empty.source.getCapabilityPage(0))
        empty.source.ping()
    }

    @Test fun ancSetWithoutAckWaitsForReadbackAndDoesNotResendAfterStaleRead() = scenario {
        val a = connected(A)
        val session = a.device.gatt.current
        session.scriptedAncReads += 0
        session.scriptedAncReads += 1
        assertEquals(AncMode.NOISE_CANCELLING, a.gaia.setAncMode(AncMode.NOISE_CANCELLING))
        assertEquals(AncMode.NOISE_CANCELLING, a.state.value.ancMode)
        assertEquals(1, session.gaiaWrites.count { it.feature == GaiaIds.ANC_V2 && it.command == GaiaIds.Anc.SET_MODE })
    }

    @Test fun persistentAncMismatchPreservesObservedModeAndExceptionPayload() = scenario {
        val a = connected(A, FakeDeviceConfig(ancWriteToRead = mapOf(1 to 0)))
        val error = assertFailsWith<DropException.AncModeMismatch> { a.gaia.setAncMode(AncMode.NOISE_CANCELLING) }
        assertEquals(AncMode.NOISE_CANCELLING, error.requested)
        assertEquals(AncMode.OFF, error.observed)
        assertEquals(AncMode.OFF, a.state.value.ancMode)
        assertEquals(1, a.device.gatt.current.gaiaWrites.count { it.feature == GaiaIds.ANC_V2 && it.command == GaiaIds.Anc.SET_MODE })
    }

    @Test fun failedAncReadbackIsUnverifiedAndClearsPreviouslyKnownMode() = scenario {
        val a = connected(A)
        assertEquals(AncMode.OFF, a.gaia.getAncMode())
        a.device.gatt.current.ancReadFailure = BtException.Transport("ANC read callback failed")
        val error = assertFailsWith<DropException.Unverified> { a.gaia.setAncMode(AncMode.NOISE_CANCELLING) }
        assertIs<DropException.Transport>(error.cause)
        assertIs<BtException.Transport>(error.cause?.cause)
        assertNull(a.state.value.ancMode)
        assertEquals(1, a.device.gatt.current.gaiaWrites.count { it.feature == GaiaIds.ANC_V2 && it.command == GaiaIds.Anc.SET_MODE })
    }

    @Test fun cancellationDuringAncReadbackDoesNotBecomeConfirmationAndLeavesGattUsable() = scenario {
        val a = connected(A)
        val session = a.device.gatt.current
        val gate = session.holdGaia(GaiaIds.ANC_V2, GaiaIds.Anc.GET_MODE)
        val setting = scope.async { a.gaia.setAncMode(AncMode.NOISE_CANCELLING) }
        val wire = gate.request.await()
        setting.cancel()
        assertFailsWith<CancellationException> { setting.await() }
        session.replyGaia(wire, byteArrayOf(1))
        gaiaBarrier(a, session)
        assertNull(a.state.value.ancMode)
        assertEquals(EarbudBattery(42, 43), a.gaia.getBattery())
        assertConnected(a.device)
    }

    @Test fun disconnectDuringAncReadbackFailsRatherThanReturningSentMode() = scenario {
        val a = connected(A)
        val session = a.device.gatt.current
        val gate = session.holdGaia(GaiaIds.ANC_V2, GaiaIds.Anc.GET_MODE)
        val setting = scope.async { runCatching { a.gaia.setAncMode(AncMode.NOISE_CANCELLING) } }
        val wire = gate.request.await()
        a.device.gatt.disconnect()
        assertIs<DropException.Disconnected>(setting.await().exceptionOrNull())
        session.replyGaia(wire, byteArrayOf(1))
        a.state.first { it.phase == DropPhase.IDLE }
        assertNull(a.state.value.ancMode)
        assertEquals(EarbudBattery(), a.state.value.battery)
        assertTrue(a.state.value.protocols.isEmpty())
        assertEquals(DropCapabilities(), a.state.value.capabilities)
    }

    @Test fun profileUsesAudioIdentityWithIndependentWriteAndReadNumbering() = scenario {
        val audio = manager.addDevice(B, "Other brand audio")
        val endpoint = manager.addDevice(A, "Other brand BLE", FakeDeviceConfig(
            gaiaFeatures = setOf(GaiaIds.BATTERY, GaiaIds.AUDIO_CURATION), initialAnc = 0,
            ancWriteToRead = mapOf(9 to 0, 12 to 2),
        ))
        val options = DropOptions(listOf(
            DropProfile(DropProfileMatch.NameContains("Other brand"),
                audioCurationWrite = mapOf(AncMode.OFF to 1, AncMode.TRANSPARENCY to 3)),
            DropProfile(DropProfileMatch.Address(audio.address),
                audioCurationWrite = mapOf(AncMode.OFF to 9, AncMode.TRANSPARENCY to 12),
                audioCurationRead = mapOf(0 to AncMode.OFF, 2 to AncMode.TRANSPARENCY)),
        ))
        endpoint.gatt.connect()
        val a = controller(endpoint, options, audio)
        a.awaitReady()
        assertEquals(AncMode.OFF, a.gaia.getAncMode())
        assertEquals(AncMode.TRANSPARENCY, a.gaia.setAncMode(AncMode.TRANSPARENCY))
        assertEquals(AncMode.TRANSPARENCY, a.state.value.ancMode)
        assertEquals(2, endpoint.gatt.current.rawAnc)
        val writes = endpoint.gatt.current.gaiaWrites.filter {
            it.feature == GaiaIds.AUDIO_CURATION && it.command == GaiaIds.AudioCuration.SET_MODE
        }
        assertContentEquals(byteArrayOf(12), writes.single().payload)
        assertEquals(0, audio.gatt.connectCalls)
    }

    private class Rig(val scope: CoroutineScope) {
        val manager = FakeBtManager(scope)
        private val controllers = mutableListOf<DropController>()
        fun controller(device: FakeBtDevice, options: DropOptions = DropOptions(), profileDevice: BtDevice = device): DropController =
            DropController(device, options, profileDevice).also { controllers += it }
        suspend fun connected(address: String, config: FakeDeviceConfig = FakeDeviceConfig()): DropController {
            val device = manager.addDevice(address, config = config)
            device.gatt.connect()
            return controller(device).also { it.awaitReady() }
        }
        fun close() {
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
    private fun assertConnected(vararg devices: BtDevice) {
        devices.forEach { assertEquals(GattPhase.CONNECTED, it.gatt.state.value.phase) }
    }
    private suspend fun gaiaBarrier(controller: DropController, session: FakeGattSession) = coroutineScope {
        val processed = async(start = CoroutineStart.UNDISPATCHED) {
            controller.events.first { it is DropEvent.GaiaNotification && it.packet.feature == GaiaIds.BASIC && it.packet.command == GaiaIds.Basic.VERSION }
        }
        session.emitGaia(feature = GaiaIds.BASIC, command = GaiaIds.Basic.VERSION, payload = byteArrayOf(0))
        processed.await()
    }
    private suspend fun sourceBarrier(controller: DropController, session: FakeGattSession) = coroutineScope {
        val processed = async(start = CoroutineStart.UNDISPATCHED) {
            controller.events.first { it is DropEvent.SourceNotification && it.commandId == 250 }
        }
        session.emitSource(commandId = 250, payload = byteArrayOf(0))
        processed.await()
    }
    companion object {
        private const val A = "AA:00:00:00:00:01"
        private const val B = "BB:00:00:00:00:02"
    }
}

private val BtGatt.current: FakeGattSession get() = (this as FakeBtGatt).current
