package ink.lipoly.app.sunrise.di

import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.CatalogByteFetcher
import ink.lipoly.app.sunrise.catalog.CatalogStorage
import ink.lipoly.app.sunrise.presentation.ConnectionCoordinator
import ink.lipoly.app.sunrise.resources.Res
import ink.lipoly.app.sunrise.settings.UiSettings
import ink.lipoly.app.sunrise.settings.UiSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal fun createPreviewSunRiseRuntime(): SunRiseRuntime {
    var constructing: KoinApplication? = null
    val application = try {
        koinApplication {
            constructing = this
            allowOverride(false)
            modules(applicationModule, previewModule)
        }
    } catch (failure: Throwable) {
        constructing?.let { closeApplicationAfterFailure(it, failure) }
        throw failure
    }
    return assembleRuntime(application)
}

private val previewModule: Module get() = module {
    single<UiSettingsStore> { PreviewUiSettingsStore() }
    single {
        CatalogEnvironment(
            storage = { PreviewCatalogStorage },
            loadBundled = { Res.readBytes("files/moondrop-catalog.snapshot.json") },
            fetcher = { PreviewCatalogFetcher },
        )
    }
    single {
        ConnectionCoordinator(
            client = null,
            catalog = get<Catalog>(),
            settings = get<UiSettingsStore>(),
            scope = get<ApplicationLifetime>().scope,
        )
    }
}

private class PreviewUiSettingsStore : UiSettingsStore {
    private val mutableState = MutableStateFlow(UiSettings())
    override val state = mutableState.asStateFlow()
    override fun update(next: UiSettings) {
        mutableState.value = next
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
