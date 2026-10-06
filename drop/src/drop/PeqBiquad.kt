package ink.lipoly.app.sunrise.drop

import kotlin.math.*

/** Normalized RBJ coefficients for a static 48 kHz parameter-response estimate. */
class PeqBiquad private constructor(private val b0: Double, private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
    fun responseDb(hz: Double): Double {
        val w = 2.0 * PI * hz.coerceIn(20.0, 20000.0) / 48000.0
        return responseDb(cos(w), sin(w), cos(2 * w), sin(2 * w))
    }

    /** Trigonometry may be shared across bands when sampling a complete response. */
    fun responseDb(c1: Double, s1: Double, c2: Double, s2: Double): Double {
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = b1 * s1 + b2 * s2
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = a1 * s1 + a2 * s2
        return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }
    companion object {
        fun of(band: GaiaPeqBand): PeqBiquad {
            if (band.filter == PeqFilter.BYPASS) return PeqBiquad(1.0, 0.0, 0.0, 0.0, 0.0)
            val w = 2 * PI * band.frequencyHz / 48000.0
            val c = cos(w)
            val alpha = sin(w) / (2 * band.q)
            val a = sqrt(10.0.pow(band.gainDb / 20.0))
            val beta = 2 * sqrt(a) * alpha
            val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
            when (band.filter) {
                PeqFilter.PEAKING -> { b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a; a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a }
                PeqFilter.LOW_PASS -> { b0 = (1 - c) / 2; b1 = 1 - c; b2 = b0; a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha }
                PeqFilter.HIGH_PASS -> { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = b0; a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha }
                PeqFilter.LOW_SHELF -> { b0 = a * ((a + 1) - (a - 1) * c + beta); b1 = 2 * a * ((a - 1) - (a + 1) * c); b2 = a * ((a + 1) - (a - 1) * c - beta); a0 = (a + 1) + (a - 1) * c + beta; a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - beta }
                PeqFilter.HIGH_SHELF -> { b0 = a * ((a + 1) + (a - 1) * c + beta); b1 = -2 * a * ((a - 1) + (a + 1) * c); b2 = a * ((a + 1) + (a - 1) * c - beta); a0 = (a + 1) - (a - 1) * c + beta; a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - beta }
                PeqFilter.BYPASS -> error("Bypass is handled above")
            }
            return PeqBiquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
