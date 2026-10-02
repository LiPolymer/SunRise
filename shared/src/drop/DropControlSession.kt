package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.flow.StateFlow

/**
 * 平台无关控件所需的单会话接口，绝不能包装成动态转发到“当前连接”的 facade。
 * profile、ANC 路径、请求与 mutate 都属于同一个 epoch；旧控件调用必须失败而非转到新会话。
 */
internal interface DropControlSession {
    /** 此 epoch 的快照，不是全局设备状态。 */
    val state: StateFlow<DropState>

    /** 返回本绑定初始化时解析的配置，失效绑定抛 Disconnected。 */
    fun profile(): DropProfile
    /** 返回探测选中的 AC/V2/V1 路径，未知路径由控件拒绝。 */
    fun ancPath(): AncPath
    /** 条件更新本绑定状态并发布；变更前后核对当前 session，禁止退休绑定覆盖状态。 */
    fun mutate(block: (DropState) -> DropState)

    /** 单事务请求，同 vendor/feature/command RESPONSE 最多等待六秒。 */
    suspend fun requestGaia(command: GaiaCommand): GaiaPacket
    /** 只完成传输写，不等待 GAIA ACK，不能据此报告已应用。 */
    suspend fun sendGaia(command: GaiaCommand)
    /** 单事务请求，同 commandId/sequence RESPONSE 最多等待六秒，返回原始负载。 */
    suspend fun requestSource(commandId: Int, payload: ByteArray): ByteArray
    /** 直接读取 capability 特征，缺失时拒绝；与协议请求同受 epoch/caller cancellation 保护。 */
    suspend fun readSourceCapability(): ByteArray
    /** 直接读取 info 特征，缺失时拒绝；固件 fallback 由调用者决定。 */
    suspend fun readSourceInfo(): ByteArray
}
