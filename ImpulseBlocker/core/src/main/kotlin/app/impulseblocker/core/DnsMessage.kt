package app.impulseblocker.core

/**
 * Minimal DNS wire-format helpers (RFC 1035). Only what the local filter needs:
 * read the question, synthesize an NXDOMAIN answer, and clamp TTLs.
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41
    const val RCODE_NXDOMAIN = 3
    const val RCODE_SERVFAIL = 2

    data class Question(
        val id: Int,
        val flags: Int,
        val name: String,
        val type: Int,
        val qclass: Int,
        /** Offset just after the first question in the original message. */
        val questionEnd: Int,
    ) {
        val isQuery: Boolean get() = (flags and 0x8000) == 0
        val opcode: Int get() = (flags shr 11) and 0xF
    }

    /** Parses the header and first question. Returns null for malformed input. */
    fun parseQuestion(msg: ByteArray, length: Int = msg.size): Question? {
        if (length < 12) return null
        val id = u16(msg, 0)
        val flags = u16(msg, 2)
        val qdCount = u16(msg, 4)
        if (qdCount < 1) return null
        val sb = StringBuilder()
        var pos = 12
        var labels = 0
        while (true) {
            if (pos >= length) return null
            val len = msg[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (len and 0xC0 != 0) return null // compression is not used in questions
            if (pos + 1 + len > length) return null
            if (++labels > 127) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) {
                val c = (msg[pos + 1 + i].toInt() and 0xFF).toChar()
                sb.append(c.lowercaseChar())
            }
            pos += 1 + len
        }
        if (pos + 4 > length) return null
        val type = u16(msg, pos)
        val qclass = u16(msg, pos + 2)
        return Question(id, flags, sb.toString(), type, qclass, pos + 4)
    }

    /**
     * Builds a response carrying only the question and the given rcode.
     * No SOA record is included, so resolvers do not cache the negative answer;
     * this matters when a temporary unlock starts.
     */
    fun buildErrorResponse(query: ByteArray, q: Question, rcode: Int): ByteArray {
        val out = ByteArray(q.questionEnd)
        System.arraycopy(query, 0, out, 0, q.questionEnd)
        // QR=1, keep opcode and RD, RA=1, rcode
        val rd = q.flags and 0x0100
        val flags = 0x8000 or (q.opcode shl 11) or rd or 0x0080 or (rcode and 0xF)
        put16(out, 2, flags)
        put16(out, 4, 1) // QDCOUNT
        put16(out, 6, 0)
        put16(out, 8, 0)
        put16(out, 10, 0)
        return out
    }

    /**
     * Lowers every resource-record TTL above [maxTtl] to [maxTtl] in place.
     * Used for domains that are only temporarily unlocked so that apps do not
     * keep a cached address long after the unlock expires.
     * Returns false if the message could not be parsed (it is then left untouched
     * from the failing record onward).
     */
    fun clampTtls(msg: ByteArray, length: Int, maxTtl: Long): Boolean {
        if (length < 12) return false
        val qd = u16(msg, 4)
        val rrCount = u16(msg, 6) + u16(msg, 8) + u16(msg, 10)
        var pos = 12
        repeat(qd) {
            pos = skipName(msg, length, pos) ?: return false
            pos += 4
        }
        repeat(rrCount) {
            pos = skipName(msg, length, pos) ?: return false
            if (pos + 10 > length) return false
            val type = u16(msg, pos)
            if (type != TYPE_OPT) {
                val ttl = u32(msg, pos + 4)
                if (ttl > maxTtl) put32(msg, pos + 4, maxTtl)
            }
            val rdLen = u16(msg, pos + 8)
            pos += 10 + rdLen
            if (pos > length) return false
        }
        return true
    }

    private fun skipName(msg: ByteArray, length: Int, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= length) return null
            val len = msg[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return if (pos + 2 <= length) pos + 2 else null
                len and 0xC0 != 0 -> return null
                else -> pos += 1 + len
            }
        }
    }

    /** Encodes a DNS query (used by tests and diagnostics). */
    fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val labels = name.trimEnd('.').split('.')
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val out = ByteArray(size)
        put16(out, 0, id)
        put16(out, 2, 0x0100) // RD
        put16(out, 4, 1)
        var pos = 12
        for (l in labels) {
            out[pos++] = l.length.toByte()
            for (c in l) out[pos++] = c.code.toByte()
        }
        out[pos++] = 0
        put16(out, pos, type); put16(out, pos + 2, 1)
        return out
    }

    fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte()
    }

    fun put32(b: ByteArray, o: Int, v: Long) {
        put16(b, o, (v shr 16).toInt() and 0xFFFF); put16(b, o + 2, v.toInt() and 0xFFFF)
    }
}
