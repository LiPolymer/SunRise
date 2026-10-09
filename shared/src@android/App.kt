package ink.lipoly.app.sunrise

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import ink.lipoly.app.sunrise.catalog.rememberCatalogDocuments
import ink.lipoly.app.sunrise.di.SunRiseRuntime
import ink.lipoly.app.sunrise.di.createPreviewSunRiseRuntime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
fun App(
    runtime: SunRiseRuntime,
    missingPermissions: Set<String> = emptySet(),
    onRequestPermissions: () -> Unit = {},
) {
    LaunchedEffect(runtime) {
        runtime.awaitInitialized()
    }
    val settings by runtime.settings.state.collectAsState()
    val documents = rememberCatalogDocuments()
    val scope = rememberCoroutineScope()
    val preview = LocalInspectionMode.current
    val session = remember(runtime, documents) { runtime.createPresentationSession(documents, scope, preview) }
    DisposableEffect(session) { onDispose { session.close() } }
    AndroidSunRiseTheme(settings) {
        AppEntry(
            catalog = runtime.catalog,
            client = runtime.client,
            session = session,
            missingPermissions = missingPermissions,
            onRequestPermissions = onRequestPermissions,
            settings = settings,
            dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            onSettingsChange = runtime.settings::update,
        )
    }
}

@Preview
@Composable
fun AppPreview() {
    val runtime = remember { createPreviewSunRiseRuntime() }
    LaunchedEffect(runtime) {
        try {
            runtime.awaitInitialized()
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { runtime.close() }
        }
    }
    App(runtime)
}