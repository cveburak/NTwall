package com.wall.guard.vpn.forward

import java.io.Closeable
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Writes a finished IP packet back to the TUN interface (towards the app). */
fun interface TunWriter {
    fun write(packet: ByteArray, length: Int)
}

/**
 * Excludes a socket from the VPN so that relayed traffic does not loop back
 * into the tunnel. On Android this is `VpnService.protect()`.
 */
interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean
}

data class FlowInfo(
    val protocol: Int,
    val sourceIp: InetAddress,
    val sourcePort: Int,
    val destIp: InetAddress,
    val destPort: Int
)

data class FlowDecision(val allow: Boolean, val uid: Int)

/** Asked exactly once per new flow (TCP SYN / first UDP datagram). */
fun interface FlowGate {
    fun decide(flow: FlowInfo): FlowDecision
}

/** Called for every packet the relay writes back to the app. */
fun interface InboundListener {
    fun onInbound(flow: FlowInfo, uid: Int, bytes: Int, tcpFlags: Int)
}

enum class Verdict {
    /** Packet was relayed to the real network. */
    FORWARDED,

    /** The gate refused the flow; the packet was dropped. */
    DENIED,

    /** Consumed without relaying and without accounting (stray segments, multicast...). */
    IGNORED,

    /** Not something the relay can handle (IPv6, ICMP, fragments...). Caller decides. */
    UNSUPPORTED
}

data class ForwardResult(val verdict: Verdict, val uid: Int)

data class ForwarderLimits(
    val mtu: Int = 1500,
    val mss: Int = 1400,
    val maxTcpSessions: Int = 512,
    val maxUdpSessions: Int = 1024,
    val connectTimeoutMs: Int = 10_000,
    // Longer than push-service heartbeats (~28 min) so idle-but-healthy connections survive.
    val tcpIdleMs: Long = 35 * 60_000L,
    val udpIdleMs: Long = 60_000L,
    val dnsIdleMs: Long = 10_000L,
    /** Max buffered client->server chunks per TCP session before we stop ACKing. */
    val queueChunks: Int = 256
)

private data class FlowKey(
    val protocol: Int,
    val srcIp: Int,
    val srcPort: Int,
    val dstIp: Int,
    val dstPort: Int
)

/**
 * A small user-space relay: the app's IPv4 TCP/UDP packets arrive from the
 * TUN device, are forwarded through ordinary (protected) sockets and the
 * replies are wrapped into IP packets and written back to the TUN device.
 *
 * Every app's traffic passes through here, so it has to stay robust: sessions
 * are capped, idle sessions are reaped and every failure resets just that
 * one connection.
 */
class PacketForwarder(
    private val tun: TunWriter,
    private val protector: SocketProtector,
    private val listener: InboundListener? = null,
    private val limits: ForwarderLimits = ForwarderLimits()
) : Closeable {

    private val tcp = ConcurrentHashMap<FlowKey, TcpSession>()
    private val udp = ConcurrentHashMap<FlowKey, UdpSession>()
    private val pendingUdp = ConcurrentLinkedQueue<UdpSession>()
    private val ipId = AtomicInteger(1)
    private val rnd = SecureRandom()

    @Volatile
    private var closed = false

    private val daemonFactory = ThreadFactory { r ->
        Thread(r, "ntwall-fwd").apply { isDaemon = true }
    }
    private val pool: ExecutorService = Executors.newCachedThreadPool(daemonFactory)
    private val janitor = Executors.newSingleThreadScheduledExecutor(daemonFactory)
    private val selector: Selector = Selector.open()
    private val udpThread = Thread({ udpLoop() }, "ntwall-udp").apply { isDaemon = true }

    val activeTcpSessions: Int get() = tcp.size
    val activeUdpSessions: Int get() = udp.size

    init {
        udpThread.start()
        janitor.scheduleWithFixedDelay({ cleanup() }, 5, 5, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    fun forward(packet: ByteArray, length: Int, gate: FlowGate): ForwardResult {
        if (closed || length < 20) return UNSUPPORTED
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return UNSUPPORTED
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return UNSUPPORTED
        val totalLen = u16(packet, 2)
        if (totalLen < ihl) return UNSUPPORTED
        val len = minOf(totalLen, length)
        // Fragmented datagrams (MF flag or offset != 0) are not reassembled.
        if ((u16(packet, 6) and 0x3FFF) != 0) return UNSUPPORTED

        val proto = packet[9].toInt() and 0xFF
        val srcIp = i32(packet, 12)
        val dstIp = i32(packet, 16)
        return when (proto) {
            PROTO_TCP -> forwardTcp(packet, len, ihl, srcIp, dstIp, gate)
            PROTO_UDP -> forwardUdp(packet, len, ihl, srcIp, dstIp, gate)
            else -> UNSUPPORTED
        }
    }

    // ------------------------------------------------------------------
    // TCP
    // ------------------------------------------------------------------

    private fun forwardTcp(
        p: ByteArray, len: Int, ihl: Int, srcIp: Int, dstIp: Int, gate: FlowGate
    ): ForwardResult {
        if (len < ihl + 20) return UNSUPPORTED
        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)
        val seq = u32(p, ihl + 4)
        val ack = u32(p, ihl + 8)
        val dataOff = ((p[ihl + 12].toInt() and 0xF0) shr 4) * 4
        if (dataOff < 20 || len < ihl + dataOff) return UNSUPPORTED
        val flags = p[ihl + 13].toInt() and 0x3F
        val window = u16(p, ihl + 14)
        val payloadOff = ihl + dataOff
        val payloadLen = len - payloadOff

        val key = FlowKey(PROTO_TCP, srcIp, srcPort, dstIp, dstPort)
        val existing = tcp[key]
        if (existing != null) {
            existing.onSegment(seq, ack, flags, window, p, payloadOff, payloadLen)
            return ForwardResult(Verdict.FORWARDED, existing.uid)
        }

        val isSyn = flags and SYN != 0
        val isAck = flags and ACK != 0
        val isRst = flags and RST != 0
        if (isSyn && !isAck && !isRst) {
            val flow = FlowInfo(PROTO_TCP, addr(srcIp), srcPort, addr(dstIp), dstPort)
            val decision = gate.decide(flow)
            if (!decision.allow) return ForwardResult(Verdict.DENIED, decision.uid)

            if (tcp.size >= limits.maxTcpSessions) cleanup()
            if (tcp.size >= limits.maxTcpSessions) {
                sendRstForUnknown(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, payloadLen)
                return ForwardResult(Verdict.DENIED, decision.uid)
            }

            val mss = parseMss(p, ihl + 20, ihl + dataOff) ?: 536
            val session = TcpSession(key, flow, decision.uid, seq, mss, window)
            val prev = tcp.putIfAbsent(key, session)
            if (prev != null) {
                prev.onSegment(seq, ack, flags, window, p, payloadOff, payloadLen)
                return ForwardResult(Verdict.FORWARDED, prev.uid)
            }
            session.start()
            return ForwardResult(Verdict.FORWARDED, decision.uid)
        }

        // A segment for a connection we do not know (e.g. created before the
        // tunnel was rebuilt). Reset it so the app reconnects and goes through
        // the gate again.
        if (!isRst) sendRstForUnknown(srcIp, srcPort, dstIp, dstPort, seq, ack, flags, payloadLen)
        return ForwardResult(Verdict.IGNORED, -1)
    }

    private fun sendRstForUnknown(
        srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, payloadLen: Int
    ) {
        val pkt = if (flags and ACK != 0) {
            buildTcp(dstIp, srcIp, dstPort, srcPort, ack, 0, RST, 0, null, 0, 0, null)
        } else {
            var next = seq + payloadLen
            if (flags and SYN != 0) next += 1
            if (flags and FIN != 0) next += 1
            buildTcp(dstIp, srcIp, dstPort, srcPort, 0, next and MASK, RST or ACK, 0, null, 0, 0, null)
        }
        try {
            tun.write(pkt, pkt.size)
        } catch (_: Exception) {
        }
    }

    private inner class TcpSession(
        val key: FlowKey,
        val flow: FlowInfo,
        val uid: Int,
        private val clientIsn: Long,
        clientMss: Int,
        initialWindow: Int
    ) {
        private val lock = ReentrantLock()
        private val cond = lock.newCondition()
        private val mss = clientMss.coerceIn(200, limits.mss)
        private val isn = rnd.nextInt().toLong() and MASK
        private var rcvNext = (clientIsn + 1) and MASK
        private var sndUna = isn
        private var sndNxt = isn
        private var clientWindow = initialWindow
        private var established = false
        private var clientFin = false
        private var serverFinSent = false

        @Volatile
        private var closed = false

        @Volatile
        private var lastActivity = System.currentTimeMillis()

        @Volatile
        private var socket: Socket? = null
        private val outQueue = LinkedBlockingQueue<ByteArray>(limits.queueChunks)

        // True once we advertised a shrunken window; the writer sends a window
        // update as soon as enough queue space is free again.
        @Volatile
        private var windowClosed = false

        /** Receive window advertised to the app: the free room in our send queue. */
        private fun advWindow(): Int = minOf(ADV_WINDOW, outQueue.remainingCapacity() * mss)

        fun start() {
            try {
                pool.execute { runConnection() }
            } catch (_: Exception) {
                lock.withLock { closeInternal(true) }
            }
        }

        // ---- server side: connect, then relay server -> client ----------

        private fun runConnection() {
            val s = Socket()
            socket = s
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                if (!protector.protect(s)) throw IOException("protect() failed")
                s.connect(InetSocketAddress(flow.destIp, flow.destPort), limits.connectTimeoutMs)
            } catch (_: Exception) {
                lock.withLock { closeInternal(true) }
                return
            }

            lock.withLock {
                if (closed) {
                    try { s.close() } catch (_: Exception) {}
                    return
                }
                established = true
                sndNxt = (isn + 1) and MASK
                send(SYN or ACK, isn, rcvNext, null, 0, 0, mss)
            }

            try {
                pool.execute { writerLoop(s) }
            } catch (_: Exception) {
                lock.withLock { closeInternal(true) }
                return
            }

            try {
                val input = s.getInputStream()
                val buf = ByteArray(mss)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    lock.withLock {
                        var waited = 0L
                        while (!closed && inflight() > 0 && inflight() + n > clientWindow) {
                            cond.await(200, TimeUnit.MILLISECONDS)
                            waited += 200
                            if (waited > 60_000) {
                                closeInternal(true)
                                return
                            }
                        }
                        if (closed) return
                        send(ACK or PSH, sndNxt, rcvNext, buf, 0, n, null)
                        sndNxt = (sndNxt + n) and MASK
                        lastActivity = System.currentTimeMillis()
                    }
                }
                lock.withLock {
                    if (!closed) {
                        send(FIN or ACK, sndNxt, rcvNext, null, 0, 0, null)
                        sndNxt = (sndNxt + 1) and MASK
                        serverFinSent = true
                        lastActivity = System.currentTimeMillis()
                        checkDone()
                    }
                }
            } catch (_: IOException) {
                lock.withLock { closeInternal(true) }
            }
        }

        // ---- client -> server: drain queue into the socket ---------------

        private fun writerLoop(s: Socket) {
            try {
                val out = s.getOutputStream()
                while (true) {
                    val chunk = outQueue.poll(500, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (closed) return
                        continue
                    }
                    if (chunk === FIN_MARK) {
                        try { s.shutdownOutput() } catch (_: Exception) {}
                        return
                    }
                    out.write(chunk)
                    lastActivity = System.currentTimeMillis()
                    if (windowClosed) {
                        lock.withLock {
                            if (!closed && windowClosed &&
                                outQueue.remainingCapacity() * mss >= ADV_WINDOW / 2
                            ) {
                                windowClosed = false
                                sendAck() // window update
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                lock.withLock { closeInternal(true) }
            }
        }

        // ---- packets coming from the app -----------------------------------

        fun onSegment(seq: Long, ack: Long, flags: Int, window: Int, buf: ByteArray, off: Int, len: Int) {
            lock.withLock {
                if (closed) return
                lastActivity = System.currentTimeMillis()

                if (flags and RST != 0) {
                    closeInternal(false)
                    return
                }
                if (flags and SYN != 0) {
                    // Retransmitted SYN: repeat our SYN-ACK once connected.
                    if (established && seq == clientIsn) {
                        send(SYN or ACK, isn, (clientIsn + 1) and MASK, null, 0, 0, mss)
                    }
                    return
                }
                if (!established) return

                if (flags and ACK != 0) {
                    val acked = (ack - sndUna) and MASK
                    if (acked <= inflight()) sndUna = ack
                    clientWindow = window
                    cond.signalAll()
                }

                var dseq = seq
                var doff = off
                var dlen = len
                if (dlen > 0) {
                    val behind = (rcvNext - dseq) and MASK
                    if (behind != 0L && behind < HALF) {
                        if (behind >= dlen) {
                            dlen = 0 // pure duplicate
                            sendAck()
                        } else {
                            doff += behind.toInt()
                            dlen -= behind.toInt()
                            dseq = rcvNext
                        }
                    }
                    if (dlen > 0) {
                        if (dseq == rcvNext) {
                            if (outQueue.offer(buf.copyOfRange(doff, doff + dlen))) {
                                rcvNext = (rcvNext + dlen) and MASK
                            }
                            sendAck()
                        } else {
                            sendAck() // out of order: duplicate ACK, client retransmits
                            return
                        }
                    }
                }

                if (flags and FIN != 0) {
                    val finSeq = (seq + len) and MASK
                    if (finSeq == rcvNext && !clientFin) {
                        if (outQueue.offer(FIN_MARK)) {
                            clientFin = true
                            rcvNext = (rcvNext + 1) and MASK
                            sendAck()
                        }
                    } else if (clientFin) {
                        sendAck()
                    }
                }
                checkDone()
            }
        }

        fun checkIdle(now: Long) {
            lock.withLock {
                if (closed) return
                val idle = now - lastActivity
                val limit = when {
                    !established -> limits.connectTimeoutMs + 20_000L
                    clientFin || serverFinSent -> 60_000L
                    else -> limits.tcpIdleMs
                }
                if (idle > limit) closeInternal(true)
            }
        }

        fun shutdown() {
            lock.withLock { closeInternal(false) }
        }

        // ---- helpers (call with lock held) ---------------------------------

        private fun inflight(): Long = (sndNxt - sndUna) and MASK

        private fun sendAck() {
            if (advWindow() < mss) windowClosed = true
            send(ACK, sndNxt, rcvNext, null, 0, 0, null)
        }

        private fun checkDone() {
            if (clientFin && serverFinSent && sndUna == sndNxt) closeInternal(false)
        }

        private fun send(
            flags: Int, seq: Long, ack: Long,
            payload: ByteArray?, poff: Int, plen: Int, mssOpt: Int?
        ) {
            val pkt = buildTcp(
                key.dstIp, key.srcIp, key.dstPort, key.srcPort,
                seq, ack, flags, advWindow(), payload, poff, plen, mssOpt
            )
            emit(flow, uid, pkt, flags)
        }

        private fun closeInternal(sendRst: Boolean) {
            if (closed) return
            closed = true
            if (sendRst) {
                if (established) send(RST or ACK, sndNxt, rcvNext, null, 0, 0, null)
                else send(RST or ACK, 0, rcvNext, null, 0, 0, null)
            }
            try { socket?.close() } catch (_: Exception) {}
            outQueue.clear()
            tcp.remove(key, this)
            cond.signalAll()
        }
    }

    // ------------------------------------------------------------------
    // UDP
    // ------------------------------------------------------------------

    private fun forwardUdp(
        p: ByteArray, len: Int, ihl: Int, srcIp: Int, dstIp: Int, gate: FlowGate
    ): ForwardResult {
        if (len < ihl + 8) return UNSUPPORTED
        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)
        val udpLen = minOf(u16(p, ihl + 4), len - ihl)
        val payloadLen = udpLen - 8
        if (payloadLen < 0) return UNSUPPORTED
        val payloadOff = ihl + 8

        val key = FlowKey(PROTO_UDP, srcIp, srcPort, dstIp, dstPort)
        var session = udp[key]
        if (session == null) {
            if (isMulticastOrBroadcast(dstIp)) return ForwardResult(Verdict.IGNORED, -1)
            val flow = FlowInfo(PROTO_UDP, addr(srcIp), srcPort, addr(dstIp), dstPort)
            val decision = gate.decide(flow)
            if (!decision.allow) return ForwardResult(Verdict.DENIED, decision.uid)

            if (udp.size >= limits.maxUdpSessions) cleanup(aggressive = true)
            if (udp.size >= limits.maxUdpSessions) return ForwardResult(Verdict.DENIED, decision.uid)

            session = createUdp(key, flow, decision.uid)
                ?: return ForwardResult(Verdict.IGNORED, decision.uid)
        }
        session.send(p, payloadOff, payloadLen)
        return ForwardResult(Verdict.FORWARDED, session.uid)
    }

    private fun createUdp(key: FlowKey, flow: FlowInfo, uid: Int): UdpSession? {
        val ch = try {
            DatagramChannel.open()
        } catch (_: IOException) {
            return null
        }
        try {
            if (!protector.protect(ch.socket())) throw IOException("protect() failed")
            ch.configureBlocking(false)
            ch.connect(InetSocketAddress(flow.destIp, flow.destPort))
        } catch (_: Exception) {
            try { ch.close() } catch (_: Exception) {}
            return null
        }
        val session = UdpSession(key, flow, uid, ch)
        val prev = udp.putIfAbsent(key, session)
        if (prev != null) {
            try { ch.close() } catch (_: Exception) {}
            return prev
        }
        pendingUdp.add(session)
        selector.wakeup()
        return session
    }

    private inner class UdpSession(
        val key: FlowKey,
        val flow: FlowInfo,
        val uid: Int,
        val channel: DatagramChannel
    ) {
        @Volatile
        var lastActivity = System.currentTimeMillis()

        fun send(buf: ByteArray, off: Int, len: Int) {
            try {
                channel.write(ByteBuffer.wrap(buf, off, len))
                lastActivity = System.currentTimeMillis()
            } catch (_: IOException) {
                close()
            }
        }

        fun onReadable(readBuf: ByteBuffer) {
            while (true) {
                readBuf.clear()
                val n = try {
                    channel.read(readBuf)
                } catch (_: PortUnreachableException) {
                    close()
                    return
                } catch (_: IOException) {
                    close()
                    return
                }
                if (n <= 0) return
                lastActivity = System.currentTimeMillis()
                val packets = buildUdp(key.dstIp, key.srcIp, key.dstPort, key.srcPort, readBuf.array(), 0, n)
                for (pkt in packets) emit(flow, uid, pkt, 0)
            }
        }

        fun close() {
            udp.remove(key, this)
            try { channel.close() } catch (_: Exception) {}
        }
    }

    private fun udpLoop() {
        val readBuf = ByteBuffer.allocate(65535)
        while (!closed) {
            try {
                selector.select(500)
                while (true) {
                    val s = pendingUdp.poll() ?: break
                    try {
                        s.channel.register(selector, SelectionKey.OP_READ, s)
                    } catch (_: Exception) {
                        s.close()
                    }
                }
                val it = selector.selectedKeys().iterator()
                while (it.hasNext()) {
                    val k = it.next()
                    it.remove()
                    val s = k.attachment() as? UdpSession ?: continue
                    if (k.isValid && k.isReadable) s.onReadable(readBuf)
                }
            } catch (_: ClosedSelectorException) {
                return
            } catch (_: IOException) {
                // keep going
            } catch (_: RuntimeException) {
                // e.g. cancelled key; keep going
            }
        }
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    private fun cleanup(aggressive: Boolean = false) {
        val now = System.currentTimeMillis()
        for (s in udp.values) {
            val limit = when {
                aggressive -> 5_000L
                s.key.dstPort == 53 -> limits.dnsIdleMs
                else -> limits.udpIdleMs
            }
            if (now - s.lastActivity > limit) s.close()
        }
        for (s in tcp.values) s.checkIdle(now)
    }

    override fun close() {
        if (closed) return
        closed = true
        janitor.shutdownNow()
        for (s in tcp.values) s.shutdown()
        for (s in udp.values) s.close()
        try { selector.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun emit(flow: FlowInfo, uid: Int, packet: ByteArray, tcpFlags: Int) {
        try {
            tun.write(packet, packet.size)
        } catch (_: Exception) {
            // TUN closed while shutting down.
        }
        listener?.onInbound(flow, uid, packet.size, tcpFlags)
    }

    // ------------------------------------------------------------------
    // Packet construction
    // ------------------------------------------------------------------

    private fun buildTcp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int,
        payload: ByteArray?, poff: Int, plen: Int, mssOpt: Int?
    ): ByteArray {
        val hdr = if (mssOpt != null) 24 else 20
        val segLen = hdr + plen
        val seg = ByteArray(segLen)
        put16(seg, 0, srcPort)
        put16(seg, 2, dstPort)
        put32(seg, 4, seq)
        put32(seg, 8, ack)
        seg[12] = ((hdr / 4) shl 4).toByte()
        seg[13] = flags.toByte()
        put16(seg, 14, window)
        if (mssOpt != null) {
            seg[20] = 2
            seg[21] = 4
            put16(seg, 22, mssOpt)
        }
        if (payload != null && plen > 0) System.arraycopy(payload, poff, seg, hdr, plen)
        put16(seg, 16, checksum(seg, 0, segLen, pseudoSum(srcIp, dstIp, PROTO_TCP, segLen)))
        return ipPacket(PROTO_TCP, srcIp, dstIp, nextId(), 0, more = false, df = true, payload = seg, poff = 0, plen = segLen)
    }

    /** Builds a UDP datagram, fragmenting it when it does not fit the MTU. */
    private fun buildUdp(
        srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int,
        payload: ByteArray, poff: Int, plen: Int
    ): List<ByteArray> {
        val udpLen = 8 + plen
        val dgram = ByteArray(udpLen)
        put16(dgram, 0, srcPort)
        put16(dgram, 2, dstPort)
        put16(dgram, 4, udpLen)
        System.arraycopy(payload, poff, dgram, 8, plen)
        var sum = checksum(dgram, 0, udpLen, pseudoSum(srcIp, dstIp, PROTO_UDP, udpLen))
        if (sum == 0) sum = 0xFFFF
        put16(dgram, 6, sum)

        val id = nextId()
        if (20 + udpLen <= limits.mtu) {
            return listOf(ipPacket(PROTO_UDP, srcIp, dstIp, id, 0, more = false, df = false, payload = dgram, poff = 0, plen = udpLen))
        }
        val maxData = ((limits.mtu - 20) / 8) * 8
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < udpLen) {
            val chunk = minOf(maxData, udpLen - offset)
            val more = offset + chunk < udpLen
            out.add(ipPacket(PROTO_UDP, srcIp, dstIp, id, offset / 8, more, false, dgram, offset, chunk))
            offset += chunk
        }
        return out
    }

    private fun ipPacket(
        proto: Int, srcIp: Int, dstIp: Int, id: Int, fragOffsetUnits: Int,
        more: Boolean, df: Boolean, payload: ByteArray, poff: Int, plen: Int
    ): ByteArray {
        val p = ByteArray(20 + plen)
        p[0] = 0x45
        put16(p, 2, 20 + plen)
        put16(p, 4, id)
        var ff = fragOffsetUnits and 0x1FFF
        if (more) ff = ff or 0x2000
        if (df) ff = ff or 0x4000
        put16(p, 6, ff)
        p[8] = 64
        p[9] = proto.toByte()
        put32i(p, 12, srcIp)
        put32i(p, 16, dstIp)
        put16(p, 10, checksum(p, 0, 20, 0))
        System.arraycopy(payload, poff, p, 20, plen)
        return p
    }

    private fun nextId(): Int = ipId.getAndIncrement() and 0xFFFF

    private fun parseMss(p: ByteArray, start: Int, end: Int): Int? {
        var i = start
        while (i < end) {
            val kind = p[i].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) {
                i++
                continue
            }
            if (i + 1 >= end) break
            val l = p[i + 1].toInt() and 0xFF
            if (l < 2) break
            if (kind == 2 && l == 4 && i + 3 < end) return u16(p, i + 2)
            i += l
        }
        return null
    }

    companion object {
        const val PROTO_TCP = 6
        const val PROTO_UDP = 17

        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10

        private const val MASK = 0xFFFFFFFFL
        private const val HALF = 0x80000000L
        private const val ADV_WINDOW = 65535
        private val FIN_MARK = ByteArray(0)
        private val UNSUPPORTED = ForwardResult(Verdict.UNSUPPORTED, -1)

        private fun isMulticastOrBroadcast(ip: Int): Boolean {
            val first = (ip ushr 24) and 0xFF
            return first in 224..239 || ip == -1
        }

        private fun addr(ip: Int): InetAddress = InetAddress.getByAddress(
            byteArrayOf(
                (ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte()
            )
        )

        internal fun u16(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        internal fun u32(b: ByteArray, o: Int): Long =
            (((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF))

        private fun i32(b: ByteArray, o: Int): Int = u32(b, o).toInt()

        private fun put16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 8).toByte()
            b[o + 1] = v.toByte()
        }

        private fun put32(b: ByteArray, o: Int, v: Long) = put32i(b, o, v.toInt())

        private fun put32i(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte()
            b[o + 1] = (v ushr 16).toByte()
            b[o + 2] = (v ushr 8).toByte()
            b[o + 3] = v.toByte()
        }

        private fun pseudoSum(srcIp: Int, dstIp: Int, proto: Int, length: Int): Long =
            ((srcIp ushr 16) and 0xFFFF).toLong() + (srcIp and 0xFFFF).toLong() +
                ((dstIp ushr 16) and 0xFFFF).toLong() + (dstIp and 0xFFFF).toLong() +
                proto.toLong() + length.toLong()

        /** RFC 1071 one's-complement checksum, seeded with [seed]. */
        internal fun checksum(data: ByteArray, off: Int, len: Int, seed: Long): Int {
            var sum = seed
            var i = off
            val end = off + len
            while (i + 1 < end) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
            while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
            return sum.inv().toInt() and 0xFFFF
        }
    }
}
