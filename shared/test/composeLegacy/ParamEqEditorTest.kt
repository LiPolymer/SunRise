package ink.lipoly.app.sunrise.composeLegacy

import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.GaiaParamEqState
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.PeqFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.time.Duration.Companion.milliseconds

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
        assertNull(editor.state.value.lastSent)
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
        advanceTimeBy(1_000.milliseconds)
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
            advanceTimeBy(25.milliseconds)
            editor.changeGain(step * 30)
            runCurrent()
        }

        assertEquals(listOf(0L, 150L, 300L), device.writes.map { it.startedAt })
        assertEquals(listOf(30, 210, 390), device.writes.map { it.bands.single().gainRaw })
        assertEquals(390, device.stored.bands.single().gainRaw)
        assertEquals(1, device.maxConcurrentWrites)
        assertTrue(editor.state.value.isEditing)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
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
        advanceTimeBy(10.milliseconds)
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
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertTrue(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun olderInflightSendNeverConfirmsActualOrRollsBackNewerDraft() = runTest {
        val device = device().apply { writeDelayMillis = 200 }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        runCurrent()
        assertNull(editor.state.value.confirmed)
        assertNull(editor.state.value.lastSent)
        advanceTimeBy(50.milliseconds)
        editor.changeGain(180)
        runCurrent()
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        assertEquals(1, device.writes.size)

        advanceTimeBy(150.milliseconds)
        runCurrent()

        assertNull(editor.state.value.confirmed)
        assertEquals(60, editor.state.value.lastSent?.single()?.gainRaw)
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        assertEquals(listOf(0L, 200L), device.writes.map { it.startedAt })
        assertEquals(0, device.canceledWrites)
        assertEquals(1, device.maxConcurrentWrites)
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals(180, device.stored.bands.single().gainRaw)
        assertTrue(editor.state.value.isEditing)
        editor.endEdit()
        runCurrent()
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
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
        advanceTimeBy(10.milliseconds)
        editor.changeGain(120)
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        assertEquals(1, device.writes.size)

        advanceTimeBy(90.milliseconds)
        runCurrent()
        assertEquals(listOf(0L, 100L), device.writes.map { it.startedAt })
        assertEquals(listOf(60, 180), device.writes.map { it.bands.single().gainRaw })
        assertEquals(1, device.maxConcurrentWrites)
        assertEquals(0, device.canceledWrites)
        advanceTimeBy(100.milliseconds)
        runCurrent()
        assertEquals(180, device.stored.bands.single().gainRaw)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
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
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertNull(editor.state.value.confirmed)
        assertEquals(180, editor.state.value.lastSent?.single()?.gainRaw)
        assertEquals(0, editor.state.value.draft.single().gainRaw)
        assertEquals(listOf(180, 0), device.writes.map { it.bands.single().gainRaw })
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        advanceTimeBy(300.milliseconds)
        runCurrent()
        assertEquals(0, device.stored.bands.single().gainRaw)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
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
    fun transportFailureStopsPendingWritesAndFailureEndEditDoesNotRestartThem() = runTest {
        val failure = DropException.Timeout("EQ send")
        val device = device().apply {
            nextWriteFailure = failure
            writeDelayMillis = 100
        }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        runCurrent()
        advanceTimeBy(10.milliseconds)
        editor.changeGain(240)
        editor.flush()
        advanceTimeBy(90.milliseconds)
        runCurrent()

        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertEquals(failure, editor.state.value.error)
        assertNull(editor.state.value.confirmed)
        assertNull(editor.state.value.lastSent)
        assertEquals(240, editor.state.value.draft.single().gainRaw)
        assertTrue(editor.state.value.isEditing)
        assertFalse(editor.state.value.canUndo)
        editor.refresh() // A still-active failed gesture must first be ended.
        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        editor.endEdit()
        editor.undo()
        editor.changeGain(60)
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertFalse(editor.state.value.isEditing)
        assertEquals(1, device.writes.size)
        assertEquals(240, editor.state.value.draft.single().gainRaw)

        editor.refresh()
        assertTrue(editor.state.value.draft.isEmpty())
        runCurrent()
        assertEquals(0, editor.state.value.draft.single().gainRaw)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertFalse(editor.state.value.canUndo)
        editor.close()
    }

    @Test
    fun failedSendAfterPartialApplicationRequiresActualReloadAndPreservesOriginalFailure() = runTest {
        val failure = DropException.Timeout("EQ send")
        val device = device().apply {
            nextWriteFailure = failure
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
        assertNull(editor.state.value.lastSent)
        assertEquals(failure, editor.state.value.error)
        assertEquals(180, device.stored.bands.single().gainRaw)
        assertFalse(editor.state.value.canUndo)
        editor.refresh()
        runCurrent()
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertEquals(180, editor.state.value.confirmed?.bands?.single()?.gainRaw)
        assertNull(editor.state.value.lastSent)
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
        assertNull(editor.state.value.confirmed)
        assertNull(editor.state.value.lastSent)
        assertIs<DropException.Rejected>(editor.state.value.error)
        assertEquals(0, device.stored.bands.single().gainRaw)
        editor.undo()
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
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
        advanceTimeBy(100.milliseconds)
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
        advanceTimeBy(50.milliseconds)
        oldEditor.changeGain(240)
        oldEditor.close()
        oldEditor.endEdit()
        oldEditor.flush()
        oldEditor.refresh()
        oldEditor.undo()
        val newDevice = device()
        val newEditor = editor(newDevice)
        runCurrent()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()

        assertEquals(1, oldDevice.writes.size)
        assertEquals(1, oldDevice.canceledWrites)
        assertEquals(0, oldDevice.stored.bands.single().gainRaw)
        assertTrue(oldEditor.state.value.draft.isEmpty())
        assertNull(oldEditor.state.value.confirmed)
        assertNull(oldEditor.state.value.lastSent)
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
    fun returningToLastSentRawValuesDuringThrottleDoesNotWriteRedundantSnapshot() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        runCurrent()
        advanceTimeBy(10.milliseconds)
        editor.changeGain(120)
        runCurrent()
        editor.changeGain(60)
        editor.endEdit()
        runCurrent()
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)
        assertEquals(60, device.stored.bands.single().gainRaw)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertEquals(editor.state.value.draft, editor.state.value.lastSent)
        editor.close()
    }

    @Test
    fun successfulTransportSettlesWithoutReadbackEvenWhenDeviceDoesNotApplyIt() = runTest {
        val device = device().apply { applyWrites = false }
        val editor = editor(device)
        runCurrent()
        val actual = device.stored
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()

        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertEquals(180, editor.state.value.lastSent?.single()?.gainRaw)
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertEquals(actual, device.stored)
        assertEquals(1, device.reads)
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)

        editor.refresh()
        assertEquals(ParamEqEditPhase.LOADING, editor.state.value.phase)
        assertNull(editor.state.value.lastSent)
        assertTrue(editor.state.value.draft.isEmpty())
        runCurrent()
        assertEquals(2, device.reads)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(actual, editor.state.value.confirmed)
        assertEquals(actual.bands, editor.state.value.draft)
        assertFalse(editor.state.value.canUndo)
        assertEquals(1, device.writes.size)
        editor.close()
    }

    @Test
    fun unchangedSuccessfulDraftCannotBeResentByFlushReleaseOrQueuedWakeUps() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        repeat(3) {
            editor.flush()
            editor.beginEdit()
            editor.changeGain(180)
            editor.replaceDraft(editor.state.value.draft.toList())
            editor.flush()
            editor.endEdit()
            runCurrent()
            advanceTimeBy(200.milliseconds)
            runCurrent()
        }

        assertEquals(1, device.reads)
        assertEquals(1, device.writes.size)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertEquals(editor.state.value.draft, editor.state.value.lastSent)
        assertTrue(editor.state.value.canUndo)
        editor.undo()
        runCurrent()
        assertEquals(listOf(180, 0), device.writes.map { it.bands.single().gainRaw })
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertEquals(0, editor.state.value.lastSent?.single()?.gainRaw)
        assertFalse(editor.state.value.canUndo)
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(2, device.writes.size)
        editor.close()
    }

    @Test
    fun newerDraftThatReturnsToInflightSnapshotSettlesSentWithoutAnotherTransaction() = runTest {
        val device = device().apply { writeDelayMillis = 200 }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        runCurrent()
        advanceTimeBy(50.milliseconds)
        editor.changeGain(180)
        editor.changeGain(60)
        editor.endEdit()
        editor.flush()
        runCurrent()
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        advanceTimeBy(150.milliseconds)
        runCurrent()

        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertEquals(editor.state.value.draft, editor.state.value.lastSent)
        assertEquals(60, editor.state.value.draft.single().gainRaw)
        assertTrue(editor.state.value.canUndo)
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)
        assertEquals(1, device.reads)
        editor.close()
    }

    @Test
    fun manualRefreshReadsClampedDeviceValueRatherThanUsingSuccessfullySentDraft() = runTest {
        val device = device().apply { gainLimitRaw = 120 }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(240)
        editor.endEdit()
        runCurrent()

        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertEquals(240, editor.state.value.draft.single().gainRaw)
        assertEquals(240, editor.state.value.lastSent?.single()?.gainRaw)
        assertEquals(120, device.stored.bands.single().gainRaw)
        assertEquals(1, device.reads)
        editor.refresh()
        runCurrent()

        assertEquals(2, device.reads)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(device.stored, editor.state.value.confirmed)
        assertEquals(120, editor.state.value.draft.single().gainRaw)
        assertNull(editor.state.value.lastSent)
        assertFalse(editor.state.value.canUndo)
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)
        editor.close()
    }

    @Test
    fun fullDraftReplacementAndFlattenRemainEditableAfterUnverifiedSend() = runTest {
        val device = device(listOf(band(0, gainRaw = 60), band(1, gainRaw = 120)))
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        assertNull(editor.state.value.confirmed)

        val replacement = listOf(band(0, gainRaw = 240), band(1, gainRaw = -120))
        editor.beginEdit()
        assertFailsWith<IllegalArgumentException> { editor.replaceDraft(listOf(band(0))) }
        editor.replaceDraft(replacement)
        editor.endEdit()
        runCurrent()
        assertEquals(replacement, editor.state.value.lastSent)
        editor.beginEdit()
        editor.replaceDraft(editor.state.value.draft.map { it.copy(gainRaw = 0) })
        editor.endEdit()
        runCurrent()
        assertEquals(listOf(0, 0), device.writes.last().bands.map { it.gainRaw })
        editor.undo()
        runCurrent()

        assertEquals(4, device.writes.size)
        assertEquals(replacement, device.writes.last().bands)
        assertEquals(replacement, editor.state.value.draft)
        assertEquals(replacement, editor.state.value.lastSent)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertFalse(editor.state.value.canUndo)
        assertEquals(1, device.reads)
        editor.close()
    }

    @Test
    fun failureAfterSuccessfulSendClearsLastSentAndRequiresExplicitReload() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(60)
        editor.endEdit()
        runCurrent()
        assertEquals(60, editor.state.value.lastSent?.single()?.gainRaw)
        val failure = DropException.Rejected(1, "PEQ")
        device.nextWriteFailure = failure
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()

        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertEquals(failure, editor.state.value.error)
        assertNull(editor.state.value.confirmed)
        assertNull(editor.state.value.lastSent)
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        assertFalse(editor.state.value.canUndo)
        editor.beginEdit()
        editor.changeGain(240)
        editor.undo()
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(2, device.writes.size)
        assertEquals(1, device.reads)
        assertEquals(180, editor.state.value.draft.single().gainRaw)
        editor.refresh()
        runCurrent()
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(device.stored, editor.state.value.confirmed)
        assertEquals(60, editor.state.value.draft.single().gainRaw)
        assertNull(editor.state.value.lastSent)
        assertEquals(2, device.reads)
        editor.close()
    }

    @Test
    fun cancelledDeviceSendStopsAutomationButActiveEditorCanReload() = runTest {
        val failure = CancellationException("EQ send cancelled")
        val device = device().apply { nextWriteFailure = failure }
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()

        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertEquals(failure, editor.state.value.error)
        assertNull(editor.state.value.confirmed)
        assertNull(editor.state.value.lastSent)
        assertFalse(editor.state.value.canUndo)
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)
        assertEquals(1, device.canceledWrites)
        editor.refresh()
        runCurrent()
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(device.stored, editor.state.value.confirmed)
        assertEquals(0, editor.state.value.draft.single().gainRaw)
        assertEquals(2, device.reads)
        editor.close()
    }

    @Test
    fun failedManualReadFromSentDiscardsSentBaselineAndRequiresAnotherExplicitRead() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        val failure = DropException.Timeout("EQ manual read")
        device.nextReadFailure = failure
        editor.refresh()
        runCurrent()

        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertEquals(failure, editor.state.value.error)
        assertNull(editor.state.value.confirmed)
        assertNull(editor.state.value.lastSent)
        assertTrue(editor.state.value.draft.isEmpty())
        assertFalse(editor.state.value.canUndo)
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(2, device.reads)
        assertEquals(1, device.writes.size)
        editor.refresh()
        runCurrent()
        assertEquals(3, device.reads)
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(device.stored, editor.state.value.confirmed)
        assertNull(editor.state.value.lastSent)
        editor.close()
    }

    @Test
    fun manualFullEditingSessionKeepsEveryOperationLocalUntilExplicitSubmit() = runTest {
        val initial = listOf(band(0, gainRaw = 119), band(1, gainRaw = -33))
        val device = device(initial)
        val editor = editor(device)
        runCurrent()
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        val edited = listOf(
            initial[0].copy(frequencyHz = 1122, gainRaw = 181, qRaw = 4097, filter = PeqFilter.LOW_SHELF),
            initial[1].copy(frequencyHz = 2233, gainRaw = -91, qRaw = 5001),
        )
        editor.beginEdit()
        for (band in edited) {
            editor.editBand(band)
            editor.flush()
            editor.submit() // An active gesture is not a submission boundary.
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertTrue(device.writes.isEmpty())
        }
        assertFalse(editor.state.value.canSubmit)
        editor.endEdit()
        assertTrue(editor.state.value.canSubmit)
        assertTrue(editor.state.value.canUndo)

        editor.beginEdit()
        editor.replaceDraft(initial) // Reset all fields, not just displayed gain.
        editor.endEdit()
        editor.undo()
        assertEquals(edited, editor.state.value.draft)
        editor.beginEdit()
        editor.replaceDraft(editor.state.value.draft.map { it.copy(gainRaw = 0) })
        editor.endEdit()
        editor.undo()
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()

        assertEquals(edited, editor.state.value.draft)
        assertTrue(device.writes.isEmpty())
        assertEquals(1, device.reads)
        editor.submit()
        assertFalse(editor.state.value.canSubmit)
        runCurrent()
        assertEquals(listOf(edited), device.writes.map { it.bands })
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        assertEquals(1, device.reads)
        editor.close()
    }

    @Test
    fun manualReadyCanSubmitIdenticalSnapshotOnceWithoutInventingReadback() = runTest {
        val initial = listOf(band(0).copy(gainRaw = 119, qRaw = 4097))
        val device = device(initial).apply { applyWrites = false }
        val editor = editor(device)
        runCurrent()
        assertEquals(ParamEqSubmitMode.REALTIME, editor.state.value.submitMode)
        assertFalse(editor.state.value.canSubmit)
        editor.submit()
        assertTrue(device.writes.isEmpty())
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        assertTrue(editor.state.value.canSubmit)
        editor.submit()
        assertEquals(ParamEqEditPhase.WRITING, editor.state.value.phase)
        assertFalse(editor.state.value.canSubmit)
        assertFalse(editor.state.value.canChangeSubmitMode)
        repeat(3) {
            editor.submit()
            editor.flush()
            editor.endEdit()
        }
        runCurrent()
        assertEquals(listOf(initial), device.writes.map { it.bands })
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertFalse(editor.state.value.canSubmit)
        assertNull(editor.state.value.confirmed)
        assertEquals(initial, editor.state.value.lastSent)
        editor.beginEdit()
        editor.replaceDraft(initial)
        editor.endEdit()
        editor.submit()
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)
        assertEquals(1, device.reads)

        editor.refresh()
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        runCurrent()
        assertTrue(editor.state.value.canSubmit)
        editor.submit()
        runCurrent()
        assertEquals(listOf(initial, initial), device.writes.map { it.bands })
        assertEquals(2, device.reads)
        editor.close()
    }

    @Test
    fun manualQueuedAndInflightSubmissionFreezesSnapshotAndRejectsDuplicateActions() = runTest {
        val device = device(listOf(band(0), band(1))).apply { writeDelayMillis = 200 }
        val editor = editor(device)
        runCurrent()
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        val candidate = mutableListOf(
            band(0).copy(gainRaw = 119, qRaw = 4097),
            band(1).copy(gainRaw = -33, frequencyHz = 0, qRaw = 0, filter = PeqFilter.BYPASS),
        )
        val submitted = candidate.toList()
        editor.beginEdit()
        editor.replaceDraft(candidate)
        editor.endEdit()
        assertTrue(editor.state.value.canUndo)
        editor.submit()
        candidate[0] = band(0, gainRaw = 240)

        repeat(2) { step ->
            editor.submit()
            editor.beginEdit()
            editor.changeGain(300)
            editor.replaceDraft(listOf(band(0), band(1)))
            editor.undo()
            editor.endEdit()
            editor.flush()
            editor.refresh()
            editor.setSubmitMode(ParamEqSubmitMode.REALTIME)
            assertEquals(submitted, editor.state.value.draft)
            assertFalse(editor.state.value.isEditing)
            assertFalse(editor.state.value.canUndo)
            assertFalse(editor.state.value.canSubmit)
            assertFalse(editor.state.value.canChangeSubmitMode)
            assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
            if (step == 0) runCurrent() else advanceTimeBy(200.milliseconds)
        }
        runCurrent()
        repeat(3) {
            editor.submit()
            editor.flush()
            editor.endEdit()
            runCurrent()
            advanceTimeBy(200.milliseconds)
        }
        runCurrent()
        assertEquals(listOf(submitted), device.writes.map { it.bands })
        assertEquals(submitted, device.stored.bands)
        assertEquals(submitted, editor.state.value.lastSent)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertFalse(editor.state.value.canSubmit)
        assertEquals(1, device.reads)
        assertEquals(1, device.maxConcurrentWrites)
        assertEquals(0, device.canceledWrites)
        editor.close()
    }

    @Test
    fun manualEditAndUndoAfterSentRequireSeparateExplicitSubmissions() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        editor.submit()
        runCurrent()
        editor.beginEdit()
        editor.changeGain(119)
        editor.submit()
        assertEquals(1, device.writes.size)
        editor.endEdit()
        assertEquals(ParamEqEditPhase.PENDING, editor.state.value.phase)
        assertTrue(editor.state.value.canSubmit)
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)
        editor.submit()
        runCurrent()
        assertFalse(editor.state.value.canSubmit)
        assertTrue(editor.state.value.canUndo)
        editor.undo()
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(2, device.writes.size)
        assertEquals(0, editor.state.value.draft.single().gainRaw)
        assertEquals(119, device.stored.bands.single().gainRaw)
        assertTrue(editor.state.value.canSubmit)
        editor.submit()
        runCurrent()
        assertEquals(listOf(0, 119, 0), device.writes.map { it.bands.single().gainRaw })
        assertEquals(1, device.reads)
        editor.close()
    }

    @Test
    fun manualDirtyRefreshDiscardsDraftAndUndoWithoutSendingAndRetainsMode() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        editor.beginEdit()
        editor.changeGain(180)
        editor.refresh()
        assertTrue(editor.state.value.isEditing)
        assertEquals(1, device.reads)
        editor.endEdit()
        assertTrue(editor.state.value.canUndo)
        assertEquals(ParamEqEditPhase.PENDING, editor.state.value.phase)
        editor.flush()
        editor.refresh()
        assertEquals(ParamEqEditPhase.LOADING, editor.state.value.phase)
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        assertFalse(editor.state.value.canUndo)
        assertFalse(editor.state.value.canSubmit)
        assertFalse(editor.state.value.canChangeSubmitMode)
        assertTrue(editor.state.value.draft.isEmpty())
        editor.submit()
        runCurrent()
        editor.undo()
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(2, device.reads)
        assertTrue(device.writes.isEmpty())
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        assertEquals(device.stored.bands, editor.state.value.draft)
        assertFalse(editor.state.value.canUndo)
        assertTrue(editor.state.value.canSubmit)
        editor.close()
    }

    @Test
    fun manualSubmissionFailureGatesAllSendsUntilExplicitActualReload() = runTest {
        val failure = DropException.Timeout("manual EQ submit")
        val device = device().apply {
            nextWriteFailure = failure
            failAfterApply = true
        }
        val editor = editor(device)
        runCurrent()
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        editor.beginEdit()
        editor.changeGain(181)
        editor.endEdit()
        editor.submit()
        runCurrent()
        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        assertEquals(failure, editor.state.value.error)
        assertFalse(editor.state.value.canSubmit)
        assertFalse(editor.state.value.canChangeSubmitMode)
        assertFalse(editor.state.value.canUndo)
        assertNull(editor.state.value.confirmed)
        assertNull(editor.state.value.lastSent)
        assertEquals(181, editor.state.value.draft.single().gainRaw)
        assertEquals(181, device.stored.bands.single().gainRaw)
        editor.submit()
        editor.setSubmitMode(ParamEqSubmitMode.REALTIME)
        editor.beginEdit()
        editor.changeGain(240)
        editor.undo()
        editor.endEdit()
        editor.flush()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)
        assertEquals(1, device.reads)
        assertEquals(181, editor.state.value.draft.single().gainRaw)

        editor.refresh()
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        runCurrent()
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(device.stored, editor.state.value.confirmed)
        assertEquals(181, editor.state.value.draft.single().gainRaw)
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        assertTrue(editor.state.value.canSubmit)
        editor.submit()
        runCurrent()
        assertEquals(2, device.writes.size)
        assertEquals(2, device.reads)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertNull(editor.state.value.confirmed)
        editor.close()
    }

    @Test
    fun manualReadFailureAndUnavailableReloadRetainModeWithoutAutomaticRecovery() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        editor.changeGain(119)
        val failure = DropException.Timeout("manual EQ reload")
        device.nextReadFailure = failure
        editor.refresh()
        runCurrent()
        assertEquals(ParamEqEditPhase.FAILED, editor.state.value.phase)
        assertEquals(failure, editor.state.value.error)
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        assertTrue(editor.state.value.draft.isEmpty())
        assertFalse(editor.state.value.canSubmit)
        editor.submit()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(2, device.reads)
        assertTrue(device.writes.isEmpty())
        device.available = false
        editor.refresh()
        runCurrent()
        assertEquals(ParamEqEditPhase.UNAVAILABLE, editor.state.value.phase)
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        assertFalse(editor.state.value.canSubmit)
        assertFalse(editor.state.value.canChangeSubmitMode)
        device.available = true
        editor.refresh()
        runCurrent()
        assertEquals(ParamEqEditPhase.READY, editor.state.value.phase)
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        assertTrue(editor.state.value.canSubmit)
        assertEquals(4, device.reads)
        assertTrue(device.writes.isEmpty())
        editor.close()
    }

    @Test
    fun modeChangesAreSettledOnlyAndDoNotTurnManualDraftIntoRealtimeWrite() = runTest {
        val device = device()
        val editor = editor(device)
        assertFalse(editor.state.value.canChangeSubmitMode)
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        runCurrent()
        assertEquals(ParamEqSubmitMode.REALTIME, editor.state.value.submitMode)
        assertTrue(editor.state.value.canChangeSubmitMode)
        editor.beginEdit()
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        assertFalse(editor.state.value.canChangeSubmitMode)
        assertEquals(ParamEqSubmitMode.REALTIME, editor.state.value.submitMode)
        editor.endEdit()
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        editor.changeGain(119)
        assertFalse(editor.state.value.canChangeSubmitMode)
        editor.setSubmitMode(ParamEqSubmitMode.REALTIME)
        editor.flush()
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals(ParamEqSubmitMode.MANUAL, editor.state.value.submitMode)
        assertTrue(device.writes.isEmpty())
        editor.submit()
        runCurrent()
        assertTrue(editor.state.value.canChangeSubmitMode)
        editor.setSubmitMode(ParamEqSubmitMode.REALTIME)
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        editor.flush()
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals(1, device.writes.size)
        editor.setSubmitMode(ParamEqSubmitMode.REALTIME)
        editor.beginEdit()
        editor.changeGain(180)
        editor.endEdit()
        runCurrent()
        assertEquals(listOf(119, 180), device.writes.map { it.bands.single().gainRaw })
        editor.close()
    }

    @Test
    fun switchingAtSettledDraftNeutralizesDelayedRealtimeWakeBeforeManualSubmit() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.changeGain(60)
        runCurrent()
        advanceTimeBy(10.milliseconds)
        editor.changeGain(120)
        runCurrent() // The realtime worker is waiting for its next 150ms start.
        editor.changeGain(60)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        editor.beginEdit()
        editor.changeGain(181)
        editor.endEdit()
        editor.flush()
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(listOf(60), device.writes.map { it.bands.single().gainRaw })
        assertEquals(ParamEqEditPhase.PENDING, editor.state.value.phase)
        editor.submit()
        editor.submit()
        editor.flush()
        runCurrent()
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(listOf(60, 181), device.writes.map { it.bands.single().gainRaw })
        assertEquals(1, device.reads)
        editor.close()
    }

    @Test
    fun manualSubmitWakesAnOldRealtimeThrottleOnceWithoutWaitingOrReplayingIt() = runTest {
        val device = device()
        val editor = editor(device)
        runCurrent()
        editor.changeGain(60)
        runCurrent()
        advanceTimeBy(10.milliseconds)
        editor.changeGain(120)
        runCurrent()
        editor.changeGain(60)
        editor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        editor.changeGain(181)
        editor.submit()
        editor.submit()
        editor.flush()
        runCurrent()
        assertEquals(listOf(0L, 10L), device.writes.map { it.startedAt })
        assertEquals(listOf(60, 181), device.writes.map { it.bands.single().gainRaw })
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(2, device.writes.size)
        assertEquals(ParamEqEditPhase.SENT, editor.state.value.phase)
        assertFalse(editor.state.value.canSubmit)
        editor.close()
    }

    @Test
    fun closingManualEditorDiscardsQueuedSubmitAndNewLifetimeDefaultsToRealtime() = runTest {
        val oldDevice = device()
        val oldEditor = editor(oldDevice)
        runCurrent()
        oldEditor.setSubmitMode(ParamEqSubmitMode.MANUAL)
        oldEditor.changeGain(181)
        oldEditor.submit()
        oldEditor.close()
        oldEditor.submit()
        oldEditor.endEdit()
        oldEditor.flush()
        oldEditor.refresh()
        oldEditor.setSubmitMode(ParamEqSubmitMode.REALTIME)
        val newDevice = device()
        val newEditor = editor(newDevice)
        runCurrent()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertTrue(oldDevice.writes.isEmpty())
        assertTrue(newDevice.writes.isEmpty())
        assertEquals(ParamEqEditPhase.UNAVAILABLE, oldEditor.state.value.phase)
        assertTrue(oldEditor.state.value.draft.isEmpty())
        assertFalse(oldEditor.state.value.canSubmit)
        assertFalse(oldEditor.state.value.canChangeSubmitMode)
        assertEquals(ParamEqSubmitMode.REALTIME, newEditor.state.value.submitMode)
        newEditor.beginEdit()
        newEditor.changeGain(60)
        newEditor.endEdit()
        runCurrent()
        assertEquals(listOf(60), newDevice.writes.map { it.bands.single().gainRaw })
        newEditor.close()
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

