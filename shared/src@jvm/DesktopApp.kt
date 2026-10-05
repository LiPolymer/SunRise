package ink.lipoly.app.sunrise

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.JvmCatalogDocuments
import ink.lipoly.app.sunrise.catalog.JvmCatalogStorage
import ink.lipoly.app.sunrise.resources.Res
import java.awt.Frame
import ink.lipoly.app.sunrise.composeLegacy.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

private val catalogShutdownHookRegistered = AtomicBoolean()

private fun registerCatalogShutdownHook() {
    if (!catalogShutdownHookRegistered.compareAndSet(false, true)) return
    try {
        Runtime.getRuntime().addShutdownHook(Thread({
            runBlocking { Catalog.close() }
        }, "sunrise-catalog-shutdown"))
    } catch (failure: Throwable) {
        catalogShutdownHookRegistered.set(false)
        throw failure
    }
}

@Composable
fun DesktopApp(window: Frame) {
    val settingsStore = remember { UiSettingsStore() }
    val settings = settingsStore.current
    LaunchedEffect(Unit) {
        Catalog.init(
            storage = { JvmCatalogStorage() },
            loadBundled = { Res.readBytes("files/moondrop-catalog.snapshot.json") },
        )
        registerCatalogShutdownHook()
    }
    val documents = remember(window) { JvmCatalogDocuments(window) }
    DisposableEffect(documents) {
        onDispose { documents.close() }
    }
    val navigation = rememberAppNavigationState()
    SunRiseTheme(settings, dark = settings.isDark(isSystemInDarkTheme())) {
        AppEntry(
            client = null,
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
