package ink.lipoly.app.sunrise

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import ink.lipoly.app.sunrise.catalog.CatalogRepository
import ink.lipoly.app.sunrise.catalog.JvmCatalogDocuments
import ink.lipoly.app.sunrise.catalog.JvmCatalogSettingsStore
import ink.lipoly.app.sunrise.catalog.JvmCatalogStorage
import ink.lipoly.app.sunrise.resources.Res
import java.awt.Frame
import ink.lipoly.app.sunrise.composeLegacy.*
import java.util.Locale

@Composable
fun DesktopApp(window: Frame) {
    val settingsStore = remember { JvmCatalogSettingsStore() }
    val settings = settingsStore.current
    val repository = remember {
        CatalogRepository(
            storage = JvmCatalogStorage(),
            loadBundled = { Res.readBytes("files/moondrop-bt.snapshot.json") },
        )
    }
    val documents = remember(window) { JvmCatalogDocuments(window) }
    LaunchedEffect(repository) { repository.loadLocal() }
    DisposableEffect(repository) {
        onDispose { repository.close() }
    }
    DisposableEffect(documents) {
        onDispose { documents.close() }
    }
    val navigation = rememberAppNavigationState()
    SunRiseTheme(settings, dark = settings.isDark(isSystemInDarkTheme())) {
        AppEntry(
            client = null,
            catalog = repository,
            documents = documents,
            missingPermissions = emptySet(),
            onRequestPermissions = {},
            settings = settings,
            english = settings.language.usesEnglish(Locale.getDefault().language),
            dynamicColorAvailable = false,
            navigation = navigation,
            onSettingsChange = settingsStore::update,
        )
    }
}
