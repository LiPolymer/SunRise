package ink.lipoly.app.sunrise.catalog

import java.security.MessageDigest

internal actual fun catalogSha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val digits = "0123456789abcdef"
    val hexadecimal = CharArray(digest.size * 2)
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xff
        hexadecimal[index * 2] = digits[value ushr 4]
        hexadecimal[index * 2 + 1] = digits[value and 0x0f]
    }
    return hexadecimal.concatToString()
}
