package ink.lipoly.app.sunrise.presentation


import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.GaiaIds

/** 已由能力报告或成功读回确认的总览控制。 */
enum class OverviewControl { GAIN, LED, SPATIAL, HEAD_TRACKING }

/** 当前会话是否具有可用的 GAIA 控制。 */
fun DropState.hasReadyGaia(): Boolean =
    phase == DropPhase.READY && DropProtocol.GAIA_BLE in protocols

/** 按设备能力与抗风噪显示偏好筛选模式。 */
fun shownAncModes(state: DropState, showWind: Boolean): List<AncMode> =
    if (!state.hasReadyGaia()) emptyList() else AncMode.entries.filter {
        it in state.capabilities.ancModes && (showWind || it != AncMode.WIND)
    }

/** 合并能力报告与本会话成功读回的控制，不推断未知能力。 */
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
