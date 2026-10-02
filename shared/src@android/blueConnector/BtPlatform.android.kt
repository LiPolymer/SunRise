package ink.lipoly.app.sunrise.blueConnector

import android.content.Context

actual typealias BtHost = Context

actual fun createBtManager(host: BtHost): BtManager = AndroidBtManager(host.applicationContext)
