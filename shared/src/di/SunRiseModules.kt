package ink.lipoly.app.sunrise.di

import ink.lipoly.app.sunrise.catalog.Catalog
import ink.lipoly.app.sunrise.catalog.CatalogByteFetcher
import ink.lipoly.app.sunrise.catalog.CatalogStorage
import ink.lipoly.app.sunrise.catalog.KtorCatalogFetcher
import ink.lipoly.app.sunrise.catalog.CatalogDocuments
import ink.lipoly.app.sunrise.presentation.CatalogOperations
import ink.lipoly.app.sunrise.presentation.ConnectionCoordinator
import ink.lipoly.app.sunrise.presentation.EqSessionOwner
import ink.lipoly.app.sunrise.presentation.HeadsetPresentationController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

internal class ApplicationLifetime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

internal data class CatalogEnvironment(
    val storage: () -> CatalogStorage,
    val loadBundled: suspend () -> ByteArray,
    val fetcher: () -> CatalogByteFetcher = ::KtorCatalogFetcher,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
)

internal data class PresentationContext(
    val documents: CatalogDocuments,
    val scope: CoroutineScope,
    val preview: Boolean,
)

// Koin singleton factories cache inside Module; each container needs fresh definitions.
internal val applicationModule: Module get() = module {
    singleOf(::Catalog)
    singleOf(::ApplicationLifetime)
    factory { parameters ->
        val context = parameters.get<PresentationContext>()
        HeadsetPresentationController(get<ConnectionCoordinator>(), context.scope)
    }
    factory { parameters ->
        val context = parameters.get<PresentationContext>()
        CatalogOperations(get<Catalog>(), context.documents, context.scope, context.preview)
    }
    factory { parameters ->
        val context = parameters.get<PresentationContext>()
        EqSessionOwner(context.scope)
    }
}
