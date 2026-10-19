package ink.lipoly.app.sunrise.bluetooth

/**
 * 写入时的入站处理规则；双向协议应使用 [PRESERVE]，只写兼容路径可显式选择排空。
 */
enum class RfcommInputPolicy {
    /** 所有未读取入站字节保留，包含两个 read 之间收到的数据。 */
    PRESERVE,
    /** 无活跃读取者时，写入前尽力排空当前缓冲；排空失败不冒充写入失败。 */
    DRAIN_BEFORE_WRITE,
}

/**
 * 经典蓝牙 RFCOMM 字节通道：Android 按调用方指定的完整服务 UUID 执行 SDP 连接，JVM 桌面句柄为 null。
 *
 * 通道只搬运任意字节片段，不解释厂商帧、不匹配请求/回复，不自动选择其他服务。
 * 打开或写入取消会释放可疑 socket；读取取消只结束本次等待，不关闭连接。
 */
interface RfcommStream {
    /** 通道远端的规范大写地址，仅用于诊断，不用于会话身份匹配。 */
    val address: String
    /**
     * 打开指定服务与入站策略；同一 UUID（忽略大小写）及策略重复打开幂等。
     * 切换任一配置前必须 [close]，不替换当前连接、不自动猜测其他服务。
     * @param serviceUuid 完整 8-4-4-4-12 十六进制 UUID；不接受短 UUID、空白或补零。
     * @param inputPolicy 写入时保留入站字节或在无读取者时尽力排空。
     * @throws IllegalArgumentException UUID 格式非法；创建 socket 前拒绝。
     * @throws IllegalStateException 当前连接使用不同的 UUID 或策略，原连接保持不变。
     * @throws BtException 权限不足、找不到服务记录或连接失败。
     */
    suspend fun open(serviceUuid: String, inputPolicy: RfcommInputPolicy)
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
