package ink.lipoly.app.sunrise.composeLegacy

import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.AudioCodec
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.EarbudBattery
import ink.lipoly.app.sunrise.drop.GainLevel
import ink.lipoly.app.sunrise.drop.GaiaCommand
import ink.lipoly.app.sunrise.drop.GaiaControls
import ink.lipoly.app.sunrise.drop.GaiaPacket
import ink.lipoly.app.sunrise.drop.GaiaParamEqState
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.HeadTrackingMode
import ink.lipoly.app.sunrise.drop.PeqFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ParamEqEditorTest {
    @Test
    fun constructionReadsActualPresetAndBandsWithoutWriting() = runTest {
        val device = device()
        val editor = editor(device)
        assertEquals(ParamEqEditPhase.LOADING, editor.state.value.phase)
        assertTrue(editor.state.value.draft.isEmpty())

        runCurrent()

        assertEquals(1, device.reads)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(2, editor.state.value.confirmed?.currentPreset)
        assertEquals(device.stored.bands, editor.state.value.draft)
        assertFalse(editor.state.value.canUndo)
        assertTrue(device.writes.isEmpty())
        editor.close()
    }

    @Test
    fun unavailableReadNeverSuppliesFictionalBandsAndCanBeReloaded() = runTest {
        val device = device().apply { available = false }
        val editor = editor(device)
        runCurrent()
        assertEquals(ParamEqEditPhase.UNAVAILABLE, editor.state.value.phase)
        assertTrue(editor.state.value.draft.isEmpty())
        assertNull(editor.state.value.confirmed)
        assertIs<DropException.UnsupportedCapability>(editor.state.value.error)
        editor.beginEdit()
        editor.flush()
        assertFalse(editor.state.value.isEditing)
        assertTrue(device.writes.isEmpty())

        device.available = true
        editor.refresh()
        runCurrent()
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(device.stored.bands, editor.state.value.draft)
        editor.close()
    }

    @Test
    fun loadFailureRequiresExplicitReload() = runTest {
        val device = device().apply { nextReadFailure = DropException.Timeout("EQ load") }
        val editor = editor(device)
        runCurrent()
        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, device.reads)

        editor.refresh()
        assertEquals(ParamEqEditPhase.LOADING, editor.state.value.phase)
        runCurrent()
        assertEquals(2, device.reads)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        editor.close()
    }

    @Test
    fun sustainedEditingConflatesLatestDraftAtMonotonic150MillisecondStarts() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(30)
        runCurrent()
        for (step in 2..13) {
            advanceTimeBy(25)
            editor.changeGain(step * 30)
            runCurrent()
        }

        assertEquals(listOf(0L, 150L, 300L), device.writes.map { it.startedAt })
        assertEquals(listOf(30, 210, 390), device.writes.map { it.bands.single().gainRaw })
        assertEquals(390, device.stored.bands.single().gainRaw)
        assertEquals(1, device.maxConcurrentWrites)
        assertTrue(editor.state.value.isEditing)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        editor.endEdit()
        runCurrent()
        assertFalse(editor.state.value.isEditing)
        assertEquals(3, device.writes.size)
        editor.close()
    }

    @Test
    fun releaseFlushSkipsTheRemainingThrottleWait() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        runCurrent()
        advanceTimeBy(10)
        editor.changeGain(180)
        runCurrent()
        assertEquals(ParamEqEditPhase.PENDING, editor.state.value.phase)
        assertEquals(1, device.writes.size)

        editor.endEdit()
        editor.refresh()
        assertEquals(ParamEqEditPhase.PENDING, editor.state.value.phase)
        assertEquals(1, device.reads)
        runCurrent()

        assertEquals(listOf(0L, 10L), device.writes.map { it.startedAt })
        assertEquals(180, device.stored.bands.single().gainRaw)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertTrue(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun olderInflightReadbackConfirmsActualButNeverRollsBackNewerDraft() = runTest {
        val device = device().apply { writeDelayMillis = 200 }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        runCurrent()
        advanceTimeBy(50)
        editor.changeGain(180)
        runCurrent()
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        assertEquals(1, device.writes.size)

        advanceTimeBy(150)
        runCurrent()

        assertEquals(60, editor.state.value.confirmed?.bands?.single()?.gainRaw)
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        assertEquals(listOf(0L, 200L), device.writes.map { it.startedAt })
        assertEquals(0, device.canceledWrites)
        assertEquals(1, device.maxConcurrentWrites)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(180, device.stored.bands.single().gainRaw)
        assertTrue(editor.state.value.isEditing)
        editor.endEdit()
        runCurrent()
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertFalse(editor.state.value.isEditing)
        editor.close()
    }

    @Test
    fun flushDuringInflightWriteWaitsForThatTransactionAndThenUsesLatestSnapshot() = runTest {
        val device = device().apply { writeDelayMillis = 100 }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        runCurrent()
        advanceTimeBy(10)
        editor.changeGain(120)
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        assertEquals(1, device.writes.size)

        advanceTimeBy(90)
        runCurrent()
        assertEquals(listOf(0L, 100L), device.writes.map { it.startedAt })
        assertEquals(listOf(60, 180), device.writes.map { it.bands.single().gainRaw })
        assertEquals(1, device.maxConcurrentWrites)
        assertEquals(0, device.canceledWrites)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(180, device.stored.bands.single().gainRaw)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        editor.close()
    }

    @Test
    fun undoIsOneGestureSnapshotAndQueuesBehindInflightWrite() = runTest {
        val device = device().apply { writeDelayMillis = 300 }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        editor.changeGain(120)
        editor.changeGain(180)
        assertFalse(editor.state.value.canUndo)
        editor.undo()
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        editor.endEdit()
        runCurrent()
        assertTrue(editor.state.value.canUndo)

        editor.undo()
        runCurrent()
        assertEquals(0, editor.state.value.draft.single().gainRaw)
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        assertFalse(editor.state.value.canUndo)
        assertEquals(1, device.writes.size)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(180, editor.state.value.confirmed?.bands?.single()?.gainRaw)
        assertEquals(0, editor.state.value.draft.single().gainRaw)
        assertEquals(listOf(180, 0), device.writes.map { it.bands.single().gainRaw })
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, device.stored.bands.single().gainRaw)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(0, device.canceledWrites)
        editor.close()
    }

    @Test
    fun unchangedClickDoesNotErasePreviousUndoOrWriteAgain() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        assertTrue(editor.state.value.canUndo)

        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        assertEquals(1, device.writes.size)
        assertTrue(editor.state.value.canUndo)
        editor.undo()
        runCurrent()
        assertEquals(0, device.stored.bands.single().gainRaw)
        assertFalse(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun aNewGestureReplacesTheSingleUndoPointRatherThanBuildingHistory() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        editor.endEdit()
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()

        editor.undo()
        runCurrent()
        assertEquals(60, device.stored.bands.single().gainRaw)
        assertFalse(editor.state.value.canUndo)
        editor.undo()
        runCurrent()
        assertEquals(3, device.writes.size)
        assertEquals(60, device.stored.bands.single().gainRaw)
        editor.close()
    }

    @Test
    fun mismatchStopsPendingWritesAndFailureEndEditDoesNotRestartThem() = runTest {
        val device = device().apply {
            gainLimitRaw = 120
            writeDelayMillis = 100
        }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        runCurrent()
        advanceTimeBy(10)
        editor.changeGain(240)
        editor.flush()
        advanceTimeBy(90)
        runCurrent()

        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertIs<DropException.ParamEqMismatch>(editor.state.value.error)
        assertEquals(120, editor.state.value.confirmed?.bands?.single()?.gainRaw)
        assertEquals(240, editor.state.value.draft.single().gainRaw)
        assertTrue(editor.state.value.isEditing)
        assertFalse(editor.state.value.canUndo)
        editor.refresh() // A still-active failed gesture must first be ended.
        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        editor.endEdit()
        editor.undo()
        editor.changeGain(60)
        editor.flush()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(editor.state.value.isEditing)
        assertEquals(1, device.writes.size)
        assertEquals(240, editor.state.value.draft.single().gainRaw)

        editor.refresh()
        assertTrue(editor.state.value.draft.isEmpty())
        runCurrent()
        assertEquals(120, editor.state.value.draft.single().gainRaw)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertFalse(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun unverifiedWriteDropsConfirmedAndReloadUsesStoredDeviceValue() = runTest {
        val device = device().apply {
            nextWriteFailure = DropException.Unverified("EQ write", DropException.Timeout("readback"))
            failAfterApply = true
        }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()

        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertIs<DropException.Unverified>(editor.state.value.error)
        assertEquals(180, device.stored.bands.single().gainRaw)
        assertFalse(editor.state.value.canUndo)
        editor.refresh()
        runCurrent()
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertEquals(180, editor.state.value.confirmed?.bands?.single()?.gainRaw)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertFalse(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun refusedWritePreservesTheDraftButDoesNotRetryOrAllowUndoToBypassReload() = runTest {
        val device = device().apply { nextWriteFailure = DropException.Rejected(1, "PEQ") }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()

        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertEquals(0, device.stored.bands.single().gainRaw)
        editor.undo()
        editor.flush()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, device.writes.size)
        editor.refresh()
        runCurrent()
        assertEquals(0, editor.state.value.draft.single().gainRaw)
        assertFalse(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun refreshCannotSwallowActiveGestureOrPendingFinalValueAndClearsUndoWhenIdle() = runTest {
        val device = device().apply { writeDelayMillis = 100 }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.refresh()
        assertEquals(1, device.reads)
        assertTrue(editor.state.value.isEditing)
        editor.changeGain(180)
        runCurrent()
        editor.refresh()
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        editor.endEdit()
        editor.refresh()
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        advanceTimeBy(100)
        runCurrent()
        assertTrue(editor.state.value.canUndo)

        editor.refresh()
        assertEquals(ParamEqEditPhase.LOADING, editor.state.value.phase)
        assertFalse(editor.state.value.canUndo)
        assertTrue(editor.state.value.draft.isEmpty())
        runCurrent()
        assertEquals(2, device.reads)
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertFalse(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun closingOldEditorCancelsItsOwnWorkAndNeverRoutesDraftToNewControls() = runTest {
        val oldDevice = device().apply { writeDelayMillis = 300 }
        val oldEditor = editor(oldDevice)
        runCurrent()
        oldEditor.beginEdit()
        oldEditor.changeGain(180)
        runCurrent()
        advanceTimeBy(50)
        oldEditor.changeGain(240)
        oldEditor.close()
        oldEditor.endEdit()
        oldEditor.flush()
        oldEditor.refresh()
        oldEditor.undo()
        val newDevice = device()
        val newEditor = editor(newDevice)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, oldDevice.writes.size)
        assertEquals(1, oldDevice.canceledWrites)
        assertEquals(0, oldDevice.stored.bands.single().gainRaw)
        assertTrue(oldEditor.state.value.draft.isEmpty())
        assertNull(oldEditor.state.value.confirmed)
        assertFalse(oldEditor.state.value.canUndo)
        assertTrue(newDevice.writes.isEmpty())
        assertEquals(0, newEditor.state.value.draft.single().gainRaw)
        newEditor.close()
    }

    @Test
    fun flattenPublishesOneWholeSnapshotAndUndoRestoresAllBandsTogether() = runTest {
        val initial = listOf(band(0, gainRaw = 60), band(1, gainRaw = 120), band(2, gainRaw = -180))
        val device = device(initial)
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.replaceDraft(editor.state.value.draft.map { it.copy(gainRaw = 0) })
        editor.endEdit()
        runCurrent()

        assertEquals(1, device.writes.size)
        assertEquals(listOf(0, 0, 0), device.writes.single().bands.map { it.gainRaw })
        assertTrue(editor.state.value.canUndo)
        editor.undo()
        runCurrent()
        assertEquals(initial, device.stored.bands)
        assertEquals(initial, device.writes.last().bands)
        assertEquals(2, device.writes.size)
        editor.close()
    }

    @Test
    fun completeDraftIsValidatedBeforeAnyLocalPublicationOrDeviceWrite() = runTest {
        val initial = listOf(band(0), band(1))
        val device = device(initial)
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        assertFailsWith<IllegalArgumentException> { editor.replaceDraft(listOf(band(0))) }
        assertFailsWith<IllegalArgumentException> { editor.replaceDraft(listOf(band(0), band(3))) }
        assertFailsWith<IllegalArgumentException> {
            editor.replaceDraft(listOf(band(0, gainRaw = 180), band(1).copy(qRaw = 0)))
        }
        assertEquals(initial, editor.state.value.draft)
        editor.endEdit()
        runCurrent()
        assertTrue(device.writes.isEmpty())
        assertFalse(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun rawValuesSurviveUnchangedPublicationAndSingleFieldEditing() = runTest {
        val initial = band(0).copy(gainRaw = 119, qRaw = 4097)
        val device = device(listOf(initial))
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.replaceDraft(listOf(initial))
        editor.endEdit()
        runCurrent()
        assertTrue(device.writes.isEmpty())

        editor.beginEdit()
        editor.editBand(initial.copy(frequencyHz = 1122))
        editor.endEdit()
        runCurrent()
        assertEquals(119, device.stored.bands.single().gainRaw)
        assertEquals(4097, device.stored.bands.single().qRaw)
        assertEquals(1122, device.stored.bands.single().frequencyHz)
        assertEquals(63, device.stored.currentPreset)
        assertEquals(-17, device.stored.totalGainRaw)
        editor.close()
    }

    @Test
    fun unusedBypassSlotsAreNotInventedOrRequantizedByAnotherBandEdit() = runTest {
        val bypass = band(1).copy(frequencyHz = 0, qRaw = 0, gainRaw = -33, filter = PeqFilter.BYPASS)
        val device = device(listOf(band(0), bypass))
        val editor = editor(device)
        runCurrent()
        assertEquals(bypass, editor.state.value.draft[1])
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        assertEquals(bypass, device.stored.bands[1])
        assertEquals(bypass, device.writes.single().bands[1])
        editor.close()
    }

    @Test
    fun callerMutableListCannotChangeAnAlreadyPublishedCompleteDraft() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        val candidate = mutableListOf(band(0, gainRaw = 180))
        editor.beginEdit()
        editor.replaceDraft(candidate)
        candidate[0] = band(0, gainRaw = 240)
        editor.endEdit()
        runCurrent()
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertEquals(180, device.stored.bands.single().gainRaw)
        editor.close()
    }

    @Test
    fun returningToConfirmedRawValuesDuringThrottleDoesNotWriteRedundantSnapshot() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        runCurrent()
        advanceTimeBy(10)
        editor.changeGain(120)
        runCurrent()
        editor.changeGain(60)
        editor.endEdit()
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, device.writes.size)
        assertEquals(60, device.stored.bands.single().gainRaw)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        editor.close()
    }

    private fun TestScope.device(bands: List<GaiaPeqBand> = listOf(band(0))) = StoredGaiaDevice(
        initial = GaiaParamEqState(listOf(0, 2, 63), 2, -17, bands),
        now = { testScheduler.currentTime },
    )

    private fun TestScope.editor(device: StoredGaiaDevice) =
        ParamEqEditor(backgroundScope, device, testScheduler.timeSource)

    private fun ParamEqEditor.changeGain(gainRaw: Int) = editBand(state.value.draft.first().copy(gainRaw = gainRaw))
}

private fun band(index: Int, gainRaw: Int = 0) =
    GaiaPeqBand(index, 1000 + index * 1000, gainRaw, 4096, PeqFilter.PEAKING)

/** Stateful device substitute: writes can be delayed, refused, partially accepted, or unverifiable. */
private class StoredGaiaDevice(initial: GaiaParamEqState, private val now: () -> Long) : GaiaControls {
    data class Write(val startedAt: Long, val bands: List<GaiaPeqBand>)

    var stored = initial.copy(bands = initial.bands.toList())
        private set
    val writes = mutableListOf<Write>()
    var reads = 0
        private set
    var available = true
    var writeDelayMillis = 0L
    var gainLimitRaw: Int? = null
    var nextReadFailure: Exception? = null
    var nextWriteFailure: Exception? = null
    var failAfterApply = false
    var maxConcurrentWrites = 0
        private set
    var canceledWrites = 0
        private set
    private var concurrentWrites = 0

    override suspend fun getParamEq(): GaiaParamEqState {
        reads++
        if (!available) throw DropException.UnsupportedCapability("GAIA Bluetrum PEQ")
        nextReadFailure?.let {
            nextReadFailure = null
            throw it
        }
        return stored.copy(bands = stored.bands.toList())
    }

    override suspend fun setParamEq(bands: List<GaiaPeqBand>): GaiaParamEqState {
        writes += Write(now(), bands.toList())
        concurrentWrites++
        maxConcurrentWrites = maxOf(maxConcurrentWrites, concurrentWrites)
        try {
            delay(writeDelayMillis)
            if (!available) throw DropException.Disconnected()
            val failure = nextWriteFailure
            nextWriteFailure = null
            if (failure != null && !failAfterApply) throw failure
            val limit = gainLimitRaw
            val actualBands = bands.map { band ->
                if (limit == null) band else band.copy(gainRaw = band.gainRaw.coerceIn(-limit, limit))
            }
            stored = stored.copy(currentPreset = 63, bands = actualBands)
            if (failure != null) throw failure
            val actual = stored.copy(bands = stored.bands.toList())
            if (actual.bands != bands) throw DropException.ParamEqMismatch(actual)
            return actual
        } catch (cancelled: CancellationException) {
            canceledWrites++
            throw cancelled
        } finally {
            concurrentWrites--
        }
    }

    private fun unexpected(): Nothing = throw AssertionError("Editor called an unrelated GAIA operation")
    override suspend fun getBattery(): EarbudBattery = unexpected()
    override suspend fun getAncMode(): AncMode = unexpected()
    override suspend fun setAncMode(mode: AncMode): AncMode = unexpected()
    override suspend fun getGain(): GainLevel = unexpected()
    override suspend fun setGain(level: GainLevel): GainLevel = unexpected()
    override suspend fun isLedOn(): Boolean = unexpected()
    override suspend fun setLedOn(on: Boolean): Boolean = unexpected()
    override suspend fun isSpatialOn(): Boolean = unexpected()
    override suspend fun setSpatialOn(on: Boolean): Boolean = unexpected()
    override suspend fun getHeadTracking(): HeadTrackingMode = unexpected()
    override suspend fun setHeadTracking(mode: HeadTrackingMode): HeadTrackingMode = unexpected()
    override suspend fun isCodecEnabled(codec: AudioCodec): Boolean = unexpected()
    override suspend fun setCodecEnabled(codec: AudioCodec, enabled: Boolean): Boolean = unexpected()
    override suspend fun isDynamicBassOn(): Boolean = unexpected()
    override suspend fun setDynamicBassOn(on: Boolean): Boolean = unexpected()
    override suspend fun isLeftRightReversed(): Boolean = unexpected()
    override suspend fun setLeftRightReversed(reversed: Boolean): Boolean = unexpected()
    override suspend fun getEqualizerPreset(): Int = unexpected()
    override suspend fun setEqualizerPreset(index: Int): Int = unexpected()
    override suspend fun getGestureConfiguration(gesture: Int, context: Int): GaiaPacket = unexpected()
    override suspend fun resetGestureConfiguration(): GaiaPacket = unexpected()
    override suspend fun getBasicInfo(command: Int): GaiaPacket = unexpected()
    override suspend fun getAudioCuration(command: Int): GaiaPacket = unexpected()
    override suspend fun setAudioCuration(command: Int, payload: ByteArray): GaiaPacket = unexpected()
    override suspend fun powerOff(): Unit = unexpected()
    override suspend fun requestRaw(command: GaiaCommand): GaiaPacket = unexpected()
    override suspend fun sendRaw(command: GaiaCommand): Unit = unexpected()
}
