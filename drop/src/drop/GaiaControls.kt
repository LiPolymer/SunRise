package ink.lipoly.app.sunrise.drop

/**
 * 当前 READY 绑定的 GAIA 控件；换会话后必须重新从 [DropController.gaia] 获取。
 * ANC/codec/PEQ 的 SET 不等待 ACK，而以 GET 确认；其他大部分 SET 等回复再独立 GET。
 * 功能存在性不由 getter 保证，缺少 GAIA 时操作抛 [DropException.UnsupportedCapability]。
 */
interface GaiaControls {
    /** 读取组件 ID/电量字节对并合并到 state.battery；空、奇数长度负载抛 Protocol。 */
    suspend fun getBattery(): EarbudBatteryReading
    /** 用当前 ANC 路径和读映射获取模式、更新 state.ancMode；未知编号抛 Protocol。 */
    suspend fun getAncMode(): AncMode
    /**
     * 只发送一次 SET，不等待 SET ACK，随后最多四次 GET 确认。
     * @param mode 必须存在于当前配置可写模式；否则抛 UnsupportedCapability。
     * @return 确认的逻辑模式；Unverified 清空状态，AncModeMismatch 保留实际读回。
     */
    suspend fun setAncMode(mode: AncMode): AncMode
    /** 读取 DAC 编号、按配置逆映射，更新 state.gain；未知编号抛 Protocol。 */
    suspend fun getGain(): GainLevel
    /** 按配置写入 [level]，等待 SET 回复后 GET 读回；返回并保存实际档位。 */
    suspend fun setGain(level: GainLevel): GainLevel
    /** 读取 LED 开关，非零为 true，更新 state.ledOn。 */
    suspend fun isLedOn(): Boolean
    /** 写入 0/1，等待 SET 回复后 GET 读回，返回并保存实际 LED 开关。 */
    suspend fun setLedOn(on: Boolean): Boolean
    /** 读取空间音效开关，非零为 true，更新 state.spatialOn。 */
    suspend fun isSpatialOn(): Boolean
    /** 写入 0/1，等待 SET 回复后 GET 读回，返回并保存实际空间音效开关。 */
    suspend fun setSpatialOn(on: Boolean): Boolean
    /** 读取 0..2 的头部追踪模式并更新 state.headTracking；未知编号抛 Protocol。 */
    suspend fun getHeadTracking(): HeadTrackingMode
    /** 写入枚举编号、等待 SET 回复后 GET 读回，返回并保存实际追踪模式。 */
    suspend fun setHeadTracking(mode: HeadTrackingMode): HeadTrackingMode
    /** Strict 0/1 GET; updates codecStates. Missing key means unknown, not disabled. */
    suspend fun isCodecEnabled(codec: AudioCodec): Boolean
    /** Sends SET then GET in one transaction; mismatch preserves actual, failed readback clears key. */
    suspend fun setCodecEnabled(codec: AudioCodec, enabled: Boolean): Boolean
    /** 查询动态低音开关，非零为 true；不更新 DropState。 */
    suspend fun isDynamicBassOn(): Boolean
    /** 设置动态低音 0/1，等待 SET 回复后 GET 读回；不更新 DropState。 */
    suspend fun setDynamicBassOn(on: Boolean): Boolean
    /** 查询左右声道反转开关，非零为 true；不更新 DropState。 */
    suspend fun isLeftRightReversed(): Boolean
    /** 设置左右反转 0/1，等待 SET 回复后 GET 读回；不更新 DropState。 */
    suspend fun setLeftRightReversed(reversed: Boolean): Boolean
    /** 返回 EQ 预设的无符号字节编号；不推断预设名称，不更新 DropState。 */
    suspend fun getEqualizerPreset(): Int
    /** 设置 [index]（0..255），等待 SET 回复后 GET 读回；越界抛 IllegalArgumentException。 */
    suspend fun setEqualizerPreset(index: Int): Int
    /** Reads complete GAIA Bluetrum USER63 configuration; unsupported/malformed readback clears paramEq. */
    suspend fun getParamEq(): GaiaParamEqState
    /**
     * Selects USER63 first, waits 100 ms, then sends one complete peaking-only snapshot using
     * Bluetrum type 0 and automatic whole-EQ attenuation, mirroring the official apply order.
     * When the binding has a classic RFCOMM channel, frames go over it (official transport);
     * otherwise they go over BLE GATT. No preset query, automatic readback or Flash save;
     * transport completion does not prove DSP application.
     */
    suspend fun setParamEq(bands: List<GaiaPeqBand>)
    /** 查询原始手势配置；[gesture]、[context] 均须为 0..255，返回未进一步解释的包。 */
    suspend fun getGestureConfiguration(gesture: Int, context: Int): GaiaPacket
    /** 请求恢复手势配置并返回匹配响应；不声明设备已持久化或读回验证。 */
    suspend fun resetGestureConfiguration(): GaiaPacket
    /**
     * 查询基础信息，返回原始响应，不更新状态。
     * @param command 仅接受 [GaiaIds.Basic] 的 VERSION/FEATURES/SERIAL/VARIANT/APP_VERSION/
     * COLOR/LANGUAGE/LEFT_SN/RIGHT_SN/TWS_STATUS；其他值抛 IllegalArgumentException。
     */
    suspend fun getBasicInfo(command: Int): GaiaPacket
    /**
     * 查询 AUDIO_CURATION 原始数据，不自动解释或写入状态。
     * @param command 允许 0,2,3,5,7,8,10,12,13,15,17,18,20,22,23,25,26,28,30,31,33,34,35,37,38,39,41。
     */
    suspend fun getAudioCuration(command: Int): GaiaPacket
    /**
     * 设置 AUDIO_CURATION 原始数据，仅等待匹配响应，不另做 GET 确认。
     * @param command 允许 1,4,6,9,11,14,16,19,21,24,27,29,32,36,40,42。
     * @param payload 必须非空；提交前复制，不解释字段单位。
     */
    suspend fun setAudioCuration(command: Int, payload: ByteArray): GaiaPacket
    /** 仅发送关机命令；返回表示传输写完成，不表示耳机已经关机，不主动断开 GATT。 */
    suspend fun powerOff()
    /** 编码并写入 [command]，等待同 vendor/feature/command 的 RESPONSE；不额外解释状态字节。 */
    suspend fun requestRaw(command: GaiaCommand): GaiaPacket
    /** 编码并写入 [command] 后返回，不等待协议响应，不证明命令已应用。 */
    suspend fun sendRaw(command: GaiaCommand)
}
