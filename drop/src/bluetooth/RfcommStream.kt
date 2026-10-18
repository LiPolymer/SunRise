package ink.lipoly.app.sunrise.bluetooth

/**
 * 经典蓝牙 RFCOMM 字节通道：Android 按 SPP 服务记录解析通道后连接，JVM 桌面不提供实现。
 *
 * 通道只搬运字节，不解释 GAIA 帧、不做请求/响应匹配或流拼帧，帧边界由调用方保证。
 * 打开时自动完成配对校验与 SDP 查询；取消等待不关闭已开始的平台连接，[close] 才释放 socket。
 */
interface RfcommStream {
    /** 通道远端的规范大写地址，仅用于诊断，不用于会话身份匹配。 */
    val address: String
    /**
     * 打开通道；已打开时直接返回。
     * @throws BtException 权限不足、对端未配对、找不到服务记录或连接失败。
     */
    suspend fun open()
    /**
     * 向已打开的通道写入一段字节并刷新。
     * @throws BtException 尚未打开、已关闭或平台写入失败。
     */
    suspend fun write(bytes: ByteArray)
    /**
     * 读取当前可用的一段入站字节；无数据时挂起等待，取消只结束本次等待，不关闭通道。
     * 一次返回不保证是一帧：组帧、拼接、超时与重同步由调用方负责。
     * @throws BtException 尚未打开、已关闭或平台读取失败。
     */
    suspend fun read(): ByteArray
    /** 幂等关闭通道并释放平台 socket；未打开或已关闭时不做任何事。 */
    suspend fun close()
}
