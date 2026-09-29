package ink.lipoly.app.sunrise.drop

data class GaiaCommand(
    val feature: Int,
    val command: Int,
    val payload: ByteArray = byteArrayOf(),
    val vendor: Int = 0x001D,
)

data class GaiaPacket(
    val vendor: Int,
    val feature: Int,
    val type: Int,
    val command: Int,
    val payload: ByteArray,
)

/** Named IDs for the protocol surface documented by the reference project. */
object GaiaIds {
    const val BASIC = 0
    const val EARBUD = 1
    const val ANC_V1 = 2
    const val VOICE_UI = 3
    const val DEBUG = 4
    const val MUSIC_PROCESSING = 5
    const val UPGRADE = 6
    const val HANDSET_SERVICE = 7
    const val AUDIO_CURATION = 8
    const val EARBUD_FIT = 9
    const val VOICE_PROCESSING = 10
    const val GESTURE_CONFIGURATION = 11
    const val STATISTICS = 12
    const val BATTERY = 13
    const val VOICE = 14
    const val DAC_GAIN = 15
    const val CODEC_TYPE = 16
    const val LIGHT_SENSOR = 17
    const val SPATIAL_AUDIO = 18
    const val LED = 19
    const val ONE_BRING_TWO = 20
    const val BT_ADDRESS = 21
    const val TOUCH_V2 = 22
    const val AUDIO_RESOURCE = 23
    const val POWER_CONTROL = 24
    const val POWER_TIMEOUT = 25
    const val TOUCH_V3 = 26
    const val DYNAMIC_BASS = 27
    const val AUDIO_FILE_STORAGE = 29
    const val LR_CHANNEL = 30
    const val ANC_V2 = 32

    object Basic { const val VERSION = 0; const val FEATURES = 1; const val FEATURES_NEXT = 2; const val SERIAL = 3; const val VARIANT = 4; const val APP_VERSION = 5; const val REGISTER_NOTIFICATION = 7; const val CANCEL_NOTIFICATION = 8; const val DATA_SETUP = 9; const val DATA_GET = 10; const val COLOR = 18; const val LANGUAGE = 19; const val LEFT_SN = 20; const val RIGHT_SN = 21; const val TWS_STATUS = 22 }
    object Anc { const val V1_GET = 1; const val V1_SET = 2; const val GET_MODE = 3; const val SET_MODE = 4; const val SWITCH_CONFIG_GET = 41; const val SWITCH_CONFIG_SET = 42 }
    object AudioCuration { const val GET_STATE = 0; const val SET_STATE = 1; const val GET_MODE_COUNT = 2; const val GET_MODE = 3; const val SET_MODE = 4; const val GET_GAIN = 5; const val SET_GAIN = 6; const val GET_TOGGLE_COUNT = 7; const val GET_TOGGLE = 8; const val SET_TOGGLE = 9; const val GET_SCENARIO = 10; const val SET_SCENARIO = 11; const val GET_DEMO_SUPPORT = 12; const val GET_DEMO_STATE = 13; const val SET_DEMO_STATE = 14; const val GET_ADAPTATION = 15; const val SET_ADAPTATION = 16; const val GET_LEAKTHROUGH_CONFIG = 17; const val GET_LEAKTHROUGH_STEP = 18; const val SET_LEAKTHROUGH_STEP = 19; const val GET_BALANCE = 20; const val SET_BALANCE = 21; const val GET_WIND_SUPPORT = 22; const val GET_WIND_STATE = 23; const val SET_WIND_STATE = 24; const val GET_AUTO_TRANSPARENCY_SUPPORT = 25; const val GET_AUTO_TRANSPARENCY_STATE = 26; const val SET_AUTO_TRANSPARENCY_STATE = 27; const val GET_RELEASE_TIME = 28; const val SET_RELEASE_TIME = 29; const val GET_HOWLING_SUPPORT = 30; const val GET_HOWLING_STATE = 31; const val SET_HOWLING_STATE = 32; const val GET_FEEDBACK_GAIN = 33; const val GET_NOISE_ID_SUPPORT = 34; const val GET_NOISE_ID_STATE = 35; const val SET_NOISE_ID_STATE = 36; const val GET_NOISE_CATEGORY = 37; const val GET_ADVERSE_SUPPORT = 38; const val GET_ADVERSE_STATE = 39; const val SET_ADVERSE_STATE = 40; const val GET_SWITCH_CONFIG = 41; const val SET_SWITCH_CONFIG = 42 }
    object Eq { const val GET_STATE = 0; const val GET_PRESETS = 1; const val GET_PRESET = 2; const val SET_PRESET = 3; const val GET_BAND_COUNT = 4; const val GET_USER_CONFIG = 5; const val SET_USER_CONFIG = 6; const val STORE_USER_CONFIG = 7; const val SET_NV_ID = 8 }
    object Gesture { const val TOUCHPAD_COUNT = 0; const val SUPPORTED_GESTURES = 1; const val SUPPORTED_CONTEXTS = 2; const val SUPPORTED_ACTIONS = 3; const val GET_CONFIG = 4; const val SET_CONFIG = 5; const val RESET = 6 }
    object Codec { const val GET_LC3 = 1; const val GET_LDAC = 2; const val SET_LC3 = 3; const val SET_LDAC = 4; const val GET_LHDC = 5; const val SET_LHDC = 6 }
    object Spatial { const val GET = 1; const val SET = 2; const val GET_TRACKING = 3; const val SET_TRACKING = 4 }
    object Device { const val GET = 1; const val SET = 2; const val POWER_OFF = 1 }
    object Battery { const val SUPPORTED = 0; const val LEVELS = 1 }
}

object GaiaCodec {
    const val COMMAND = 0
    const val NOTIFICATION = 1
    const val RESPONSE = 2

    fun encode(command: GaiaCommand): ByteArray {
        require(command.vendor in 0..0xFFFF && command.feature in 0..127 && command.command in 0..127)
        val word = (command.feature shl 9) or command.command
        return byteArrayOf((command.vendor shr 8).toByte(), command.vendor.toByte(),
            (word shr 8).toByte(), word.toByte()) + command.payload.copyOf()
    }

    fun decode(bytes: ByteArray): GaiaPacket? {
        if (bytes.size < 4) return null
        val vendor = u16be(bytes, 0)
        val word = u16be(bytes, 2)
        return GaiaPacket(vendor, (word shr 9) and 0x7f, (word shr 7) and 0x03,
            word and 0x7f, bytes.copyOfRange(4, bytes.size))
    }

    fun features(payload: ByteArray): Set<Int> {
        if (payload.size >= 3 && payload.size % 2 == 1 && (payload[0].toInt() and 0xff) <= 1) {
            return (1 until payload.size step 2).map { payload[it].toInt() and 0xff }.toSet()
        }
        val result = mutableSetOf<Int>()
        for (offset in 0 until payload.size - 3 step 4) {
            val word = ((payload[offset].toInt() and 0xff) shl 24) or
                ((payload[offset + 1].toInt() and 0xff) shl 16) or
                ((payload[offset + 2].toInt() and 0xff) shl 8) or (payload[offset + 3].toInt() and 0xff)
            for (bit in 0..31) if ((word and (1 shl bit)) != 0) result += (offset / 4) * 32 + bit
        }
        return result
    }

    internal fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}
