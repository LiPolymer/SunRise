package ink.lipoly.app.sunrise.drop

import ink.lipoly.app.sunrise.drop.gaia.GaiaBinding
import ink.lipoly.app.sunrise.drop.gaia.GaiaControls
import ink.lipoly.app.sunrise.drop.source.SourceBinding
import ink.lipoly.app.sunrise.drop.source.SourceControls

/** Both discovered families, one owner and one shared transaction gate; no protocol policy. */
internal class CombinedController(
    context: ControllerContext,
    private val gaia: GaiaBinding,
    source: SourceBinding,
) : SessionController(context),
    GaiaControls by gaia.controls,
    SourceControls by source.controls,
    BatteryControls,
    NoiseControls {
    override suspend fun readBattery(): EarbudBatteryReading = gaia.controls.getBattery()
    override suspend fun readNoise(): AncMode = gaia.controls.getAncMode()
    override suspend fun writeNoise(mode: AncMode): AncMode = gaia.controls.setAncMode(mode)
}
