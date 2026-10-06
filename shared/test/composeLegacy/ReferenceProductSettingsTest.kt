package ink.lipoly.app.sunrise.composeLegacy

import ink.lipoly.app.sunrise.settings.decodeReferenceProducts
import ink.lipoly.app.sunrise.settings.encodeReferenceProducts
import kotlin.test.*

class ReferenceProductSettingsTest {
    @Test fun bindingsRoundTripWithCanonicalAudioAddresses() {
        val bindings = mapOf("aa:bb:cc:dd:ee:ff" to "11111111-1111-4111-8111-111111111111")
        assertEquals(bindings.mapKeys { it.key.uppercase() }, decodeReferenceProducts(encodeReferenceProducts(bindings)))
    }

    @Test fun damagedBindingDocumentDoesNotKeepPartialBindings() {
        for (bad in listOf("not json", "[]", "{\"AA\":false}", "{\"AA\":\"valid\",\"BB\":null}")) {
            assertEquals(emptyMap(), decodeReferenceProducts(bad))
        }
    }
}
