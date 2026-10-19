package ink.lipoly.app.sunrise.headset

/**
 * 应用私有的音频地址到 GATT 端点地址关联存储，不参与通用蓝牙设备身份管理。
 *
 * 存储命中只决定优先尝试顺序；仅在协议 READY 且端点仍有效时记住新关联。
 */
internal interface HeadsetAssociations {
    /**
     * 查询先前成功采用的端点；调用方使用大写音频地址，返回值仍须由管理器校验。
     * @param address 所选音频设备地址。
     * @return 已存端点地址，无记录时为空；记录不保证端点仍可连接。
     */
    fun endpoint(address: String): String?
    /**
     * 在端点采纳后保存关联；持久化时机由平台实现决定，不表示物理身份认证。
     * @param address 用于显示和 profile 匹配的音频地址。
     * @param endpoint 本次已完成控制器初始化的 GATT 地址。
     */
    fun remember(address: String, endpoint: String)
}
