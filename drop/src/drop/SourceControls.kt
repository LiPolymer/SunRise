package ink.lipoly.app.sunrise.drop

/**
 * 当前 READY 绑定的 9ECA 控件。响应首字节是状态码；一般非零抛 Rejected，
 * 音源 SET 的 5/9 例外。SET 返回响应值，并非一律独立 GET 验证。
 */
interface SourceControls {
    /** 查询并保存音源状态；普通请求首字节非零抛 Rejected。 */
    suspend fun getAudioSource(): SourceStatus
    /**
     * 发送音源切换，先保存响应，必要时每 200ms 查询直到稳定或超时。
     * @param sourceId 0..255，应来自 [SourceEntry.sourceId]，不能从编号猜测设备支持。
     * @param options fadeSeconds 须为 0..60；awaitStable 时期限为 (max(fadeSeconds,1)+3) 秒。
     * @return 响应/等待结果；稳定成功需 statusCode 为 0/9、transitionState=0 且 currentSource 匹配。
     * 等待遇到非 0/5/9 会退出，最终非 0/9 抛 Rejected；9 的返回不一定已稳定，仍应检查字段。
     */
    suspend fun setAudioSource(sourceId: Int, options: SourceSwitchOptions = SourceSwitchOptions()): SourceStatus
    /** 查询 [page]（0..15）；每页最多五项。空 entries 合法，不代表可以硬编码音源。 */
    suspend fun getCapabilityPage(page: Int): SourceCapabilityPage
    /** 直接读取可读 capability 特征（最多八项）；特征缺失/不可读抛 UnsupportedCapability。 */
    suspend fun readSourceCapability(): SourceCapability
    /** 优先读取 info 特征；解析/读取失败时退到 GET_FW_VERSION，取消透传；更新 sourceFeatures。 */
    suspend fun getFirmwareInfo(): SourceFirmwareInfo
    /** 查询音量并保存响应字段；范围/步进由设备提供，不假定百分比或 dB。 */
    suspend fun getVolume(): SourceVolume
    /** [mode]、[value]、[flags] 均须为 0..255；返回 SET 响应并保存 volume，不额外 GET。 */
    suspend fun setVolume(mode: Int, value: Int, flags: Int = 0): SourceVolume
    /** 查询并保存当前预设、数量及可编辑预设编号。 */
    suspend fun getPresetEq(): SourcePresetEq
    /** 设置 [index]（0..255），返回响应的前后编号；仅更新已有 state.presetEq.current，不额外 GET。 */
    suspend fun setPresetEq(index: Int): SourcePresetEqChange
    /** 查询用户 PEQ 的预设、点数、revision、前置增益和 dirty；不写入 DropState。 */
    suspend fun getPeqConfig(): SourcePeqConfig
    /** 设置用户预设 7 的前置增益，[centiDb] 为百分之一 dB，范围 -12800..12799；返回 ACK 字段。 */
    suspend fun setPeqPreGain(centiDb: Int): SourcePeqPreGain
    /** 查询 [index]（0..31）的 PEQ 点；返回原始 Q/filter 数据，不更新 DropState。 */
    suspend fun getPeqPoint(index: Int): SourcePeqPoint
    /**
     * 写入并返回 PEQ 点响应，不额外 GET。
     * @param point index=0..31，frequencyHz=20..20000，gainCentiDb=-32768..32767，
     * qRaw=1..65535，filterId=0..7；越界抛 IllegalArgumentException，不推断 Q 缩放/滤波器名称。
     */
    suspend fun setPeqPoint(point: SourcePeqPoint): SourcePeqPoint
    /** 提交 PEQ，[action] 为 0..2、[revision] 为 0..65535；只返回设备响应，不推断动作语义。 */
    suspend fun commitPeq(action: Int, revision: Int): SourcePeqCommit
    /** 查询并保存麦克风增益；各字段单位为十分之一 dB。 */
    suspend fun getMicGain(): SourceMicGain
    /** 设置 [deciDb]（-1280..1280，十分之一 dB），保存 SET 响应，不额外 GET。 */
    suspend fun setMicGain(deciDb: Int): SourceMicGain
    /** 返回颜色的原始无符号字节 ID，不推断颜色名称。 */
    suspend fun getEarbudColor(): Int
    /** 返回语言的原始无符号字节 ID，不推断语言名称。 */
    suspend fun getEarbudLanguage(): Int
    /** 查询 [side] 的偏移 0/10 两个序列号块并拼成 20 字节；不解码文本，结构不匹配抛 Protocol。 */
    suspend fun getEarbudSerial(side: EarbudSide): ByteArray
    /** 等待 PING 成功状态回复；不更新状态，不证明其他功能受支持。 */
    suspend fun ping()
}
