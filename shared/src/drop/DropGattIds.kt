package ink.lipoly.app.sunrise.drop

/** Protocol UUIDs belong to Drop, not to the generic Bluetooth transport. */
internal object DropGattIds {
    const val GAIA_SERVICE = "00001100-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_COMMAND = "00001101-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_RESPONSE = "00001102-d102-11e1-9b23-00025b00a5a5"
    const val GAIA_DATA = "00001103-d102-11e1-9b23-00025b00a5a5"
    const val SOURCE_SERVICE = "9eca0000-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_COMMAND = "9eca0001-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_RESPONSE = "9eca0002-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_NOTIFICATION = "9eca0003-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_CAPABILITY = "9eca0004-7f3a-4f32-9a38-a91b2c6e0100"
    const val SOURCE_INFO = "9eca0005-7f3a-4f32-9a38-a91b2c6e0100"
}
