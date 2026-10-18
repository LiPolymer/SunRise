package ink.lipoly.app.sunrise.drop.moondrop

import ink.lipoly.app.sunrise.drop.AncMode
import ink.lipoly.app.sunrise.drop.BatteryControls
import ink.lipoly.app.sunrise.drop.DropControlSession
import ink.lipoly.app.sunrise.drop.DropProtocol
import ink.lipoly.app.sunrise.drop.EarbudBatteryReading
import ink.lipoly.app.sunrise.drop.EarbudCapabilities
import ink.lipoly.app.sunrise.drop.EarbudControls
import ink.lipoly.app.sunrise.drop.NoiseControls

/**
 * 把同一绑定的 GAIA 控件投影为跨协议门面；只做能力判断与委托，不含编解码、重试或状态缓存。
 * 每绑定一个实例，随绑定失效：能力读取与操作都经 [DropControlSession.checkActive] 失败化。
 */
internal class BindingControls(
    private val session: DropControlSession,
    private val gaia: GaiaControls,
) : EarbudControls {

    private val batteryControls = object : BatteryControls {
        override suspend fun read(): EarbudBatteryReading {
            session.checkActive()
            return gaia.getBattery()
        }
    }

    private val noiseControls = object : NoiseControls {
        override suspend fun read(): AncMode {
            session.checkActive()
            return gaia.getAncMode()
        }

        override suspend fun write(mode: AncMode): AncMode {
            session.checkActive()
            return gaia.setAncMode(mode)
        }
    }

    override val capabilities: EarbudCapabilities
        get() {
            session.checkActive()
            val state = session.state.value
            return EarbudCapabilities(
                protocols = state.protocols,
                noiseModes = state.capabilities.ancModes,
                complete = state.capabilities.complete,
            )
        }

    override val battery: BatteryControls?
        get() {
            session.checkActive()
            return batteryControls.takeIf { hasGaia() }
        }

    override val noise: NoiseControls?
        get() {
            session.checkActive()
            return noiseControls.takeIf { hasGaia() }
        }

    /** 协议集合在绑定建立时确定，不随后续探测变化。 */
    private fun hasGaia(): Boolean = DropProtocol.GAIA_BLE in session.state.value.protocols
}
