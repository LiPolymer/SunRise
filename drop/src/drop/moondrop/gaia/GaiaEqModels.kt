package ink.lipoly.app.sunrise.drop.moondrop.gaia

/** Generic GAIA filter IDs; Bluetrum bulk encoding is separate and currently writes peaking as 0. */
enum class PeqFilter(val gaiaId: Int) {
    BYPASS(0), LOW_PASS(7), HIGH_PASS(8), LOW_SHELF(10), HIGH_SHELF(11), PEAKING(13),
}

/** Device raw units are retained exactly, including unused bypass fields. */
data class GaiaPeqBand(
    val index: Int,
    val frequencyHz: Int,
    val gainRaw: Int,
    val qRaw: Int,
    val filter: PeqFilter,
) {
    val gainDb: Double get() = gainRaw / 60.0
    val q: Double get() = qRaw / 4096.0
}

/** Complete verified readback, never an editor draft. Header gain semantics are unconfirmed. */
data class GaiaParamEqState(
    val availablePresets: List<Int>,
    val currentPreset: Int,
    val totalGainRaw: Int,
    val bands: List<GaiaPeqBand>,
) {
    val bandCount: Int get() = bands.size
}
