package ink.lipoly.app.sunrise

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import ink.lipoly.app.sunrise.catalog.JvmCatalogDocuments
import ink.lipoly.app.sunrise.composeLegacy.*
import ink.lipoly.app.sunrise.di.SunRiseRuntime
import ink.lipoly.app.sunrise.settings.usesEnglish
import ink.lipoly.app.sunrise.settings.isDark
import java.awt.Frame
import java.util.Locale

@Composable
fun DesktopApp(window: Frame, runtime: SunRiseRuntime) {
    LaunchedEffect(runtime) {
        runtime.awaitInitialized()
    }
    val settings by runtime.settings.state.collectAsState()
    val documents = remember(window) { JvmCatalogDocuments(window) }
    val scope = rememberCoroutineScope()
    val session = remember(runtime, documents) { runtime.createPresentationSession(documents, scope, preview = false) }
    DisposableEffect(session, documents) {
        onDispose {
            try { session.close() } finally { documents.close() }
        }
    }
    val navigation = rememberAppNavigationState()
    SunRiseTheme(settings, dark = settings.isDark(isSystemInDarkTheme())) {
        AppEntry(
            catalog = runtime.catalog,
            client = runtime.client,
            session = session,
            missingPermissions = emptySet(),
            onRequestPermissions = {},
            settings = settings,
            english = settings.language.usesEnglish(Locale.getDefault().language),
            dynamicColorAvailable = false,
            navigation = navigation,
            onSettingsChange = runtime.settings::update,
        )
    }
}
