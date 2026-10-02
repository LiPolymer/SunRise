package ink.lipoly.app.sunrise.blueConnector

expect abstract class BtHost
expect fun createBtManager(host: BtHost): BtManager
