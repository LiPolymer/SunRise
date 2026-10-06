package ink.lipoly.app.sunrise.drop

import kotlin.test.*

class PeqHeadroomTest {
    private fun band(index: Int, frequencyHz: Int, gainRaw: Int, qRaw: Int = 4096) =
        GaiaPeqBand(index, frequencyHz, gainRaw, qRaw, PeqFilter.PEAKING)

    @Test fun flatAndAllCutsNeverIntroducePositivePregain() {
        assertEquals(0, PeqHeadroom.preGainRaw(listOf(band(0, 1000, 0))))
        assertEquals(0, PeqHeadroom.preGainRaw(listOf(
            band(0, 500, -360),
            band(1, 1000, -360),
        )))
    }

    @Test fun boostAndOverlappingBoostsMatchOfficialTruncation() {
        assertEquals(-366, PeqHeadroom.preGainRaw(listOf(band(0, 1000, 360))))
        // The sum of the responses needs more headroom than either individual +6 dB band.
        // Official IEEE toward-zero conversion yields -491, not a rounded -492.
        assertEquals(-491, PeqHeadroom.preGainRaw(listOf(
            band(0, 500, 360),
            band(1, 1000, 360),
        )))
    }

    @Test fun officialFiveBandSnapshotUsesRepresentedRawParameters() {
        assertEquals(-168, PeqHeadroom.preGainRaw(listOf(
            band(0, 21, -138, 4505),
            band(1, 140, 120, 2867),
            band(2, 1600, 162, 8601),
            band(3, 3100, -102, 13516),
            band(4, 6100, -84, 19251),
        )))
    }

    @Test fun frequencyAndWidthEditsChangeCombinedHeadroomWithoutGainEdits() {
        val overlapping = listOf(band(0, 500, 360), band(1, 1000, 360))
        val narrower = overlapping.map { it.copy(qRaw = 8192) }
        val coincident = overlapping.map { it.copy(frequencyHz = 1000) }
        assertTrue(PeqHeadroom.preGainRaw(narrower) > -491,
            "Narrower separated peaks must require less attenuation")
        assertTrue(PeqHeadroom.preGainRaw(coincident) < -491,
            "Coincident peaks must require more attenuation")
    }

    @Test fun combinedHeadroomMustFitSigned16EvenWhenEachBandDoes() {
        val excessive = List(10) { band(it, 1000, 3600) }
        assertFailsWith<IllegalArgumentException> { PeqHeadroom.preGainRaw(excessive) }
    }
}
