package ink.lipoly.app.sunrise.drop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 控制器拥有的传输生命周期钩子，不描述传输 I/O。
 *
 * [isLive] 同步守卫旧能力引用；[lost] 在自有传输被替换时关闭控制器，借用会话可为空流；
 * [release] 在 [SessionController.close] 中于 `NonCancellable` 内最多执行一次。
 */
internal class SessionLifecycle(
    val isLive: () -> Boolean = { true },
    val lost: Flow<Unit> = emptyFlow(),
    val release: suspend () -> Unit = {},
)

/** Construction state shared with delegated family interfaces before their owner is created. */
internal class ControllerContext(
    protocols: Set<DropProtocol>,
    val lifecycle: SessionLifecycle,
    private val current: () -> Boolean,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val closed = MutableStateFlow(false)
    val mutableState = MutableStateFlow(DropState())
    private val mutableEvents = MutableSharedFlow<DropEvent>(extraBufferCapacity = 32)
    val state = mutableState.asStateFlow()
    val events = mutableEvents.asSharedFlow()
    private val isCurrent: Boolean
        get() = !closed.value && current() && lifecycle.isLive()

    val control = ControlSession(
        parentScope = scope,
        protocols = protocols,
        current = { isCurrent },
        publishState = { if (isCurrent) mutableState.value = it },
        publishEvent = { if (isCurrent) mutableEvents.tryEmit(it) },
    )
}

/** Common flow and retirement; protocol and transport work stay outside this class. */
internal open class SessionController(private val context: ControllerContext) : IBudsController {
    final override val state = context.state
    final override val events = context.events

    internal fun observeLoss() {
        context.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            context.lifecycle.lost.collect {
                if (!context.closed.value) close()
            }
        }
    }

    final override suspend fun close() {
        if (!context.closed.compareAndSet(expect = false, update = true)) return
        context.control.close()
        context.scope.cancel()
        context.mutableState.value = DropState()
        withContext(NonCancellable) { runCatching { context.lifecycle.release() } }
    }
}
