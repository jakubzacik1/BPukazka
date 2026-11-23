package app.impulseblocker.service

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import app.impulseblocker.core.DnsFilter
import app.impulseblocker.core.DnsMessage
import app.impulseblocker.core.UdpPacket
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Reads DNS packets from the TUN interface, answers blocked names locally with
 * NXDOMAIN and forwards everything else to the network's real DNS server.
 *
 * Only traffic addressed to the virtual DNS server IPs is routed into the TUN
 * (see [DnsVpnService]), so normal browsing traffic never passes through this
 * code: no battery cost for it and no effect on connectivity.
 *
 * The reader thread sleeps in poll() until a packet arrives (no busy loop).
 */
class DnsTunnelEngine(
    private val tun: ParcelFileDescriptor,
    private val protect: (DatagramSocket) -> Boolean,
    private val upstreamServers: () -> List<InetAddress>,
    private val decide: (DnsMessage.Question) -> DnsFilter.Decision,
    private val onBlocked: (rule: String, host: String) -> Unit,
) {
    private val tag = "DnsTunnel"
    private val out = FileOutputStream(tun.fileDescriptor)
    private val wakePipe: Array<FileDescriptor> = Os.pipe()
    @Volatile private var stopped = false
    private val workers = ThreadPoolExecutor(
        0, 24, 30, TimeUnit.SECONDS, SynchronousQueue(), ThreadPoolExecutor.DiscardPolicy(),
    )
    private val reader = Thread(::readLoop, "dns-tun-reader")

    fun start() = reader.start()

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { Os.write(wakePipe[1], byteArrayOf(1), 0, 1) }
        runCatching { reader.join(2000) }
        workers.shutdownNow()
        runCatching { Os.close(wakePipe[0]) }
        runCatching { Os.close(wakePipe[1]) }
        runCatching { tun.close() }
    }

    private fun readLoop() {
        val buf = ByteArray(32767)
        val tunPoll = StructPollfd().apply { fd = tun.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val wakePoll = StructPollfd().apply { fd = wakePipe[0]; events = OsConstants.POLLIN.toShort() }
        val fds = arrayOf(tunPoll, wakePoll)
        while (!stopped) {
            tunPoll.revents = 0
            wakePoll.revents = 0
            try {
                Os.poll(fds, -1)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                Log.w(tag, "poll failed", e)
                break
            }
            if (stopped || wakePoll.revents.toInt() != 0) break
            val rev = tunPoll.revents.toInt()
            if (rev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
            if (rev and OsConstants.POLLIN == 0) continue
            val n = try {
                Os.read(tun.fileDescriptor, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                Log.w(tag, "read failed", e)
                break
            } catch (e: IOException) {
                break
            }
            if (n <= 0) continue
            try {
                handlePacket(buf, n)
            } catch (e: Exception) {
                Log.w(tag, "Dropping malformed packet", e)
            }
        }
        Log.i(tag, "Reader stopped")
    }

    private fun handlePacket(buf: ByteArray, length: Int) {
        val pkt = UdpPacket.parse(buf, length) ?: return // TCP/ICMP to the virtual IP: ignore
        if (pkt.dstPort != 53) return
        val q = DnsMessage.parseQuestion(pkt.payload) ?: return
        when (val d = decide(q)) {
            is DnsFilter.Decision.Block -> {
                write(pkt.buildReply(DnsMessage.buildErrorResponse(pkt.payload, q, DnsMessage.RCODE_NXDOMAIN)))
                d.userRule?.let { onBlocked(it, q.name) }
            }
            is DnsFilter.Decision.Forward -> workers.execute { forward(pkt, q, d.clampTtl) }
            DnsFilter.Decision.Drop -> Unit
        }
    }

    private fun forward(pkt: UdpPacket, q: DnsMessage.Question, clampTtl: Boolean) {
        val servers = upstreamServers()
        for (server in servers.take(3)) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 4000
                    socket.send(DatagramPacket(pkt.payload, pkt.payload.size, InetSocketAddress(server, 53)))
                    val respBuf = ByteArray(4096)
                    val resp = DatagramPacket(respBuf, respBuf.size)
                    while (true) {
                        socket.receive(resp)
                        // Ignore anything that is not the answer to this query.
                        if (resp.length >= 12 && DnsMessage.u16(respBuf, 0) == q.id) break
                    }
                    val answer = respBuf.copyOf(resp.length)
                    if (clampTtl) DnsMessage.clampTtls(answer, answer.size, DnsFilter.UNLOCKED_MAX_TTL)
                    write(pkt.buildReply(answer))
                }
                return
            } catch (e: SocketTimeoutException) {
                Log.d(tag, "Upstream $server timed out for ${q.name}")
            } catch (e: IOException) {
                Log.d(tag, "Upstream $server failed: ${e.message}")
            }
        }
        if (!stopped) write(pkt.buildReply(DnsMessage.buildErrorResponse(pkt.payload, q, DnsMessage.RCODE_SERVFAIL)))
    }

    private fun write(packet: ByteArray) {
        if (stopped) return
        try {
            synchronized(out) { out.write(packet) }
        } catch (e: IOException) {
            Log.w(tag, "write failed", e)
        }
    }
}
