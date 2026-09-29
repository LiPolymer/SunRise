package ink.lipoly.app.sunrise.drop

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DropPlatformTest {
    @Test
    fun desktopBluetoothCannotCreateAClient() {
        assertFailsWith<UnsupportedOperationException> {
            createDropClient(object : DropHost() {})
        }
    }
}
