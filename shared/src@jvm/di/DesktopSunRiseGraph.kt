package ink.lipoly.app.sunrise.di

import ink.lipoly.app.sunrise.JvmUiSettingsStore
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.JvmCatalogStorage
import ink.lipoly.app.sunrise.presentation.ConnectionCoordinator
import ink.lipoly.app.sunrise.resources.Res
import ink.lipoly.app.sunrise.settings.UiSettingsStore
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

fun createDesktopSunRiseRuntime(): SunRiseRuntime {
    var constructing: KoinApplication? = null
    val application = try {
        koinApplication {
            constructing = this
            allowOverride(false)
            modules(applicationModule, desktopModule)
        }
    } catch (failure: Throwable) {
        constructing?.let { closeApplicationAfterFailure(it, failure) }
        throw failure
    }
    return assembleRuntime(application)
}

/** Desktop has no Bluetooth backend: neither a manager nor a facade is registered. */
private val desktopModule: Module get() = module {
    single { JvmUiSettingsStore() }
    single<UiSettingsStore> { get<JvmUiSettingsStore>() }
    single { JvmCatalogStorage() }
    single {
        val storage = get<JvmCatalogStorage>()
        CatalogEnvironment(
            storage = { storage },
            loadBundled = { Res.readBytes("files/moondrop-catalog.snapshot.json") },
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
