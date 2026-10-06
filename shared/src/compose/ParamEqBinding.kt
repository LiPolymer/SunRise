package ink.lipoly.app.sunrise.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ink.lipoly.app.sunrise.controls.peq.ParamEqEditor
import ink.lipoly.app.sunrise.drop.DropException
import ink.lipoly.app.sunrise.drop.DropPhase
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.HeadsetState
import ink.lipoly.app.sunrise.headset.HeadsetPhase
import ink.lipoly.app.sunrise.presentation.EqSessionOwner

/** Keep this binding at the app root, not inside a navigated page. The host session closes it. */
@Composable
internal fun rememberParamEqEditor(
    client: HeadsetClient?,
    state: HeadsetState?,
    owner: EqSessionOwner,
    missingPermissions: Set<String>,
): ParamEqEditor? {
    val controls = if (client != null && missingPermissions.isEmpty() &&
        state?.phase == HeadsetPhase.READY && state.controls.phase == DropPhase.READY &&
        DropProtocol.GAIA_BLE in state.controls.protocols
    ) {
        try { client.gaia } catch (_: DropException) { null }
    } else null
    return remember(owner, controls) { owner.bind(controls) }
}
