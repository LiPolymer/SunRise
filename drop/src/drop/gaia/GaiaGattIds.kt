package ink.lipoly.app.sunrise.drop.gaia

/** GAIA 完整小写 UUID 属于协议层；通用 bluetooth 不依赖这些协议标识。 */
internal object GaiaGattIds {
    const val GAIA_SERVICE = "00001100-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_COMMAND = "00001101-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_RESPONSE = "00001102-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_DATA = "00001103-d102-11e1-9b23-00025b00a5a5"
}
