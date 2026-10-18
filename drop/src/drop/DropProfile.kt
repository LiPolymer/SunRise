package ink.lipoly.app.sunrise.drop

/** 配置身份匹配，不是设备发现筛选；由控制器的 profileDevice 提供地址和名称。 */
sealed interface DropProfileMatch {
    /**
     * 地址全等匹配，忽略大小写；覆盖项中地址命中优先于名称命中。
     * @property value 配置身份地址；本层不规范化或校验 MAC 格式。
     */
    data class Address(val value: String) : DropProfileMatch
    /**
     * 非空白子串匹配，忽略大小写；不宣称同名等于同一设备。
     * @property value 名称子串，空白不匹配。
     */
    data class NameContains(val value: String) : DropProfileMatch
}

/**
 * 设备 ANC/DAC 编号映射；读写必须独立，因为固件可能对 SET/GET 使用不同编号。
 * 地址覆盖优先；选中覆盖项的 null 字段继承内置匹配配置，再由各路径使用默认映射。
 * 非 null 空 map 不等于继承。ANC 读 map 缺少某编号时仍尝试逆查写 map。
 *
 * @property match 音频/配置身份的匹配规则，不改变通讯设备。
 * @property audioCurationWrite AUDIO_CURATION 模式到写编号；默认 OFF/NC/TRANSPARENCY/WIND=1/2/3/4。
 * @property audioCurationRead AUDIO_CURATION 读编号到逻辑模式；null 或未命中时逆查写映射。
 * @property ancV2Write ANC_V2 模式到写编号；默认 OFF/NC/TRANSPARENCY/WIND/ADAPTIVE/LIVE=0..5。
 * @property ancV2Read ANC_V2 读编号到逻辑模式；null 或未命中时逆查写映射。
 * @property gainWrite DAC 档位到编号，同时供读回逆查；自定义时须含所有 GainLevel，默认 LOW/MEDIUM/HIGH=0/1/2。
 */
data class DropProfile(
    val match: DropProfileMatch,
    val audioCurationWrite: Map<AncMode, Int>? = null,
    val audioCurationRead: Map<Int, AncMode>? = null,
    val ancV2Write: Map<AncMode, Int>? = null,
    val ancV2Read: Map<Int, AncMode>? = null,
    val gainWrite: Map<GainLevel, Int>? = null,
)

/**
 * 协议配置选项；不负责发现、连接、品牌筛选或重试。
 * @property profileOverrides 自定义设备映射；匹配地址优先，同类按列表首项，缺省字段可继承内置配置。
 */
data class DropOptions(val profileOverrides: List<DropProfile> = emptyList())
