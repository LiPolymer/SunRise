package ink.lipoly.app.sunrise.blueConnector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Permissions an Android host must request before performing Bluetooth operations. */
object BtPermissions {
    fun missing(context: Context, scan: Boolean = true): Set<String> = buildSet {
        if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            if (scan && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                add(Manifest.permission.BLUETOOTH_SCAN)
        } else if (scan && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
