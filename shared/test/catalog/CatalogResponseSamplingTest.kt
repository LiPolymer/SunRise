package ink.lipoly.app.sunrise.catalog

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.*

class CatalogResponseSamplingTest {
    private fun reference(vararg points: Pair<Double, Double>) = FrequencyResponse(
        DoubleArray(points.size) { points[it].first }, DoubleArray(points.size) { points[it].second },
    )

    @Test fun interpolatesMagnitudeLinearlyInLogFrequency() {
        val response = reference(100.0 to 40.0, 1000.0 to 60.0)
        assertEquals(50.0, interpolateCatalogSpl(response, sqrt(100.0 * 1000.0)), 1e-12)
        assertEquals(40.0, interpolateCatalogSpl(response, 100.0), 0.0)
        assertEquals(60.0, interpolateCatalogSpl(response, 1000.0), 0.0)
        assertEquals(40.0 + 20.0 * ln(5.0) / ln(10.0), interpolateCatalogSpl(response, 500.0), 1e-12)
    }

    @Test fun samplingNormalizesEveryPointByTheSame500HzOffset() {
        val response = reference(100.0 to 40.0, 500.0 to 53.0, 1000.0 to 60.0)
        val sampled = sampleCatalogResponse(response)
        assertEquals(500.0, sampled.normalizationHz)
        assertEquals(100.0, sampled.frequencyHz.first())
        assertEquals(1000.0, sampled.frequencyHz.last())
        assertEquals(-13.0, sampled.referenceDb.first(), 1e-12)
        assertEquals(7.0, sampled.referenceDb.last(), 1e-12)
        for (index in sampled.frequencyHz.indices) {
            assertEquals(interpolateCatalogSpl(response, sampled.frequencyHz[index]) - 53.0, sampled.referenceDb[index], 1e-12)
        }
    }

    @Test fun preservesIntersectionEndpointsAndNeverInvents20kCoverage() {
        val response = reference(20.0 to 80.0, 19896.974609 to 90.0)
        val sampled = sampleCatalogResponse(response)
        assertEquals(20.0, sampled.frequencyHz.first(), 0.0)
        assertEquals(19896.974609, sampled.frequencyHz.last(), 0.0)
        assertTrue(sampled.frequencyHz.all { it in 20.0..19896.974609 })
        assertTrue((1 until sampled.frequencyHz.size).all { sampled.frequencyHz[it - 1] < sampled.frequencyHz[it] })
    }

    @Test fun fullCoverageUsesTheExactSharedGridWithoutDuplicateEndpoints() {
        val sampled = sampleCatalogResponse(reference(10.0 to 40.0, 40000.0 to 40.0))
        assertEquals(768, catalogFrequencyGrid.size)
        assertContentEquals(catalogFrequencyGrid, sampled.frequencyHz)
        assertTrue(sampled.referenceDb.all { it == 0.0 })
        assertEquals(500.0, sampled.normalizationHz)
    }

    @Test fun coverageWithout500HzRetainsOriginalSpl() {
        for (response in listOf(reference(600.0 to 70.0, 800.0 to 80.0), reference(30.0 to 80.0, 400.0 to 70.0))) {
            val sampled = sampleCatalogResponse(response)
            assertNull(sampled.normalizationHz)
            assertEquals(response.splDb.first(), sampled.referenceDb.first(), 0.0)
            assertEquals(response.splDb.last(), sampled.referenceDb.last(), 0.0)
        }
    }

    @Test fun exact500HzBoundaryStillNormalizes() {
        for (response in listOf(reference(500.0 to 70.0, 800.0 to 80.0), reference(30.0 to 80.0, 500.0 to 70.0))) {
            val sampled = sampleCatalogResponse(response)
            assertEquals(500.0, sampled.normalizationHz)
            val boundary = if (sampled.frequencyHz.first() == 500.0) sampled.referenceDb.first() else sampled.referenceDb.last()
            assertEquals(0.0, boundary, 0.0)
        }
    }

    @Test fun emptyIntersectionIsExplicitAndTouchingIntersectionIsOnePoint() {
        for (response in listOf(reference(1.0 to 50.0, 10.0 to 60.0), reference(30000.0 to 50.0, 40000.0 to 60.0))) {
            val sampled = sampleCatalogResponse(response)
            assertTrue(sampled.frequencyHz.isEmpty())
            assertTrue(sampled.referenceDb.isEmpty())
            assertNull(sampled.normalizationHz)
        }
        assertContentEquals(doubleArrayOf(20.0), sampleCatalogResponse(reference(10.0 to 50.0, 20.0 to 60.0)).frequencyHz)
        assertContentEquals(doubleArrayOf(20000.0), sampleCatalogResponse(reference(20000.0 to 50.0, 30000.0 to 60.0)).frequencyHz)
    }

    @Test fun directLookupRejectsOutsideRangeAndNonfiniteQueries() {
        val response = reference(100.0 to 40.0, 1000.0 to 60.0)
        for (frequency in doubleArrayOf(99.999, 1000.001, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { interpolateCatalogSpl(response, frequency) }
        }
    }
}
