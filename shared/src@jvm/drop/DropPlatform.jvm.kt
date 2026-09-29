package ink.lipoly.app.sunrise.drop

actual abstract class DropHost

actual fun createDropClient(host: DropHost, options: DropOptions): DropClient =
    throw UnsupportedOperationException("Bluetooth is unavailable on JVM desktop")
