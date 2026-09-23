package com.wall.guard.vpn.forward

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives [PacketForwarder] with hand-made IPv4 packets against real loopback
 * sockets. The "client" below is a tiny TCP endpoint that plays the role of
 * the Android kernel on the other side of the TUN device.
 */
class PacketForwarderTest {

    private val cleanup = ArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        cleanup.forEach { runCatching { it.close() } }
        cleanup.clear()
    }

    // ---------------------------------------------------------------- helpers

    private class Capture : TunWriter {
        val queue = LinkedBlockingQueue<ByteArray>()
        override fun write(packet: ByteArray, length: Int) {
            queue.add(packet.copyOf(length))
        }

        fun next(timeoutMs: Long = 5000): ByteArray =
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: fail("no packet within ${timeoutMs}ms").let { ByteArray(0) }
    }

    private val noProtect = object : SocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }

    private val allowAll = FlowGate { FlowDecision(true, 10123) }

    private fun newForwarder(
        cap: Capture,
        listener: InboundListener? = null,
        limits: ForwarderLimits = ForwarderLimits()
    ): PacketForwarder {
        val f = PacketForwarder(cap, noProtect, listener, limits)
        cleanup.add(f)
        return f
    }

    private val clientIp = byteArrayOf(10, 1, 10, 2)
    private val loopback = byteArrayOf(127, 0, 0, 1)

    private fun ipInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) = PacketForwarder.u32(b, o)
    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    /** Independent RFC 1071 implementation (does not reuse production code). */
    private fun sum16(data: ByteArray, off: Int, len: Int, seed: Long = 0): Long {
        var s = seed
        var i = off
        while (i + 1 < off + len) {
            s += u16(data, i); i += 2
        }
        if (i < off + len) s += (data[i].toInt() and 0xFF) shl 8
        return s
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return s.toInt()
    }

    private fun pseudo(src: ByteArray, dst: ByteArray, proto: Int, len: Int): Long =
        u16(src, 0).toLong() + u16(src, 2) + u16(dst, 0) + u16(dst, 2) + proto + len

    private fun ipHeader(total: Int, id: Int, ff: Int, proto: Int, src: ByteArray, dst: ByteArray): ByteArray {
        val h = ByteArray(20)
        h[0] = 0x45; put16(h, 2, total); put16(h, 4, id); put16(h, 6, ff)
        h[8] = 64; h[9] = proto.toByte()
        System.arraycopy(src, 0, h, 12, 4); System.arraycopy(dst, 0, h, 16, 4)
        put16(h, 10, fold(sum16(h, 0, 20)).inv() and 0xFFFF)
        return h
    }

    private fun tcpPacket(
        src: ByteArray, dst: ByteArray, sp: Int, dp: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray = ByteArray(0),
        mss: Int? = null, window: Int = 65535
    ): ByteArray {
        val hdr = if (mss != null) 24 else 20
        val seg = ByteArray(hdr + payload.size)
        put16(seg, 0, sp); put16(seg, 2, dp); put32(seg, 4, seq); put32(seg, 8, ack)
        seg[12] = ((hdr / 4) shl 4).toByte(); seg[13] = flags.toByte(); put16(seg, 14, window)
        if (mss != null) {
            seg[20] = 2; seg[21] = 4; put16(seg, 22, mss)
        }
        System.arraycopy(payload, 0, seg, hdr, payload.size)
        put16(seg, 16, fold(sum16(seg, 0, seg.size, pseudo(src, dst, 6, seg.size))).inv() and 0xFFFF)
        return ipHeader(20 + seg.size, 1, 0x4000, 6, src, dst) + seg
    }

    private fun udpPacket(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, payload: ByteArray): ByteArray {
        val d = ByteArray(8 + payload.size)
        put16(d, 0, sp); put16(d, 2, dp); put16(d, 4, d.size)
        System.arraycopy(payload, 0, d, 8, payload.size)
        put16(d, 6, fold(sum16(d, 0, d.size, pseudo(src, dst, 17, d.size))).inv() and 0xFFFF)
        return ipHeader(20 + d.size, 2, 0, 17, src, dst) + d
    }

    private class Tcp(
        val raw: ByteArray, val seq: Long, val ack: Long, val flags: Int,
        val window: Int, val payload: ByteArray, val srcPort: Int, val dstPort: Int
    ) {
        val syn get() = flags and 0x02 != 0
        val ackF get() = flags and 0x10 != 0
        val fin get() = flags and 0x01 != 0
        val rst get() = flags and 0x04 != 0
    }

    private fun parseTcp(pkt: ByteArray): Tcp {
        assertEquals("IP version/IHL", 0x45, pkt[0].toInt() and 0xFF)
        assertEquals("protocol", 6, pkt[9].toInt() and 0xFF)
        val total = u16(pkt, 2)
        assertEquals("IP total length", pkt.size, total)
        assertEquals("IP header checksum", 0xFFFF, fold(sum16(pkt, 0, 20)))
        val src = pkt.copyOfRange(12, 16)
        val dst = pkt.copyOfRange(16, 20)
        val segLen = total - 20
        assertEquals("TCP checksum", 0xFFFF, fold(sum16(pkt, 20, segLen, pseudo(src, dst, 6, segLen))))
        val dataOff = ((pkt[32].toInt() and 0xF0) shr 4) * 4
        return Tcp(
            pkt, u32(pkt, 24), u32(pkt, 28), pkt[33].toInt() and 0x3F, u16(pkt, 34),
            pkt.copyOfRange(20 + dataOff, total), u16(pkt, 20), u16(pkt, 22)
        )
    }

    /** The application's side of one TCP connection. */
    private inner class Client(
        val fwd: PacketForwarder, val cap: Capture, val serverPort: Int,
        val clientPort: Int = 40000, val gate: FlowGate = allowAll
    ) {
        var snd = 1000L
        var rcv = 0L
        var serverAcked = 0L
        var relayWindow = 65535
        var sawFin = false
        val received = ByteArrayOutputStream()

        fun send(flags: Int, payload: ByteArray = ByteArray(0), mss: Int? = null): ForwardResult {
            val p = tcpPacket(clientIp, loopback, clientPort, serverPort, snd, rcv, flags, payload, mss)
            return fwd.forward(p, p.size, gate)
        }

        fun connect() {
            val r = send(0x02, mss = 1460)
            assertEquals(Verdict.FORWARDED, r.verdict)
            assertEquals(10123, r.uid)
            snd += 1
            val synack = parseTcp(cap.next())
            assertTrue("SYN|ACK expected, flags=${synack.flags}", synack.syn && synack.ackF)
            assertEquals(snd, synack.ack)
            assertEquals(serverPort, synack.srcPort)
            assertEquals(clientPort, synack.dstPort)
            rcv = (synack.seq + 1) and 0xFFFFFFFFL
            send(0x10)
        }

        /** Processes one packet from the relay; returns false on timeout. */
        fun pump(timeoutMs: Long = 5000): Boolean {
            val raw = cap.queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return false
            val t = parseTcp(raw)
            assertFalse("unexpected RST", t.rst)
            if (t.ackF) serverAcked = t.ack
            relayWindow = t.window
            var needAck = false
            if (t.payload.isNotEmpty()) {
                if (t.seq == rcv) {
                    received.write(t.payload)
                    rcv = (rcv + t.payload.size) and 0xFFFFFFFFL
                } else {
                    fail("out-of-order server data: seq=${t.seq} expected=$rcv")
                }
                needAck = true
            }
            if (t.fin && t.seq + t.payload.size == rcv) {
                rcv = (rcv + 1) and 0xFFFFFFFFL
                sawFin = true
                needAck = true
            }
            if (needAck) send(0x10)
            return true
        }

        fun waitFor(bytes: Int, timeoutMs: Long = 10000) {
            val end = System.currentTimeMillis() + timeoutMs
            while (received.size() < bytes) {
                if (System.currentTimeMillis() > end) fail("received ${received.size()} of $bytes")
                pump(500)
            }
        }

        fun waitForFin(timeoutMs: Long = 10000) {
            val end = System.currentTimeMillis() + timeoutMs
            while (!sawFin) {
                if (System.currentTimeMillis() > end) fail("no FIN from server")
                pump(500)
            }
        }
    }

    private fun startEchoServer(): Pair<ServerSocket, Thread> {
        val ss = ServerSocket(0, 50, InetAddress.getByAddress(loopback))
        cleanup.add(ss)
        val t = Thread {
            try {
                while (true) {
                    val s = ss.accept()
                    Thread {
                        try {
                            val i = s.getInputStream()
                            val o = s.getOutputStream()
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = i.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                            }
                            s.close()
                        } catch (_: Exception) {
                        }
                    }.apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
        return ss to t
    }

    private fun waitUntil(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun tcpHandshakeEchoAndGracefulClose() {
        val (server, _) = startEchoServer()
        val cap = Capture()
        val inbound = AtomicInteger()
        val fwd = newForwarder(cap, { _, uid, bytes, _ -> assertEquals(10123, uid); inbound.addAndGet(bytes) })
        val c = Client(fwd, cap, server.localPort)

        c.connect()
        assertEquals(1, fwd.activeTcpSessions)

        val msg = "hello firewall".toByteArray()
        c.send(0x18, msg) // PSH|ACK
        c.snd += msg.size
        c.waitFor(msg.size)
        assertArrayEquals(msg, c.received.toByteArray())

        // Client closes; echo server sees EOF, closes, relay sends FIN.
        c.send(0x11) // FIN|ACK
        c.snd += 1
        c.waitForFin()
        assertTrue("session should be removed after both FINs are acked", waitUntil { fwd.activeTcpSessions == 0 })
        assertTrue(inbound.get() > 0)
    }

    @Test
    fun tcpBulkTransferRespectsWindowAndKeepsOrder() {
        val (server, _) = startEchoServer()
        val cap = Capture()
        val fwd = newForwarder(cap)
        val c = Client(fwd, cap, server.localPort)
        c.connect()

        val data = ByteArray(300_000).also { Random(42).nextBytes(it) }
        var pos = 0
        val startSeq = c.snd
        while (pos < data.size || c.received.size() < data.size) {
            // never exceed ~32 KB unacknowledged towards the relay
            while (pos < data.size && (c.snd - c.serverAcked.coerceAtLeast(startSeq)) < minOf(32_000, c.relayWindow)) {
                val n = minOf(1400, data.size - pos)
                c.send(0x18, data.copyOfRange(pos, pos + n))
                c.snd += n
                pos += n
            }
            if (!c.pump(3000)) fail("stalled: sent=$pos received=${c.received.size()}")
        }
        assertArrayEquals(data, c.received.toByteArray())

        c.send(0x11); c.snd += 1
        c.waitForFin()
        assertTrue(waitUntil { fwd.activeTcpSessions == 0 })
    }

    @Test
    fun tcpConnectionRefusedResetsClient() {
        val closed = ServerSocket(0, 1, InetAddress.getByAddress(loopback))
        val port = closed.localPort
        closed.close()

        val cap = Capture()
        val fwd = newForwarder(cap)
        val c = Client(fwd, cap, port)
        c.send(0x02, mss = 1460)
        val rst = parseTcp(cap.next())
        assertTrue("RST expected", rst.rst)
        assertTrue(rst.ackF)
        assertEquals(1001L, rst.ack)
        assertTrue(waitUntil { fwd.activeTcpSessions == 0 })
    }

    @Test
    fun tcpSegmentForUnknownConnectionGetsRst() {
        val cap = Capture()
        val fwd = newForwarder(cap)
        val p = tcpPacket(clientIp, loopback, 41000, 9, 5000, 7777, 0x18, "x".toByteArray())
        val r = fwd.forward(p, p.size, allowAll)
        assertEquals(Verdict.IGNORED, r.verdict)
        val rst = parseTcp(cap.next())
        assertTrue(rst.rst)
        assertEquals(7777L, rst.seq)
    }

    @Test
    fun deniedFlowIsDroppedAndNothingIsSent() {
        val (server, _) = startEchoServer()
        val cap = Capture()
        val fwd = newForwarder(cap)
        val deny = FlowGate { FlowDecision(false, 10999) }
        val c = Client(fwd, cap, server.localPort, gate = deny)
        val r = c.send(0x02, mss = 1460)
        assertEquals(Verdict.DENIED, r.verdict)
        assertEquals(10999, r.uid)
        assertEquals(null, cap.queue.poll(300, TimeUnit.MILLISECONDS))
        assertEquals(0, fwd.activeTcpSessions)
    }

    @Test
    fun gateIsAskedOncePerFlowOnly() {
        val (server, _) = startEchoServer()
        val cap = Capture()
        val fwd = newForwarder(cap)
        val calls = AtomicInteger()
        val gate = FlowGate { calls.incrementAndGet(); FlowDecision(true, 10123) }
        val c = Client(fwd, cap, server.localPort, gate = gate)
        c.connect()
        c.send(0x18, "a".toByteArray()); c.snd += 1
        c.send(0x18, "b".toByteArray()); c.snd += 1
        c.waitFor(2)
        assertEquals(1, calls.get())
    }

    @Test
    fun nonIpv4AndIcmpAreReportedUnsupported() {
        val cap = Capture()
        val fwd = newForwarder(cap)
        val v6 = ByteArray(60).also { it[0] = 0x60 }
        assertEquals(Verdict.UNSUPPORTED, fwd.forward(v6, v6.size, allowAll).verdict)
        val icmp = ipHeader(28, 3, 0, 1, clientIp, loopback) + ByteArray(8)
        assertEquals(Verdict.UNSUPPORTED, fwd.forward(icmp, icmp.size, allowAll).verdict)
    }

    @Test
    fun udpRoundTrip() {
        val echo = DatagramSocket(0, InetAddress.getByAddress(loopback))
        cleanup.add(echo)
        Thread {
            try {
                val buf = ByteArray(2048)
                while (true) {
                    val dp = DatagramPacket(buf, buf.size)
                    echo.receive(dp)
                    echo.send(DatagramPacket(dp.data, dp.length, dp.address, dp.port))
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        val cap = Capture()
        val fwd = newForwarder(cap)
        val payload = "dns-like query".toByteArray()
        val p = udpPacket(clientIp, loopback, 50001, echo.localPort, payload)
        val r = fwd.forward(p, p.size, allowAll)
        assertEquals(Verdict.FORWARDED, r.verdict)

        val reply = cap.next()
        assertEquals(17, reply[9].toInt() and 0xFF)
        assertEquals(0xFFFF, fold(sum16(reply, 0, 20)))
        val udpLen = u16(reply, 2) - 20
        assertEquals(0xFFFF, fold(sum16(reply, 20, udpLen, pseudo(loopback, clientIp, 17, udpLen))))
        assertEquals(echo.localPort, u16(reply, 20))
        assertEquals(50001, u16(reply, 22))
        assertArrayEquals(payload, reply.copyOfRange(28, reply.size))

        // second datagram of the same flow reuses the session
        fwd.forward(p, p.size, allowAll)
        cap.next()
        assertEquals(1, fwd.activeUdpSessions)
    }

    @Test
    fun oversizedUdpReplyIsFragmentedAndReassemblable() {
        val big = ByteArray(3000).also { Random(7).nextBytes(it) }
        val srv = DatagramSocket(0, InetAddress.getByAddress(loopback))
        cleanup.add(srv)
        Thread {
            try {
                val buf = ByteArray(64)
                val dp = DatagramPacket(buf, buf.size)
                srv.receive(dp)
                srv.send(DatagramPacket(big, big.size, dp.address, dp.port))
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        val cap = Capture()
        val fwd = newForwarder(cap)
        val p = udpPacket(clientIp, loopback, 50002, srv.localPort, "q".toByteArray())
        assertEquals(Verdict.FORWARDED, fwd.forward(p, p.size, allowAll).verdict)

        val frags = ArrayList<ByteArray>()
        var more = true
        while (more) {
            val f = cap.next()
            assertTrue("fragment exceeds MTU", f.size <= 1500)
            assertEquals(0xFFFF, fold(sum16(f, 0, 20)))
            more = (u16(f, 6) and 0x2000) != 0
            frags.add(f)
        }
        assertEquals(3, frags.size)
        assertEquals("all fragments share one IP id", 1, frags.map { u16(it, 4) }.toSet().size)
        val out = ByteArrayOutputStream()
        var expectedOffset = 0
        for (f in frags) {
            assertEquals(expectedOffset, (u16(f, 6) and 0x1FFF) * 8)
            out.write(f, 20, f.size - 20)
            expectedOffset += f.size - 20
        }
        val dgram = out.toByteArray()
        assertEquals(3008, dgram.size)
        assertEquals(0xFFFF, fold(sum16(dgram, 0, dgram.size, pseudo(loopback, clientIp, 17, dgram.size))))
        assertArrayEquals(big, dgram.copyOfRange(8, dgram.size))
    }

    @Test
    fun multicastUdpIsIgnored() {
        val cap = Capture()
        val fwd = newForwarder(cap)
        val mdns = byteArrayOf(224.toByte(), 0, 0, 251.toByte())
        val p = udpPacket(clientIp, mdns, 5353, 5353, "x".toByteArray())
        assertEquals(Verdict.IGNORED, fwd.forward(p, p.size, allowAll).verdict)
        assertEquals(0, fwd.activeUdpSessions)
    }

    @Test
    fun idleUdpSessionsAreCollected() {
        val srv = DatagramSocket(0, InetAddress.getByAddress(loopback))
        cleanup.add(srv)
        val cap = Capture()
        val fwd = newForwarder(cap, limits = ForwarderLimits(udpIdleMs = 100, dnsIdleMs = 100))
        val p = udpPacket(clientIp, loopback, 50003, srv.localPort, "x".toByteArray())
        assertEquals(Verdict.FORWARDED, fwd.forward(p, p.size, allowAll).verdict)
        assertEquals(1, fwd.activeUdpSessions)
        assertTrue("janitor should remove it", waitUntil(12000) { fwd.activeUdpSessions == 0 })
    }

    // ---------------------------------------------------------- robustness

    @Test
    fun tcpDuplicateOutOfOrderAndOverlappingSegments() {
        val (server, _) = startEchoServer()
        val cap = Capture()
        val fwd = newForwarder(cap)
        val c = Client(fwd, cap, server.localPort)
        c.connect()
        val s0 = c.snd

        fun raw(seq: Long, data: String) {
            val p = tcpPacket(clientIp, loopback, c.clientPort, server.localPort, seq, c.rcv, 0x18, data.toByteArray())
            fwd.forward(p, p.size, allowAll)
        }

        // B arrives before A: must be answered with a duplicate ACK for s0 and not delivered.
        raw(s0 + 4, "BBBB")
        val dup = parseTcp(cap.next())
        assertEquals(s0, dup.ack)
        assertEquals(0, dup.payload.size)

        raw(s0, "AAAA")          // in order
        raw(s0 + 4, "BBBB")      // now in order
        raw(s0, "AAAA")          // pure duplicate
        raw(s0 + 4, "BBBBCCCC")  // 4 bytes overlap, 4 new
        c.snd = s0 + 12
        c.waitFor(12)
        // drain any trailing ACKs
        while (c.pump(200)) { }
        assertEquals("AAAABBBBCCCC", String(c.received.toByteArray()))
    }

    @Test
    fun tcpServerClosesFirst() {
        val ss = ServerSocket(0, 5, InetAddress.getByAddress(loopback))
        cleanup.add(ss)
        Thread {
            try {
                val s = ss.accept()
                s.getOutputStream().write("bye".toByteArray())
                s.close()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()

        val cap = Capture()
        val fwd = newForwarder(cap)
        val c = Client(fwd, cap, ss.localPort)
        c.connect()
        c.waitFor(3)
        c.waitForFin()
        assertEquals("bye", String(c.received.toByteArray()))
        c.send(0x11); c.snd += 1
        assertTrue(waitUntil { fwd.activeTcpSessions == 0 })
    }

    @Test
    fun tcpClientResetTearsDownSession() {
        val (server, _) = startEchoServer()
        val cap = Capture()
        val fwd = newForwarder(cap)
        val c = Client(fwd, cap, server.localPort)
        c.connect()
        assertEquals(1, fwd.activeTcpSessions)
        c.send(0x04) // RST
        assertTrue(waitUntil { fwd.activeTcpSessions == 0 })
    }

    @Test
    fun tcpIdleSessionIsResetByJanitor() {
        val (server, _) = startEchoServer()
        val cap = Capture()
        val fwd = newForwarder(cap, limits = ForwarderLimits(tcpIdleMs = 100))
        val c = Client(fwd, cap, server.localPort)
        c.connect()
        assertTrue("idle session should be reaped", waitUntil(12000) { fwd.activeTcpSessions == 0 })
        val rst = parseTcp(cap.next())
        assertTrue(rst.rst)
    }

    @Test
    fun manyParallelConnectionsShareOneForwarder() {
        val (server, _) = startEchoServer()
        val queues = java.util.concurrent.ConcurrentHashMap<Int, Capture>()
        val demux = TunWriter { pkt, len ->
            val port = u16(pkt, 22)
            queues[port]?.write(pkt, len)
        }
        val fwd = PacketForwarder(demux, noProtect)
        cleanup.add(fwd)

        val n = 30
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until n).map { i ->
            Thread {
                try {
                    val cap = Capture()
                    val port = 42000 + i
                    queues[port] = cap
                    val c = Client(fwd, cap, server.localPort, clientPort = port)
                    c.connect()
                    val data = ByteArray(60_000).also { Random(i.toLong()).nextBytes(it) }
                    var pos = 0
                    val start = c.snd
                    while (pos < data.size || c.received.size() < data.size) {
                        while (pos < data.size && (c.snd - c.serverAcked.coerceAtLeast(start)) < minOf(20_000, c.relayWindow)) {
                            val k = minOf(1400, data.size - pos)
                            c.send(0x18, data.copyOfRange(pos, pos + k)); c.snd += k; pos += k
                        }
                        if (!c.pump(5000)) fail("conn $i stalled")
                    }
                    assertArrayEquals(data, c.received.toByteArray())
                    c.send(0x11); c.snd += 1
                    c.waitForFin()
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }.apply { isDaemon = true }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(60_000) }
        if (errors.isNotEmpty()) throw AssertionError("parallel failure: ${errors.first()}", errors.first())
        assertTrue("all sessions closed", waitUntil { fwd.activeTcpSessions == 0 })
    }

    @Test
    fun slowServerShrinksWindowAndNoDataIsLost() {
        val ss = ServerSocket(0, 5, InetAddress.getByAddress(loopback))
        cleanup.add(ss)
        val got = ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        Thread {
            try {
                val s = ss.accept()
                val i = s.getInputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    synchronized(got) { got.write(buf, 0, n) }
                    Thread.sleep(2) // slow consumer
                }
                s.close()
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true }.start()

        val cap = Capture()
        val fwd = newForwarder(cap)
        val c = Client(fwd, cap, ss.localPort)
        c.connect()

        val data = ByteArray(3_000_000).also { Random(99).nextBytes(it) }
        var pos = 0
        val start = c.snd
        var minWindow = 65535
        while (pos < data.size) {
            while (pos < data.size && (c.snd - c.serverAcked.coerceAtLeast(start)) < minOf(60_000, c.relayWindow)) {
                val k = minOf(1400, data.size - pos)
                c.send(0x18, data.copyOfRange(pos, pos + k)); c.snd += k; pos += k
            }
            minWindow = minOf(minWindow, c.relayWindow)
            if (!c.pump(3000)) fail("stalled at $pos (window=${c.relayWindow})")
        }
        // wait until everything is acknowledged and the window has reopened
        val end = System.currentTimeMillis() + 20_000
        while ((c.serverAcked != c.snd || c.relayWindow < 1400) && System.currentTimeMillis() < end) c.pump(200)
        c.send(0x11); c.snd += 1

        assertTrue("server should receive everything", done.await(30, TimeUnit.SECONDS))
        assertArrayEquals(data, synchronized(got) { got.toByteArray() })
        assertTrue("relay must have shrunk its window under load (min=$minWindow)", minWindow < 65535)
    }
}
