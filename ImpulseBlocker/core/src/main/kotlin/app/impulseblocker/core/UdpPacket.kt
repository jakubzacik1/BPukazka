package app.impulseblocker.core

/**
 * An IPv4 or IPv6 UDP datagram read from (or written to) the TUN interface.
 * Only unfragmented packets without IPv6 extension headers are supported; DNS
 * queries from Android's resolver always fit that shape.
 */
class UdpPacket(
    val ipVersion: Int,
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
) {

    /** Builds the reply datagram (addresses and ports swapped) carrying [replyPayload]. */
    fun buildReply(replyPayload: ByteArray): ByteArray =
        build(ipVersion, dstAddr, srcAddr, dstPort, srcPort, replyPayload)

    companion object {
        const val PROTO_UDP = 17

        fun parse(buf: ByteArray, length: Int): UdpPacket? {
            if (length < 1) return null
            return when ((buf[0].toInt() and 0xFF) shr 4) {
                4 -> parseV4(buf, length)
                6 -> parseV6(buf, length)
                else -> null
            }
        }

        private fun parseV4(b: ByteArray, length: Int): UdpPacket? {
            if (length < 20) return null
            val ihl = (b[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl + 8) return null
            val totalLen = DnsMessage.u16(b, 2)
            if (totalLen > length || totalLen < ihl + 8) return null
            val flagsFrag = DnsMessage.u16(b, 6)
            if (flagsFrag and 0x3FFF != 0) return null // fragmented (MF set or offset != 0)
            if ((b[9].toInt() and 0xFF) != PROTO_UDP) return null
            val src = b.copyOfRange(12, 16)
            val dst = b.copyOfRange(16, 20)
            val srcPort = DnsMessage.u16(b, ihl)
            val dstPort = DnsMessage.u16(b, ihl + 2)
            val udpLen = DnsMessage.u16(b, ihl + 4)
            if (udpLen < 8 || ihl + udpLen > totalLen) return null
            val payload = b.copyOfRange(ihl + 8, ihl + udpLen)
            return UdpPacket(4, src, dst, srcPort, dstPort, payload)
        }

        private fun parseV6(b: ByteArray, length: Int): UdpPacket? {
            if (length < 48) return null
            val payloadLen = DnsMessage.u16(b, 4)
            if (40 + payloadLen > length) return null
            if ((b[6].toInt() and 0xFF) != PROTO_UDP) return null // extension headers unsupported
            val src = b.copyOfRange(8, 24)
            val dst = b.copyOfRange(24, 40)
            val srcPort = DnsMessage.u16(b, 40)
            val dstPort = DnsMessage.u16(b, 42)
            val udpLen = DnsMessage.u16(b, 44)
            if (udpLen < 8 || udpLen > payloadLen) return null
            val payload = b.copyOfRange(48, 40 + udpLen)
            return UdpPacket(6, src, dst, srcPort, dstPort, payload)
        }

        fun build(
            ipVersion: Int,
            src: ByteArray,
            dst: ByteArray,
            srcPort: Int,
            dstPort: Int,
            payload: ByteArray,
        ): ByteArray {
            val udpLen = 8 + payload.size
            return if (ipVersion == 4) {
                val out = ByteArray(20 + udpLen)
                out[0] = 0x45
                DnsMessage.put16(out, 2, out.size)
                DnsMessage.put16(out, 6, 0x4000) // DF
                out[8] = 64
                out[9] = PROTO_UDP.toByte()
                System.arraycopy(src, 0, out, 12, 4)
                System.arraycopy(dst, 0, out, 16, 4)
                DnsMessage.put16(out, 10, checksum(out, 0, 20, 0))
                writeUdp(out, 20, srcPort, dstPort, payload)
                val pseudo = pseudoSum(src, dst, udpLen)
                val c = checksum(out, 20, udpLen, pseudo)
                DnsMessage.put16(out, 26, if (c == 0) 0xFFFF else c)
                out
            } else {
                val out = ByteArray(40 + udpLen)
                out[0] = 0x60
                DnsMessage.put16(out, 4, udpLen)
                out[6] = PROTO_UDP.toByte()
                out[7] = 64
                System.arraycopy(src, 0, out, 8, 16)
                System.arraycopy(dst, 0, out, 24, 16)
                writeUdp(out, 40, srcPort, dstPort, payload)
                val pseudo = pseudoSum(src, dst, udpLen)
                val c = checksum(out, 40, udpLen, pseudo)
                DnsMessage.put16(out, 46, if (c == 0) 0xFFFF else c)
                out
            }
        }

        private fun writeUdp(out: ByteArray, off: Int, srcPort: Int, dstPort: Int, payload: ByteArray) {
            DnsMessage.put16(out, off, srcPort)
            DnsMessage.put16(out, off + 2, dstPort)
            DnsMessage.put16(out, off + 4, 8 + payload.size)
            DnsMessage.put16(out, off + 6, 0)
            System.arraycopy(payload, 0, out, off + 8, payload.size)
        }

        private fun pseudoSum(src: ByteArray, dst: ByteArray, udpLen: Int): Long {
            var sum = 0L
            var i = 0
            while (i < src.size) { sum += DnsMessage.u16(src, i); i += 2 }
            i = 0
            while (i < dst.size) { sum += DnsMessage.u16(dst, i); i += 2 }
            sum += PROTO_UDP
            sum += udpLen
            return sum
        }

        /** RFC 1071 Internet checksum over [len] bytes starting at [off], plus [initial]. */
        fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
            var sum = initial
            var i = 0
            while (i + 1 < len) { sum += DnsMessage.u16(b, off + i); i += 2 }
            if (i < len) sum += (b[off + i].toInt() and 0xFF) shl 8
            while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
            return (sum.inv() and 0xFFFF).toInt()
        }
    }
}
