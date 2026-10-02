package ink.lipoly.app.sunrise.compose

import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.DropCapabilities
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.DropState
import ink.lipoly.app.sunrise.drop.GaiaIds
import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewRulesTest {
    private val ready = DropState(
        phase = DropPhase.READY,
        protocols = setOf(DropProtocol.GAIA_BLE),
        capabilities = DropCapabilities(
            gaiaFeatures = setOf(GaiaIds.DAC_GAIN, GaiaIds.SPATIAL_AUDIO),
            ancModes = setOf(AncMode.OFF, AncMode.NOISE_CANCELLING, AncMode.WIND),
        ),
    )

    @Test fun onlyReportedOrReadConfirmedControlsAppear() {
        assertEquals(
            setOf(OverviewControl.GAIN, OverviewControl.SPATIAL, OverviewControl.HEAD_TRACKING),
            confirmedOverviewControls(ready),
        )
        assertEquals(
            setOf(OverviewControl.GAIN, OverviewControl.SPATIAL, OverviewControl.HEAD_TRACKING, OverviewControl.LED),
            confirmedOverviewControls(ready, setOf(OverviewControl.LED)),
        )
        assertEquals(emptySet(), confirmedOverviewControls(ready.copy(phase = DropPhase.IDLE), setOf(OverviewControl.LED)))
    }

    @Test fun windSettingOnlyFiltersTheButton() {
        assertEquals(listOf(AncMode.OFF, AncMode.NOISE_CANCELLING), shownAncModes(ready, false))
        assertEquals(listOf(AncMode.OFF, AncMode.NOISE_CANCELLING, AncMode.WIND), shownAncModes(ready, true))
    }
}
