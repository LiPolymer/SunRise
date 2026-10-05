package ink.lipoly.app.sunrise.catalog

import ink.lipoly.app.sunrise.drop.GaiaPeqBand
import ink.lipoly.app.sunrise.drop.PeqFilter
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.*

class CatalogEqResponseTest {
    private fun reference(vararg points: Pair<Double, Double>) = FrequencyResponse(
        DoubleArray(points.size) { points[it].first }, DoubleArray(points.size) { points[it].second },
    )

    private fun band(index: Int = 0, frequencyHz: Int = 1000, gainRaw: Int = 0, qRaw: Int = 4096) =
        GaiaPeqBand(index, frequencyHz, gainRaw, qRaw, PeqFilter.PEAKING)

    @Test fun referenceInterpolationAndPredictionShareThe500HzNormalization() {
        val response = reference(100.0 to 40.0, 1000.0 to 60.0)
        assertEquals(50.0, interpolateCatalogSpl(response, sqrt(100.0 * 1000.0)), 1e-12)
        val offset = 40.0 + 20.0 * ln(5.0) / ln(10.0)
        val overlay = buildAcousticOverlay(response, listOf(band()), false)
        assertEquals(500.0, overlay.normalizationHz)
        assertNull(overlay.predictionError)
        val predicted = assertNotNull(overlay.predictedDb)
        for (index in overlay.frequencyHz.indices) {
            assertEquals(interpolateCatalogSpl(response, overlay.frequencyHz[index]) - offset, overlay.referenceDb[index], 1e-12)
            assertEquals(overlay.referenceDb[index], predicted[index], 1e-8)
        }
    }

    @Test fun centerBoostIsSixDbWithoutPregainAndMinusPointOneWithWriterPregain() {
        // The measured endpoint includes the filter's exact center, not a near-center grid point.
        val response = reference(20.0 to 80.0, 1000.0 to 80.0)
        val bands = listOf(band(gainRaw = 360))
        val uncompensated = buildAcousticOverlay(response, bands, false)
        val compensated = buildAcousticOverlay(response, bands, true)
        assertNull(uncompensated.includedPreGainRaw)
        assertEquals(-366, compensated.includedPreGainRaw)
        assertNull(compensated.predictionError)
        val rawPrediction = assertNotNull(uncompensated.predictedDb)
        val prediction = assertNotNull(compensated.predictedDb)
        assertEquals(6.0, rawPrediction.last() - uncompensated.referenceDb.last(), 1e-6)
        assertEquals(-0.1, prediction.last() - compensated.referenceDb.last(), 1e-6)
        assertContentEquals(uncompensated.referenceDb, compensated.referenceDb)
        for (index in prediction.indices) assertEquals(-366 / 60.0, prediction[index] - rawPrediction[index], 1e-8)
    }

    @Test fun overlappingBoostsUseWriterTruncationWithoutRenormalizingThePrediction() {
        val response = reference(20.0 to 75.0, 20000.0 to 85.0)
        val bands = listOf(band(0, 500, 360), band(1, 1000, 360))
        val raw = buildAcousticOverlay(response, bands, false)
        val compensated = buildAcousticOverlay(response, bands, true)
        assertEquals(-491, compensated.includedPreGainRaw)
        assertContentEquals(raw.referenceDb, compensated.referenceDb)
        val rawPrediction = assertNotNull(raw.predictedDb)
        val prediction = assertNotNull(compensated.predictedDb)
        for (index in prediction.indices) assertEquals(-491 / 60.0, prediction[index] - rawPrediction[index], 1e-8)
    }

    @Test fun ultraEndpointIsExactAndNeverExtrapolatesTo20k() {
        val endpoint = 19896.974609
        val overlay = buildAcousticOverlay(reference(20.0 to 80.0, endpoint to 90.0), listOf(band()), false)
        assertEquals(20.0, overlay.frequencyHz.first(), 0.0)
        assertEquals(endpoint, overlay.frequencyHz.last(), 0.0)
        assertTrue(overlay.frequencyHz.all { it in 20.0..endpoint })
        assertTrue((1 until overlay.frequencyHz.size).all { overlay.frequencyHz[it - 1] < overlay.frequencyHz[it] })
        assertEquals(overlay.referenceDb.last(), assertNotNull(overlay.predictedDb).last(), 1e-8)
    }

    @Test fun no500HzCoverageRetainsRawSplInBothReferenceAndPrediction() {
        val overlay = buildAcousticOverlay(reference(600.0 to 70.0, 1000.0 to 80.0), listOf(band(gainRaw = 360)), false)
        assertNull(overlay.normalizationHz)
        assertEquals(70.0, overlay.referenceDb.first(), 0.0)
        assertEquals(80.0, overlay.referenceDb.last(), 0.0)
        assertEquals(86.0, assertNotNull(overlay.predictedDb).last(), 1e-6)
    }

    @Test fun absentAndEmptyDraftsNeverInventAPredictionOrPregain() {
        val response = reference(20.0 to 80.0, 20000.0 to 90.0)
        val expected = sampleCatalogResponse(response)
        for (bands in listOf(null, emptyList<GaiaPeqBand>())) for (includePreGain in listOf(false, true)) {
            val overlay = buildAcousticOverlay(response, bands, includePreGain)
            assertContentEquals(expected.frequencyHz, overlay.frequencyHz)
            assertContentEquals(expected.referenceDb, overlay.referenceDb)
            assertNull(overlay.predictedDb)
            assertNull(overlay.includedPreGainRaw)
            assertNull(overlay.predictionError)
        }
    }

    @Test fun writableFrequencyAndWidthBoundariesStillProducePredictions() {
        val response = reference(20.0 to 80.0, 20000.0 to 80.0)
        for (frequency in listOf(20, 20000)) for (q in listOf(1, 65535)) {
            val overlay = buildAcousticOverlay(response, listOf(band(frequencyHz = frequency, gainRaw = 360, qRaw = q)), false)
            assertNull(overlay.predictionError)
            assertTrue(assertNotNull(overlay.predictedDb).all { it.isFinite() })
        }
    }

    @Test fun invalidAndUnsupportedDraftsPreserveReferenceRegardlessOfPregainSwitch() {
        val response = reference(20.0 to 80.0, 20000.0 to 90.0)
        val expected = sampleCatalogResponse(response)
        val invalid = listOf(
            listOf(band(index = 1)),
            listOf(band(frequencyHz = 19)),
            listOf(band(frequencyHz = 20001)),
            listOf(band(gainRaw = 32768)),
            listOf(band(gainRaw = -32769)),
            listOf(band(qRaw = 0)),
            listOf(band(qRaw = 65536)),
            List(256) { band(index = it) },
        ) + PeqFilter.entries.filter { it != PeqFilter.PEAKING }.map { listOf(band().copy(filter = it)) }
        for (bands in invalid) for (includePreGain in listOf(false, true)) {
            val overlay = buildAcousticOverlay(response, bands, includePreGain)
            assertContentEquals(expected.frequencyHz, overlay.frequencyHz)
            assertContentEquals(expected.referenceDb, overlay.referenceDb)
            assertNull(overlay.predictedDb)
            assertNull(overlay.includedPreGainRaw)
            assertTrue(assertNotNull(overlay.predictionError).isNotBlank())
        }
    }

    @Test fun excessiveCompensationIsAnErrorNotZeroGainFallback() {
        val response = reference(20.0 to 80.0, 20000.0 to 80.0)
        val bands = List(10) { band(it, 1000, 3600) }
        val overlay = buildAcousticOverlay(response, bands, true)
        assertContentEquals(sampleCatalogResponse(response).referenceDb, overlay.referenceDb)
        assertNull(overlay.predictedDb)
        assertNull(overlay.includedPreGainRaw)
        assertTrue(assertNotNull(overlay.predictionError).isNotBlank())
        assertNotNull(buildAcousticOverlay(response, bands, false).predictedDb)
    }

    @Test fun finiteSourceValuesWhoseNormalizationOverflowsDoNotPublishNonfinitePrediction() {
        // Parsing accepts finite SPL; subtracting a finite opposite extreme can still overflow.
        val response = parseFrequencyResponse("20 1e308\n500 -1e308\n20000 1e308\n".encodeToByteArray())
        val expected = sampleCatalogResponse(response)
        assertEquals(Double.POSITIVE_INFINITY, expected.referenceDb.first())
        val overlay = buildAcousticOverlay(response, listOf(band()), false)
        assertContentEquals(expected.referenceDb, overlay.referenceDb)
        assertNull(overlay.predictedDb)
        assertNull(overlay.includedPreGainRaw)
        assertTrue(assertNotNull(overlay.predictionError).isNotBlank())
    }

    @Test fun emptyDisplayIntersectionReportsTheUnavailableRange() {
        val overlay = buildAcousticOverlay(reference(30000.0 to 70.0, 40000.0 to 80.0), listOf(band()), true)
        assertTrue(overlay.frequencyHz.isEmpty())
        assertTrue(overlay.referenceDb.isEmpty())
        assertNull(overlay.predictedDb)
        assertNull(overlay.includedPreGainRaw)
        assertTrue(assertNotNull(overlay.predictionError).isNotBlank())
        assertNull(acousticScale(overlay))
    }

    private fun scaleOverlay(reference: DoubleArray, prediction: DoubleArray? = null) = AcousticOverlay(
        DoubleArray(reference.size) { 20.0 + it }, reference, prediction, 500.0, null,
    )

    @Test fun rightScaleIncludesBothCurvesWithThreeDbPaddingAndOutwardSixDbRounding() {
        val scale = assertNotNull(acousticScale(scaleOverlay(doubleArrayOf(-7.0, 8.0), doubleArrayOf(-13.0, 16.0))))
        assertEquals(-18.0, scale.minDb, 0.0)
        assertEquals(24.0, scale.maxDb, 0.0)
        val boundary = assertNotNull(acousticScale(scaleOverlay(doubleArrayOf(-3.0, 3.0))))
        assertEquals(-6.0, boundary.minDb, 0.0)
        assertEquals(6.0, boundary.maxDb, 0.0)
        val outsideBoundary = assertNotNull(acousticScale(scaleOverlay(doubleArrayOf(-3.001, 3.001))))
        assertEquals(-12.0, outsideBoundary.minDb, 0.0)
        assertEquals(12.0, outsideBoundary.maxDb, 0.0)
    }

    @Test fun rightScaleHasAtLeastTwelveDbSpanForFlatData() {
        for (value in doubleArrayOf(0.0, 3.0, -3.0, 81.0)) {
            val scale = assertNotNull(acousticScale(scaleOverlay(doubleArrayOf(value))))
            assertTrue(scale.maxDb - scale.minDb >= 12.0)
            assertTrue(scale.minDb <= value - 3.0)
            assertTrue(scale.maxDb >= value + 3.0)
            assertEquals(0.0, scale.minDb % 6.0, 0.0)
            assertEquals(0.0, scale.maxDb % 6.0, 0.0)
        }
    }

    @Test fun rightScaleIgnoresNonfiniteValuesAndIsAbsentOnlyWithoutFiniteValues() {
        val overlay = scaleOverlay(doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY), doubleArrayOf(-7.0, 8.0, Double.NEGATIVE_INFINITY))
        assertEquals(AcousticScale(-12.0, 12.0), acousticScale(overlay))
        assertNull(acousticScale(scaleOverlay(doubleArrayOf(Double.NaN), doubleArrayOf(Double.POSITIVE_INFINITY))))
        assertNull(acousticScale(scaleOverlay(doubleArrayOf())))
    }

    @Test fun finiteExtremeSplKeepsTickAllocationWithinTheViewportLimit() {
        val scale = assertNotNull(acousticScale(scaleOverlay(doubleArrayOf(-1e300, 1e300))))
        for (limit in 2..20) {
            val ticks = acousticAxisTicks(scale, limit)
            assertTrue(ticks.size <= limit)
            assertEquals(scale.maxDb, ticks.first())
            assertEquals(scale.minDb, ticks.last())
            for (index in ticks.indices) {
                assertTrue(ticks[index].isFinite() && ticks[index] in scale.minDb..scale.maxDb)
                if (index > 0) assertTrue(ticks[index] < ticks[index - 1])
            }
        }
    }

    @Test fun unrepresentableFiniteAxisDoesNotProduceCollapsedOrInfiniteCoordinates() {
        assertNull(acousticScale(scaleOverlay(doubleArrayOf(1e300, 1e300))))
        assertNull(acousticScale(scaleOverlay(doubleArrayOf(-Double.MAX_VALUE, Double.MAX_VALUE))))
    }

    @Test fun ordinaryAxisTicksKeepSixDbStepsWhenSpaceAllows() {
        assertContentEquals(doubleArrayOf(12.0, 6.0, 0.0, -6.0, -12.0),
            acousticAxisTicks(AcousticScale(-12.0, 12.0), 8))
    }
}
