package ink.lipoly.app.sunrise.drop

import android.content.Context

actual typealias DropHost = Context

actual fun createDropClient(host: DropHost, options: DropOptions): DropClient =
    AndroidDropClient(host.applicationContext, options)
