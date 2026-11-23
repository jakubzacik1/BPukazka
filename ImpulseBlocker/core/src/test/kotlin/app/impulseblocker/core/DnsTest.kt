package app.impulseblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTest {

    @Test fun parsesQuestion() {
        val q = DnsMessage.parseQuestion(DnsMessage.buildQuery(0x1234, "WWW.Wolt.com", DnsMessage.TYPE_AAAA))!!
        assertEquals(0x1234, q.id)
        assertEquals("www.wolt.com", q.name)
        assertEquals(DnsMessage.TYPE_AAAA, q.type)
        assertTrue(q.isQuery)
    }

    @Test fun rejectsTruncatedMessages() {
        val full = DnsMessage.buildQuery(1, "wolt.com", 1)
        for (len in 0 until full.size) assertNull(DnsMessage.parseQuestion(full, len))
    }

    @Test fun buildsNxdomain() {
        val query = DnsMessage.buildQuery(0xBEEF, "wolt.com", DnsMessage.TYPE_A)
        val q = DnsMessage.parseQuestion(query)!!
        val resp = DnsMessage.buildErrorResponse(query, q, DnsMessage.RCODE_NXDOMAIN)
        val r = DnsMessage.parseQuestion(resp)!!
        assertEquals(0xBEEF, r.id)
        assertEquals("wolt.com", r.name)
        assertEquals(0x8000, r.flags and 0x8000) // QR
        assertEquals(3, r.flags and 0xF)       // NXDOMAIN
        assertEquals(0x0100, r.flags and 0x0100) // RD copied
        assertEquals(0, DnsMessage.u16(resp, 6))
    }

    @Test fun clampsTtlsWithCompressedNames() {
        val query = DnsMessage.buildQuery(7, "wolt.com", DnsMessage.TYPE_A)
        // Append one A answer: name pointer to offset 12, type A, class IN, TTL 3600, 4 bytes rdata
        val answer = byteArrayOf(
            0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0x0E, 0x10, 0, 4, 1, 2, 3, 4,
        )
        val msg = query + answer
        DnsMessage.put16(msg, 2, 0x8180)
        DnsMessage.put16(msg, 6, 1)
        assertTrue(DnsMessage.clampTtls(msg, msg.size, 30))
        assertEquals(30L, DnsMessage.u32(msg, query.size + 6))
    }

    @Test fun udpV4RoundTripWithValidChecksums() {
        val dns = DnsMessage.buildQuery(9, "a.wolt.com", 1)
        val pkt = UdpPacket.build(4, byteArrayOf(10, 111, 222.toByte(), 1), byteArrayOf(10, 111, 222.toByte(), 2), 40000, 53, dns)
        assertEquals(0, UdpPacket.checksum(pkt, 0, 20, 0)) // header checksum verifies
        val parsed = UdpPacket.parse(pkt, pkt.size)!!
        assertEquals(53, parsed.dstPort)
        assertEquals(40000, parsed.srcPort)
        assertEquals(dns.toList(), parsed.payload.toList())
        val reply = UdpPacket.parse(parsed.buildReply(byteArrayOf(1, 2, 3)).let { it }, 31)!!
        assertEquals(53, reply.srcPort)
        assertEquals(listOf<Byte>(10, 111, 222.toByte(), 2), reply.srcAddr.toList())
    }

    @Test fun udpV6RoundTrip() {
        val src = ByteArray(16) { if (it == 0) 0xFD.toByte() else if (it == 15) 1 else 0 }
        val dst = ByteArray(16) { if (it == 0) 0xFD.toByte() else if (it == 15) 2 else 0 }
        val dns = DnsMessage.buildQuery(10, "wolt.com", DnsMessage.TYPE_AAAA)
        val pkt = UdpPacket.build(6, src, dst, 5353, 53, dns)
        val parsed = UdpPacket.parse(pkt, pkt.size)
        assertNotNull(parsed)
        assertEquals(6, parsed!!.ipVersion)
        assertEquals(dns.toList(), parsed.payload.toList())
        // UDP checksum including pseudo-header must verify to 0
        var pseudo = 0L
        for (i in 0 until 16 step 2) pseudo += DnsMessage.u16(src, i) + DnsMessage.u16(dst, i)
        pseudo += 17 + (8 + dns.size)
        assertEquals(0, UdpPacket.checksum(pkt, 40, 8 + dns.size, pseudo))
    }

    @Test fun ignoresNonUdpAndFragments() {
        val pkt = UdpPacket.build(4, byteArrayOf(1, 1, 1, 1), byteArrayOf(2, 2, 2, 2), 1, 53, byteArrayOf(0))
        pkt[9] = 6 // TCP
        assertNull(UdpPacket.parse(pkt, pkt.size))
        val frag = UdpPacket.build(4, byteArrayOf(1, 1, 1, 1), byteArrayOf(2, 2, 2, 2), 1, 53, byteArrayOf(0))
        DnsMessage.put16(frag, 6, 0x2000) // MF
        assertNull(UdpPacket.parse(frag, frag.size))
    }

    @Test fun filterDecisions() {
        val f = DnsFilter(listOf("wolt.com", "ubereats.com"), blockEncryptedDns = true)
        fun q(name: String) = DnsMessage.parseQuestion(DnsMessage.buildQuery(1, name, 1))!!
        val unlocked = setOf("ubereats.com")
        assertEquals(DnsFilter.Decision.Block("wolt.com"), f.decide(q("wolt.com")) { it in unlocked })
        assertEquals(DnsFilter.Decision.Block("wolt.com"), f.decide(q("consumer-api.wolt.com")) { it in unlocked })
        assertEquals(DnsFilter.Decision.Forward(true), f.decide(q("www.ubereats.com")) { it in unlocked })
        assertEquals(DnsFilter.Decision.Forward(false), f.decide(q("wolt.fi")) { it in unlocked })
        assertEquals(DnsFilter.Decision.Block(null), f.decide(q("dns.google")) { false })
        assertEquals(DnsFilter.Decision.Block(null), f.decide(q("use-application-dns.net")) { false })
        val noDoh = DnsFilter(emptyList(), blockEncryptedDns = false)
        assertEquals(DnsFilter.Decision.Forward(false), noDoh.decide(q("dns.google")) { false })
    }
}
