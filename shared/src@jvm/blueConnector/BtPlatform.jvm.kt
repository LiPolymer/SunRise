package ink.lipoly.app.sunrise.blueConnector

actual abstract class BtHost
actual fun createBtManager(host: BtHost): BtManager =
    throw UnsupportedOperationException("Bluetooth is unavailable on JVM desktop")
