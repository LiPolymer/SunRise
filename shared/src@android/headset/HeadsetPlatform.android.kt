package ink.lipoly.app.sunrise.headset

import android.content.Context
import ink.lipoly.app.sunrise.blueConnector.BtManager
import ink.lipoly.app.sunrise.drop.DropOptions

/** The host owns [bt]; this facade never creates another manager. Existing associations survive migration. */
fun createHeadsetClient(context: Context, bt: BtManager, options: DropOptions = DropOptions()): HeadsetClient {
    val preferences = context.applicationContext.getSharedPreferences("drop_verified_devices", Context.MODE_PRIVATE)
    val associations = object : HeadsetAssociations {
        override fun endpoint(address: String): String? = preferences.getString(address.uppercase(), null)
        override fun remember(address: String, endpoint: String) {
            preferences.edit().putString(address.uppercase(), endpoint.uppercase()).apply()
        }
    }
    return HeadsetClient(bt, associations, options)
}
