package ink.lipoly.app.sunrise.drop

/**
 * GAIA 经典 RFCOMM 组帧：`SOF 04 00 <payloadLength>` 加标准 GAIA 帧（`00 1D <feature> <command> <payload>`）。
 *
 * 实机抓包（官版 2.25 应用社区预设）逐字节确认：SOF=`0xFF`、版本=`0x04`、标志=`0x00`，
 * 长度字节只计 GAIA 负载，即 GAIA 帧长度减 4；单帧长度字节上限 0x7F，因此一个写入块最多 7 段。
 * RFCOMM 自身的地址/控制/长度/校验由平台栈生成，本层不生成，也不做流拼帧与响应匹配。
 */
internal object GaiaRfcomm {
    /** 帧起始字节。 */
    const val SOF = 0xFF

    /** 传输版本字节。 */
    const val VERSION = 0x04

    /** 标志字节；抓包中恒为 0。 */
    const val FLAGS = 0x00

    /** 单字节长度字段能表示的最大 GAIA 负载。 */
    const val MAX_PAYLOAD = 0x7F

    /**
     * 把一条 GAIA 命令编码成经典通道上的完整帧。
     * @throws IllegalArgumentException 负载超过 [MAX_PAYLOAD]，无法用单字节长度表示。
     */
    fun frame(command: GaiaCommand): ByteArray {
        val gaia = GaiaCodec.encode(command)
        val payload = gaia.size - 4
        require(payload <= MAX_PAYLOAD) { "GAIA payload $payload exceeds RFCOMM single-byte length" }
        val result = ByteArray(4 + gaia.size)
        result[0] = SOF.toByte()
        result[1] = VERSION.toByte()
        result[2] = FLAGS.toByte()
        result[3] = payload.toByte()
        gaia.copyInto(result, 4)
        return result
    }
}
