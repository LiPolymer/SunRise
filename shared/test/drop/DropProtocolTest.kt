package ink.lipoly.app.sunrise.drop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DropProtocolTest {
    @Test fun gaiaRoundTripAndFeatureFormats() {
        val command = GaiaCommand(GaiaIds.ANC_V2, GaiaIds.Anc.SET_MODE, byteArrayOf(4))
        assertContentEquals(byteArrayOf(0, 0x1d, 0x40, 4, 4), GaiaCodec.encode(command))
        val response = byteArrayOf(0, 0x1d, 0x41, 4, 4)
        val decoded = GaiaCodec.decode(response)!!
        assertEquals(GaiaIds.ANC_V2, decoded.feature)
        assertEquals(GaiaCodec.RESPONSE, decoded.type)
        assertContentEquals(byteArrayOf(4), decoded.payload)
        assertEquals(setOf(0, 1, 13, 32), GaiaCodec.features(byteArrayOf(
            0, 0, 0x20, 3, 0, 0, 0, 1,
        )))
        assertEquals(setOf(0, 13, 32), GaiaCodec.features(byteArrayOf(
            0, 0, 2, 13, 1, 32, 1,
        )))
    }

    @Test fun profileUsesIndependentReadAndWriteMaps() {
        val ga2 = DropProfiles.resolve(DropOptions(), "AA:BB:CC:DD:EE:FF", "Golden Ages 2")
        assertEquals(4, DropProfiles.toDevice(AncPath.AUDIO_CURATION, AncMode.TRANSPARENCY, ga2))
        assertEquals(AncMode.TRANSPARENCY, DropProfiles.fromDevice(AncPath.AUDIO_CURATION, 2, ga2))
        assertEquals(2, DropProfiles.gainToDevice(GainLevel.LOW, ga2))
        val pudding = DropProfiles.resolve(DropOptions(), "AA:BB:CC:DD:EE:FF", "Pudding")
        assertEquals(4, DropProfiles.toDevice(AncPath.V2, AncMode.NOISE_CANCELLING, pudding))
        assertEquals(AncMode.ADAPTIVE, DropProfiles.fromDevice(AncPath.V2, 1, pudding))
        val custom = DropProfile(DropProfileMatch.Address("AA:BB:CC:DD:EE:FF"),
            audioCurationWrite = mapOf(AncMode.OFF to 9))
        val resolved = DropProfiles.resolve(DropOptions(listOf(custom)),
            "AA:BB:CC:DD:EE:FF", "Golden Ages 2")
        assertEquals(9, resolved.audioCurationWrite?.get(AncMode.OFF))
        assertEquals(AncMode.TRANSPARENCY, resolved.audioCurationRead?.get(2))
        assertEquals(2, DropProfiles.gainToDevice(GainLevel.LOW, resolved))
        val plain = DropProfiles.resolve(DropOptions(), "00:00:00:00:00:00", "unknown")
        assertEquals(5, DropProfiles.toDevice(AncPath.V2, AncMode.LIVE, plain))
    }

    @Test fun sourceFrameAndPayloadParsers() {
        assertContentEquals(byteArrayOf(0xa5.toByte(), 1, 1, 2, 7, 3, 1, 4, 5),
            SourceCodec.encode(SourceIds.SET_AUDIO_SOURCE, 7, byteArrayOf(1, 4, 5)))
        assertEquals(null, SourceCodec.decode(byteArrayOf(0xa5.toByte(), 1, 2, 1, 9, 14)))
        val frame = SourceCodec.decode(byteArrayOf(0xa5.toByte(), 1, 2, 1, 9, 5, 0, 1, 1, 0, 0))!!
        assertEquals(9, frame.sequence)
        assertTrue(SourceCodec.sourceStatus(frame.payload).stableSuccess)
        assertTrue(SourceStatus(9, 1, 1, 0, 0).stableSuccess)
        val fw = SourceCodec.firmware(byteArrayOf(1, 0, 0x1f, 0, 2, 3, 4, 5, 6, 0, 0, 0))
        assertEquals(SourceFeature.PEQ in fw.features, true)
        assertEquals(6L, fw.buildId)
        assertEquals(-100, SourceCodec.peqPreGain(byteArrayOf(0, 255.toByte(), 0x9c.toByte(), 255.toByte(), 1)).centiDb)
        assertFailsWith<DropException.Protocol> { SourceCodec.snChunk(byteArrayOf(0), 0) }
    }
}
