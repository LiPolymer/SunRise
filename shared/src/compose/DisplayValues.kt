package ink.lipoly.app.sunrise.compose

import ink.lipoly.app.sunrise.drop.AncMode

/** 设备真实 ANC 状态的中文显示值。 */
internal fun AncMode.display(): String = when (this) {
    AncMode.OFF -> "关闭"
    AncMode.NOISE_CANCELLING -> "降噪"
    AncMode.TRANSPARENCY -> "通透"
    AncMode.WIND -> "抗风噪"
    AncMode.ADAPTIVE -> "自适应"
    AncMode.LIVE -> "Live"
}
