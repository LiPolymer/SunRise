package ink.lipoly.app.sunrise.drop

/** Splits framed RFCOMM packets and bare GAIA PDUs across arbitrary read boundaries. */
class GaiaRfcommFramer {
    private val pending = ArrayList<Byte>()

    fun reset() = pending.clear()

    fun feed(bytes: ByteArray, endOfBurst: Boolean = false): List<ByteArray> {
        pending.addAll(bytes.toList())
        if (pending.size > 8192) pending.clear()
        val packets = ArrayList<ByteArray>()
        while (pending.isNotEmpty()) {
            if ((pending[0].toInt() and 0xff) == 0xff) {
                if (pending.size < 4) break
                val flags = pending[2].toInt() and 0xff
                val extended = (pending[1].toInt() and 0xff) >= 4 && flags and 2 != 0
                val header = if (extended) 5 else 4
                if (pending.size < header) break
                val payloadSize = if (extended)
                    ((pending[3].toInt() and 0xff) shl 8) or (pending[4].toInt() and 0xff)
                else pending[3].toInt() and 0xff
                val frameSize = header + 4 + payloadSize + if (flags and 1 != 0) 1 else 0
                if (frameSize > 4096) { pending.removeAt(0); continue }
                if (pending.size < frameSize) break
                val pdu = pending.subList(header, header + 4 + payloadSize).toByteArray()
                repeat(frameSize) { pending.removeAt(0) }
                if (GaiaCodec.decode(pdu)?.vendor == 0x001D) packets += pdu
                continue
            }
            if (pending.size < 2) break
            if (pending[0].toInt() != 0 || (pending[1].toInt() and 0xff) != 0x1d) {
                pending.removeAt(0)
                continue
            }
            val next = (4 until pending.size).firstOrNull { i ->
                (pending[i].toInt() and 0xff) == 0xff ||
                    (i + 1 < pending.size && pending[i].toInt() == 0 && (pending[i + 1].toInt() and 0xff) == 0x1d)
            }
            if (next == null && !endOfBurst) break
            val size = next ?: pending.size
            if (size < 4) break
            packets += pending.subList(0, size).toByteArray()
            repeat(size) { pending.removeAt(0) }
        }
        return packets
    }
}
