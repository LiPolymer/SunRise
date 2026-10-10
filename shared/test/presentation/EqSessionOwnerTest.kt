package ink.lipoly.app.sunrise.presentation

import ink.lipoly.app.sunrise.compose.FancyEqualizer.ParamEqEditPhase
import ink.lipoly.app.sunrise.compose.FancyEqualizer.ParamEqSubmitMode
import ink.lipoly.app.sunrise.compose.StoredGaiaDevice
import ink.lipoly.app.sunrise.drop.GaiaControls
import ink.lipoly.app.sunrise.drop.GaiaParamEqState
import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.PeqFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class EqSessionOwnerTest {
    @Test
    fun repeatedBindingUsesTheSameEditorAndReadsControlsOnlyOnce() = runTest {
        val controls = device(17)
        val owner = EqSessionOwner(backgroundScope, testScheduler.timeSource)

        val editor = owner.bind(controls)
        assertSame(editor, owner.bind(controls))
        runCurrent()

        assertEquals(1, controls.reads)
        assertEquals(ParamEqEditPhase.READY, editor?.state?.value?.phase)
        assertEquals(ParamEqSubmitMode.REALTIME, editor?.state?.value?.submitMode)
        assertEquals(controls.stored.bands, editor?.state?.value?.draft)
        owner.close()
    }

    @Test
    fun equalButDistinctControlsHaveDistinctEditorsBecauseBindingUsesIdentity() = runTest {
        val firstControls = EqualControls(device(21))
        val secondControls = EqualControls(device(82))
        val owner = EqSessionOwner(backgroundScope, testScheduler.timeSource)
        assertEquals(firstControls, secondControls)

        val firstEditor = owner.bind(firstControls)!!
        runCurrent()
        val secondEditor = owner.bind(secondControls)!!
        runCurrent()

        assertNotSame(firstEditor, secondEditor)
        assertEquals(1, firstControls.delegate.reads)
        assertEquals(1, secondControls.delegate.reads)
        assertEquals(secondControls.delegate.stored.bands, secondEditor.state.value.draft)
        owner.close()
    }

    @Test
    fun replacingControlsCancelsTheOldEditorsDelayedWriteAndLoadsNewDeviceState() = runTest {
        val oldControls = device(17).apply { writeDelayMillis = 1_000 }
        val newControls = device(93)
        val owner = EqSessionOwner(backgroundScope, testScheduler.timeSource)
        val oldEditor = owner.bind(oldControls)!!
        runCurrent()
        oldEditor.editBand(oldEditor.state.value.draft.single().copy(gainRaw = 180))
        oldEditor.flush()
        runCurrent()
        assertEquals(1, oldControls.writes.size)
        assertEquals(0, oldControls.canceledWrites)

        val newEditor = owner.bind(newControls)!!
        assertEquals(ParamEqEditPhase.UNAVAILABLE, oldEditor.state.value.phase)
        runCurrent()
        advanceTimeBy(1_500.milliseconds)
        runCurrent()

        assertEquals(1, oldControls.canceledWrites)
        assertEquals(1, oldControls.writes.size)
        assertEquals(17, oldControls.stored.bands.single().gainRaw)
        assertEquals(1, newControls.reads)
        assertEquals(ParamEqEditPhase.READY, newEditor.state.value.phase)
        assertEquals(newControls.stored.bands, newEditor.state.value.draft)
        assertEquals(ParamEqSubmitMode.REALTIME, newEditor.state.value.submitMode)
        assertTrue(newControls.writes.isEmpty())

        oldEditor.editBand(band(240))
        oldEditor.flush()
        runCurrent()
        assertEquals(1, oldControls.writes.size)
        owner.close()
    }

    @Test
    fun nullBindingAndCloseRetireEditorsAndNeverReviveTheOwner() = runTest {
        val controls = device(12).apply { writeDelayMillis = 700 }
        val nextControls = device(71).apply { writeDelayMillis = 700 }
        val owner = EqSessionOwner(backgroundScope, testScheduler.timeSource)
        val retired = owner.bind(controls)!!
        runCurrent()
        retired.editBand(retired.state.value.draft.single().copy(gainRaw = 100))
        retired.flush()
        runCurrent()
        assertEquals(1, controls.writes.size)

        assertNull(owner.bind(null))
        runCurrent()
        assertEquals(1, controls.canceledWrites)
        assertEquals(ParamEqEditPhase.UNAVAILABLE, retired.state.value.phase)

        val current = owner.bind(nextControls)!!
        assertSame(current, owner.bind(nextControls))
        runCurrent()
        assertEquals(1, nextControls.reads)
        assertEquals(ParamEqSubmitMode.REALTIME, current.state.value.submitMode)
        current.editBand(current.state.value.draft.single().copy(gainRaw = 61))
        current.flush()
        runCurrent()
        assertEquals(listOf(61), nextControls.writes.map { it.bands.single().gainRaw })

        owner.close()
        owner.close()
        assertNull(owner.bind(nextControls))
        assertNull(owner.bind(controls))
        current.editBand(band(240))
        current.flush()
        runCurrent()
        advanceTimeBy(1_000.milliseconds)
        runCurrent()
        assertEquals(1, nextControls.canceledWrites)
        assertEquals(listOf(61), nextControls.writes.map { it.bands.single().gainRaw })
    }

    private fun TestScope.device(gainRaw: Int): StoredGaiaDevice {
        val band = band(gainRaw)
        return StoredGaiaDevice(
            initial = GaiaParamEqState(listOf(0, 2, 63), 2, 0, listOf(band)),
            now = { testScheduler.currentTime },
        )
    }

    private fun band(gainRaw: Int) =
        GaiaPeqBand(0, 1_000, gainRaw, 4_096, PeqFilter.PEAKING)

    private class EqualControls(val delegate: StoredGaiaDevice) : GaiaControls by delegate {
        override fun equals(other: Any?): Boolean = other is EqualControls
        override fun hashCode(): Int = 0
    }
}
