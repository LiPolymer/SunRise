package ink.lipoly.app.sunrise.catalog

internal actual fun <T> catalogFetcherSynchronized(lock: Any, block: () -> T): T =
    synchronized(lock, block)
