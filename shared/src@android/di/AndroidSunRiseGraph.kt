package ink.lipoly.app.sunrise.di

import android.content.Context
import ink.lipoly.app.sunrise.AndroidUiSettingsStore
import ink.lipoly.app.sunrise.blueConnector.BtManager
import ink.lipoly.app.sunrise.blueConnector.createBtManager
import ink.lipoly.app.sunrise.catalog.AndroidCatalogStorage
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.headset.createHeadsetClient
import ink.lipoly.app.sunrise.presentation.ConnectionCoordinator
import ink.lipoly.app.sunrise.resources.Res
import ink.lipoly.app.sunrise.settings.UiSettingsStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose

fun createAndroidSunRiseRuntime(context: Context): SunRiseRuntime {
    val appContext = context.applicationContext
    var constructing: KoinApplication? = null
    val application = try {
        koinApplication {
            constructing = this
            allowOverride(false)
            androidContext(appContext)
            modules(applicationModule, androidModule(appContext))
        }
    } catch (failure: Throwable) {
        constructing?.let { closeApplicationAfterFailure(it, failure) }
        throw failure
    }
    return assembleRuntime(application)
}

private fun androidModule(context: Context): Module = module {
    single { AndroidUiSettingsStore(context.applicationContext) }
    single<UiSettingsStore> { get<AndroidUiSettingsStore>() }
    single { AndroidCatalogStorage(context.applicationContext) }
    single {
        val storage = get<AndroidCatalogStorage>()
        CatalogEnvironment(
            storage = { storage },
            loadBundled = { Res.readBytes("files/moondrop-catalog.snapshot.json") },
        )
    }
    single {
        val bt = createBtManager(context.applicationContext)
        try {
            ApplicationBluetoothResources(bt, createHeadsetClient(context.applicationContext, bt))
        } catch (failure: Throwable) {
            try {
                bt.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    } onClose { resources -> resources?.close() }
    single<BtManager> { get<ApplicationBluetoothResources>().bt }
    single<HeadsetClient> { get<ApplicationBluetoothResources>().client }
    single {
        ConnectionCoordinator(
            client = get<HeadsetClient>(),
            catalog = get<Catalog>(),
            settings = get<UiSettingsStore>(),
            scope = get<ApplicationLifetime>().scope,
        )
    }
}
