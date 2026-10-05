package ink.lipoly.app.sunrise.catalog

internal actual fun <T> catalogSynchronized(lock: Any, block: () -> T): T =
    synchronized(lock, block)
