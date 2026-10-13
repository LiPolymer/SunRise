package ink.lipoly.app.sunrise.di

import ink.lipoly.app.sunrise.JvmUiSettingsStore
import ink.lipoly.app.sunrise.blueConnector.BtHost
import ink.lipoly.app.sunrise.blueConnector.BtManager
import ink.lipoly.app.sunrise.blueConnector.createBtManager
import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.JvmCatalogStorage
import ink.lipoly.app.sunrise.headset.HeadsetAssociations
import ink.lipoly.app.sunrise.headset.HeadsetClient
import ink.lipoly.app.sunrise.presentation.ConnectionCoordinator
import ink.lipoly.app.sunrise.resources.Res
import ink.lipoly.app.sunrise.settings.UiSettingsStore
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose

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
    return assembleRuntime(application).also { it.updatePermissions(emptySet()) }
}

/** Desktop uses an isolated in-memory headset; no system Bluetooth resources are opened. */
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
        val bt = createBtManager(object : BtHost() {})
        try {
            ApplicationBluetoothResources(bt, HeadsetClient(bt, DesktopHeadsetAssociations()))
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

/** Mock audio/GATT associations live only as long as this desktop runtime. */
private class DesktopHeadsetAssociations : HeadsetAssociations {
    private val endpoints = mutableMapOf<String, String>()

    @Synchronized
    override fun endpoint(address: String): String? = endpoints[address]

    @Synchronized
    override fun remember(address: String, endpoint: String) {
        endpoints[address] = endpoint
    }
}
