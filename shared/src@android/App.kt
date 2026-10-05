package ink.lipoly.app.sunrise

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import ink.lipoly.app.sunrise.catalog.AndroidCatalogStorage
import ink.lipoly.app.sunrise.catalog.CatalogByteFetcher
import ink.lipoly.app.sunrise.catalog.CatalogRepository
import ink.lipoly.app.sunrise.catalog.CatalogStorage
import ink.lipoly.app.sunrise.catalog.KtorCatalogFetcher
import ink.lipoly.app.sunrise.catalog.rememberCatalogDocuments
import ink.lipoly.app.sunrise.composeLegacy.*
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.resources.Res

@Composable
@Preview
fun App(
    client: HeadsetClient? = null,
    missingPermissions: Set<String> = emptySet(),
    onRequestPermissions: () -> Unit = {},
) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val catalog = remember(context, preview) {
        CatalogRepository(
            storage = if (preview) PreviewCatalogStorage else AndroidCatalogStorage(context),
            loadBundled = { Res.readBytes("files/moondrop-bt.snapshot.json") },
            fetcher = if (preview) PreviewCatalogFetcher else KtorCatalogFetcher(),
        )
    }
    val documents = rememberCatalogDocuments()
    LaunchedEffect(catalog) { catalog.loadLocal() }
    DisposableEffect(catalog) {
        onDispose { catalog.close() }
    }
    val settingsStore = remember(context) { UiSettingsStore(context) }
    val settings = settingsStore.current
    val navigation = rememberAppNavigationState()

    BackHandler(navigation.catalogue || navigation.diagnostics) {
        if (navigation.catalogue) navigation.catalogue = false else navigation.diagnostics = false
    }
    AndroidSunRiseTheme(settings) {
        AppEntry(
            client = client,
            catalog = catalog,
            documents = documents,
            missingPermissions = missingPermissions,
            onRequestPermissions = onRequestPermissions,
            settings = settings,
            english = settings.english(),
            dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            navigation = navigation,
            onSettingsChange = settingsStore::update,
        )
    }
}

private object PreviewCatalogStorage : CatalogStorage {
    override suspend fun readActive(): ByteArray? = null
    override suspend fun writeActive(bytes: ByteArray): Unit =
        error("Catalog writes are unavailable in preview")
}

private object PreviewCatalogFetcher : CatalogByteFetcher {
    override suspend fun fetch(url: String, maxBytes: Int): ByteArray =
        error("Catalog pulls are unavailable in preview")
    override fun close() = Unit
}