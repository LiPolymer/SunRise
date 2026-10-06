package ink.lipoly.app.sunrise.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.presentation.PresentationSession
import ink.lipoly.app.sunrise.settings.UiSettings

/**
 * Rewrite the shared UI here. The host owns these services; do not close them from a page.
 * session.headset: connection/actions; session.catalog: import/export/pull; session.eq: editor owner.
 * Read service/editor StateFlows with collectAsState, and persist settings with onSettingsChange.
 */
@Composable
internal fun AppContent(
    catalog: Catalog,
    client: HeadsetClient?,
    session: PresentationSession,
    missingPermissions: Set<String>,
    onRequestPermissions: () -> Unit,
    settings: UiSettings,
    english: Boolean,
    dynamicColorAvailable: Boolean,
    onSettingsChange: (UiSettings) -> Unit,
) {
    val headsetState = client?.state?.collectAsState()?.value
    val catalogState by catalog.state.collectAsState()
    val eqEditor = rememberParamEqEditor(client, headsetState, session.eq, missingPermissions)
    val eqState = eqEditor?.state?.collectAsState()?.value

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SunRise", style = MaterialTheme.typography.headlineMedium)
            Text(headsetState?.phase?.name ?: if (english) "Bluetooth unavailable" else "蓝牙不可用")
            Text(if (english) "Catalog: ${catalogState.snapshot?.products?.size ?: 0}" else "目录：${catalogState.snapshot?.products?.size ?: 0}")
            eqState?.let { Text(if (english) "EQ bands: ${it.draft.size}" else "EQ 频段：${it.draft.size}") }
            if (missingPermissions.isNotEmpty()) {
                Button(onClick = onRequestPermissions) {
                    Text(if (english) "Grant Bluetooth permissions" else "授予蓝牙权限")
                }
            }
        }
    }
}
