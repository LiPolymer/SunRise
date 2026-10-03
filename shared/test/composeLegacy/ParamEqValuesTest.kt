package ink.lipoly.app.sunrise.composeLegacy

import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.PeqFilter
import kotlin.math.abs
import kotlin.test.*
import org.junit.Test

class ParamEqValuesTest {
    private fun band(gain: Int = 0, q: Int = 4096, filter: PeqFilter = PeqFilter.PEAKING, frequency: Int = 1000, index: Int = 0) =
        GaiaPeqBand(index = index, frequencyHz = frequency, gainRaw = gain, qRaw = q, filter = filter)

    @Test fun logarithmicFrequencyEndpointsAndCenter() {
        assertEquals(20, peqFrequencyAt(0f))
        assertEquals(632, peqFrequencyAt(0.5f))
        assertEquals(20000, peqFrequencyAt(1f))
        assertEquals(0f, peqFrequencyFraction(20))
        assertEquals(1f, peqFrequencyFraction(20000))
        for (hz in listOf(20.0, 100.0, 1000.0, 20000.0)) {
            assertEquals(hz, peqXFrequency(peqFrequencyX(hz, 320.0), 320.0), 1e-8)
        }
        assertEquals(0.0, peqFrequencyX(20.0, 320.0))
        assertEquals(320.0, peqFrequencyX(20000.0, 320.0))
    }

    @Test fun coordinateBoundsStayFinite() {
        for (width in listOf(-1.0, 0.0, 320.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            for (coordinate in listOf(-1e9, 1e9, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
                assertTrue(peqXFrequency(coordinate, width).isFinite())
                assertTrue(peqFrequencyX(coordinate, width).isFinite())
            }
        }
        assertEquals(20.0, peqXFrequency(-1e9, 320.0))
        assertEquals(20000.0, peqXFrequency(1e9, 320.0), 1e-8)
    }

    @Test fun gainSliderAndNudgesUseRawSixSteps() {
        assertEquals(-720, peqGainAt(0f))
        assertEquals(0, peqGainAt(0.5f))
        assertEquals(720, peqGainAt(1f))
        for (raw in -720..720 step 6) assertEquals(raw, peqGainAt(peqGainFraction(raw)))
        assertEquals(6, peqNudge(band(), PeqParameter.GAIN, 1).gainRaw)
        assertEquals(-6, peqNudge(band(), PeqParameter.GAIN, -1).gainRaw)
        assertEquals(720, peqNudge(band(gain = 720), PeqParameter.GAIN, 1).gainRaw)
        assertEquals(-720, peqNudge(band(gain = -720), PeqParameter.GAIN, -1).gainRaw)
    }

    @Test fun qSliderUsesQuantizedLowerBoundary() {
        assertEquals(409, peqQAt(0f))
        assertEquals(65535, peqQAt(1f))
        assertEquals(0f, peqQFraction(409))
        assertEquals(1f, peqQFraction(65535))
        val raw = peqParseInput("0.1", band(), PeqParameter.Q)
        assertEquals(409, raw)
        assertTrue(peqSliderInRange(band(q = raw), PeqParameter.Q))
        assertFalse(peqSliderInRange(band(q = 408), PeqParameter.Q))
        assertFalse(peqSliderInRange(band(gain = 721), PeqParameter.GAIN))
        for (i in 0..1000) assertTrue(peqQAt(i / 1000f) in 409..65535)
    }

    @Test fun preciseUnmodifiedUnitInputPreservesRawBits() {
        val original = band(gain = 119, q = 4097)
        assertEquals(119, peqParseInput(peqInputText(original, PeqParameter.GAIN), original, PeqParameter.GAIN))
        assertEquals(4097, peqParseInput(peqInputText(original, PeqParameter.Q), original, PeqParameter.Q))
        assertEquals(119, peqParseInput(" ${original.gainDb} ", original, PeqParameter.GAIN))
        assertEquals(4097, peqParseInput(original.q.toString().replace('.', ','), original, PeqParameter.Q))
        val extendedGain = band(gain = 900)
        // Opening and confirming an out-of-slider readback must not silently clip it.
        assertEquals(900, peqParseInput(peqInputText(extendedGain, PeqParameter.GAIN), extendedGain, PeqParameter.GAIN))
        assertEquals(0, peqParseInput("0", extendedGain, PeqParameter.GAIN))
        val bypass = band(q = 0, filter = PeqFilter.BYPASS, frequency = 0)
        assertEquals(0, peqParseInput(peqInputText(bypass, PeqParameter.FREQUENCY), bypass, PeqParameter.FREQUENCY))
        assertEquals(0, peqParseInput("00", bypass, PeqParameter.FREQUENCY))
        assertEquals(0, peqParseInput(peqInputText(bypass, PeqParameter.Q), bypass, PeqParameter.Q))
    }

    @Test fun numericInputAcceptsDecimalCommaButNotGroupingOrNonfinite() {
        assertEquals(-90, peqParseInput("-1,5", band(), PeqParameter.GAIN))
        assertEquals(-90, peqParseInput("-1.5", band(), PeqParameter.GAIN))
        assertEquals(4096, peqParseInput("1,000", band(), PeqParameter.Q))
        assertEquals(120, peqParseInput("+2", band(), PeqParameter.GAIN))
        assertEquals(1, peqParseInput((1.0 / 4096).toString(), band(), PeqParameter.Q))
        assertEquals(65535, peqParseInput((65535.0 / 4096).toString(), band(), PeqParameter.Q))
        for (text in listOf("", "NaN", "Infinity", "1,2.3", "1,2,3", "1 000", "1e3", "--1", "12.01", "-12.01")) {
            assertFailsWith<IllegalArgumentException> { peqParseInput(text, band(), PeqParameter.GAIN) }
        }
        for (text in listOf("0", "-1", "16", "0.0001")) {
            assertFailsWith<IllegalArgumentException> { peqParseInput(text, band(), PeqParameter.Q) }
        }
        for (text in listOf("19", "20001", "1000.0", "1,000", "-20", "NaN")) {
            assertFailsWith<IllegalArgumentException> { peqParseInput(text, band(), PeqParameter.FREQUENCY) }
        }
        assertEquals(20000, peqParseInput("20000", band(), PeqParameter.FREQUENCY))
    }

    @Test fun otherFieldEditsDoNotRoundReadback() {
        val original = band(gain = 119, q = 4097)
        val changed = peqWithRaw(original, PeqParameter.FREQUENCY, 2000)
        assertEquals(119, changed.gainRaw)
        assertEquals(4097, changed.qRaw)
        assertEquals(2000, changed.frequencyHz)
        val nudged = peqNudge(original, PeqParameter.FREQUENCY, 1)
        assertEquals(119, nudged.gainRaw)
        assertEquals(4097, nudged.qRaw)
    }

    @Test fun resetFlattenAndExplicitEnablingPreserveUnchangedFields() {
        val original = band(gain = 119, q = 4097, index = 3, frequency = 250)
        val reset = peqReset(original)
        assertEquals(3, reset.index)
        assertEquals(250, reset.frequencyHz)
        assertEquals(0, reset.gainRaw)
        assertEquals(4096, reset.qRaw)
        val bypass = band(gain = -90, q = 0, filter = PeqFilter.BYPASS, frequency = 0, index = 4)
        assertEquals(bypass, peqEnableFilter(bypass, PeqFilter.BYPASS))
        val enabled = peqEnableFilter(bypass, PeqFilter.PEAKING)
        assertEquals(1000, enabled.frequencyHz)
        assertEquals(4096, enabled.qRaw)
        assertEquals(-90, enabled.gainRaw)
        val flattened = peqFlatten(listOf(original, bypass))
        assertEquals(listOf(3, 4), flattened.map { it.index })
        assertEquals(listOf(250, 1000), flattened.map { it.frequencyHz })
        assertEquals(listOf(4097, 4096), flattened.map { it.qRaw })
        assertTrue(flattened.all { it.filter == PeqFilter.PEAKING && it.gainRaw == 0 })
        assertEquals(119, original.gainRaw)
        assertEquals(0, bypass.frequencyHz)
        assertEquals(15.0, peqAxisDb(listOf(band(gain = 721))))
    }

    @Test fun bellCenterAndInverseResponses() {
        val positive = PeqBiquad.of(band(gain = 120))
        val negative = PeqBiquad.of(band(gain = -120))
        assertEquals(2.0, positive.responseDb(1000.0), 1e-9)
        for (hz in listOf(20.0, 500.0, 1000.0, 2000.0, 20000.0)) {
            assertEquals(0.0, positive.responseDb(hz) + negative.responseDb(hz), 1e-9)
        }
        val wide = PeqBiquad.of(band(gain = 360, q = 4096))
        val narrow = PeqBiquad.of(band(gain = 360, q = 16384))
        assertTrue(wide.responseDb(1500.0) > narrow.responseDb(1500.0))
    }

    @Test fun shelvesPassesAndBypassHaveCorrectDirections() {
        val lowShelf = PeqBiquad.of(band(gain = 360, filter = PeqFilter.LOW_SHELF))
        val highShelf = PeqBiquad.of(band(gain = 360, filter = PeqFilter.HIGH_SHELF))
        assertEquals(6.0, lowShelf.responseDb(20.0), 0.01)
        assertEquals(0.0, lowShelf.responseDb(20000.0), 0.01)
        assertEquals(0.0, highShelf.responseDb(20.0), 0.01)
        assertEquals(6.0, highShelf.responseDb(20000.0), 0.01)
        val lowPass = PeqBiquad.of(band(filter = PeqFilter.LOW_PASS))
        val highPass = PeqBiquad.of(band(filter = PeqFilter.HIGH_PASS))
        assertTrue(lowPass.responseDb(20.0) > lowPass.responseDb(20000.0))
        assertTrue(highPass.responseDb(20.0) < highPass.responseDb(20000.0))
        val bypass = PeqBiquad.of(band(gain = 900, q = 0, filter = PeqFilter.BYPASS, frequency = 0))
        for (hz in listOf(20.0, 1000.0, 20000.0)) assertEquals(0.0, bypass.responseDb(hz), 1e-12)
        assertEquals(lowPass.responseDb(4000.0), PeqBiquad.of(band(gain = 360, filter = PeqFilter.LOW_PASS)).responseDb(4000.0))
    }

    @Test fun qHandleBandwidthRoundTripAndDirection() {
        for (q in listOf(1.0 / 4096, 0.1, 1.0, 2.0, 65535.0 / 4096)) {
            assertEquals(q, peqQFromHalfBandwidth(peqHalfBandwidth(q)), 1e-10)
        }
        assertTrue(peqHalfBandwidth(2.0) < peqHalfBandwidth(1.0))
        assertTrue(abs(peqQFromHalfBandwidth(peqHalfBandwidth(1.0)) - 1.0) < 1e-12)
    }

    @Test fun relativeDragsDoNotJumpOrRoundUntouchedRawUnits() {
        assertEquals(1000, peqDraggedFrequency(1000, 0f, 320f))
        assertEquals(119, peqDraggedGain(119, 0f, 180f, 12.0))
        assertEquals(4097, peqDraggedQ(4097, 0f, 320f, 1))
        assertEquals(119, peqDraggedGain(119, Float.NaN, 180f, 12.0))
        assertEquals(20, peqDraggedFrequency(1000, -10000f, 320f))
        assertEquals(20000, peqDraggedFrequency(1000, 10000f, 320f))
        assertEquals(65535, peqDraggedQ(4096, -10000f, 320f, 1))
        assertEquals(1, peqDraggedQ(4096, 10000f, 320f, 1))
        assertTrue(peqDraggedQ(4096, 10f, 320f, 1) < 4096)
        assertTrue(peqDraggedQ(4096, -10f, 320f, -1) < 4096)
        assertTrue(peqDraggedGain(119, -10f, 180f, 12.0) > 119)
        assertTrue(peqDraggedFrequency(1000, 10f, 320f) > 1000)
    }
}
