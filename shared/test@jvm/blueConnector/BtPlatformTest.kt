package ink.lipoly.app.sunrise.blueConnector

import kotlin.test.Test
import kotlin.test.assertFailsWith

class BtPlatformTest {
    @Test fun desktopBluetoothCannotCreateAManager() {
        assertFailsWith<UnsupportedOperationException> {
            createBtManager(object : BtHost() {})
        }
    }
}
