package ink.lipoly.app.sunrise.composeLegacy


import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.GaiaIds

enum class OverviewControl { GAIN, LED, SPATIAL, HEAD_TRACKING }

fun DropState.hasReadyGaia(): Boolean =
    phase == DropPhase.READY && DropProtocol.GAIA_BLE in protocols

fun shownAncModes(state: DropState, showWind: Boolean): List<AncMode> =
    if (!state.hasReadyGaia()) emptyList() else AncMode.entries.filter {
        it in state.capabilities.ancModes && (showWind || it != AncMode.WIND)
    }

fun confirmedOverviewControls(
    state: DropState,
    readConfirmed: Set<OverviewControl> = emptySet(),
): Set<OverviewControl> {
    if (!state.hasReadyGaia()) return emptySet()
    val reported = buildSet {
        if (GaiaIds.DAC_GAIN in state.capabilities.gaiaFeatures) add(OverviewControl.GAIN)
        if (GaiaIds.LED in state.capabilities.gaiaFeatures) add(OverviewControl.LED)
        if (GaiaIds.SPATIAL_AUDIO in state.capabilities.gaiaFeatures) {
            add(OverviewControl.SPATIAL)
            add(OverviewControl.HEAD_TRACKING)
        }
    }
    return reported + readConfirmed
}
