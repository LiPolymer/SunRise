package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * ANC 只发送一次 SET，不要求其 ACK：等待应用后最多四次 GET，成功必须读回目标逻辑模式。
 * 首次 GET 前等待 300ms，失配重试之间 500ms；只重复读取，不重发 SET。
 * 读回异常包装 Unverified 并保留 cause；取消、Disconnected、NotReady 原样传播。
 * 四次均失配抛 AncModeMismatch，最后实际值已经由 GET 写入状态；
 * Unverified 的清空状态责任在 GaiaControlsImpl。
 * @param requested 待确认的逻辑 ANC 模式。
 * @param write 仅完成一次传输写的动作；写入异常直接抛出。
 * @param read 获取并保存实际逻辑模式的动作。
 * @param wait 毫秒等待函数，默认协程 delay，保留调用方取消。
 */
internal suspend fun writeAndVerifyAncMode(
    requested: AncMode,
    write: suspend () -> Unit,
    read: suspend () -> AncMode,
    wait: suspend (Long) -> Unit = { delay(it.milliseconds) },
): AncMode {
    write()
    wait(300)

    repeat(4) { attempt ->
        val observed = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DropException.Disconnected) {
            throw e
        } catch (e: DropException.NotReady) {
            throw e
        } catch (e: Exception) {
            throw DropException.Unverified("ANC mode", e)
        }
        if (observed == requested) return observed
        if (attempt < 3) wait(500)
        else throw DropException.AncModeMismatch(requested, observed)
    }
    error("Unreachable ANC verification state")
}
