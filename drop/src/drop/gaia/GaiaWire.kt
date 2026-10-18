package ink.lipoly.app.sunrise.drop.gaia

/*
 * GAIA 通用线格式：vendor + (feature/type/command) 头与能力分页解析。
 * 本文件只描述信封，不定义任何 feature 语义；编号表、负载与交互确认属于具体设备实现（当前为 `moondrop`）。
 */

/**
 * GAIA 请求模型；构造不校验，编码时才检查字段范围。
 * @property feature 0..127 的功能编号。
 * @property command 0..127 的功能内命令编号。
 * @property payload 原始负载，编码时复制；本模型不限制长度，实际写入受 GATT MTU 限制。
 * @property vendor 0..65535 厂商编号，默认 0x001D 对应当前设备家族；面向其他厂商时应显式传入。
 */
data class GaiaCommand(
    val feature: Int,
    val command: Int,
    val payload: ByteArray = byteArrayOf(),
    val vendor: Int = 0x001D,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GaiaCommand) return false
        return feature == other.feature && command == other.command &&
            payload === other.payload && vendor == other.vendor
    }

    override fun hashCode(): Int {
        var result = feature
        result = 31 * result + command
        result = 31 * result + payload.contentHashCode()
        return 31 * result + vendor
    }
}

/**
 * GAIA 四字节头解码结果，不在 codec 中检查成功/拒绝状态。
 * @property vendor 大端 16 位厂商编号。
 * @property feature command word 的高七位功能编号。
 * @property type command word 的两位类型（0 命令、1 通知、2 响应；decode 也可返回 3）。
 * @property command command word 的低七位命令编号。
 * @property payload 头之后的独立负载副本；ByteArray 不承诺内容相等语义。
 */
data class GaiaPacket(
    val vendor: Int,
    val feature: Int,
    val type: Int,
    val command: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GaiaPacket) return false
        return vendor == other.vendor && feature == other.feature && type == other.type &&
            command == other.command && payload === other.payload
    }

    override fun hashCode(): Int {
        var result = vendor
        result = 31 * result + feature
        result = 31 * result + type
        result = 31 * result + command
        return 31 * result + payload.contentHashCode()
    }
}

/** GAIA GATT PDU 编解码；仅四字节大端头与负载，不做流拼帧、校验和或响应成功性判断。 */
object GaiaCodec {
    /** command word 中的请求类型编号。 */
    const val COMMAND = 0
    /** command word 中的非请求通知类型编号。 */
    const val NOTIFICATION = 1
    /** command word 中的请求回复类型编号。 */
    const val RESPONSE = 2

    /**
     * 编码厂商大端字段与 (feature shl 9)|command，复制原始负载。
     * @throws IllegalArgumentException vendor 不在 0..65535，或 feature/command 不在 0..127。
     * @return 四字节头加负载；长度能否写入由 GATT 会话判断。
     */
    fun encode(command: GaiaCommand): ByteArray {
        require(command.vendor in 0..0xFFFF && command.feature in 0..127 && command.command in 0..127)
        val word = (command.feature shl 9) or command.command
        val result = ByteArray(4 + command.payload.size)
        result[0] = (command.vendor shr 8).toByte()
        result[1] = command.vendor.toByte()
        result[2] = (word shr 8).toByte()
        result[3] = word.toByte()
        command.payload.copyInto(result, 4)
        return result
    }

    /** 头不足四字节返回 null；否则拆位并复制剩余负载，type=3 也保留，不判断匹配/拒绝。 */
    fun decode(bytes: ByteArray): GaiaPacket? {
        if (bytes.size < 4) return null
        val vendor = u16be(bytes, 0)
        val word = u16be(bytes, 2)
        return GaiaPacket(vendor, (word shr 9) and 0x7f, (word shr 7) and 0x03,
            word and 0x7f, bytes.copyOfRange(4, bytes.size))
    }

    /**
     * 解出 feature ID 集合：奇数长至少三字节且首字节 0/1 时按 continuation+ID/version 对解析；
     * 否则按每四字节大端位图解析，尾部不足四字节忽略。分页完整性由绑定另行判断。
     */
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
