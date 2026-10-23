package com.mdkdw1.myinternet

import android.util.Log
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.random.Random

object Utils {
    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L; var i = off
        while (i < off + len - 1) { sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i+1].toInt() and 0xFF); i += 2 }
        if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
    fun tcpChecksum(srcIp: Int, dstIp: Int, tcp: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        sum += (srcIp ushr 16) and 0xFFFF; sum += srcIp and 0xFFFF
        sum += (dstIp ushr 16) and 0xFFFF; sum += dstIp and 0xFFFF
        sum += 6; sum += len
        var i = off
        while (i < off + len - 1) { sum += ((tcp[i].toInt() and 0xFF) shl 8) or (tcp[i+1].toInt() and 0xFF); i += 2 }
        if (i < off + len) sum += (tcp[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
    fun udpChecksum(srcIp: Int, dstIp: Int, udp: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        sum += (srcIp ushr 16) and 0xFFFF; sum += srcIp and 0xFFFF
        sum += (dstIp ushr 16) and 0xFFFF; sum += dstIp and 0xFFFF
        sum += 17; sum += len
        var i = off
        while (i < off + len - 1) { sum += ((udp[i].toInt() and 0xFF) shl 8) or (udp[i+1].toInt() and 0xFF); i += 2 }
        if (i < off + len) sum += (udp[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
    fun readInt(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 24) or ((b[off+1].toInt() and 0xFF) shl 16) or ((b[off+2].toInt() and 0xFF) shl 8) or (b[off+3].toInt() and 0xFF)
    fun writeInt(b: ByteArray, off: Int, v: Int) { b[off] = (v ushr 24).toByte(); b[off+1] = (v ushr 16).toByte(); b[off+2] = (v ushr 8).toByte(); b[off+3] = v.toByte() }
    fun intToBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun ipStr(d: ByteArray, off: Int) = "${d[off].toInt() and 0xFF}.${d[off+1].toInt() and 0xFF}.${d[off+2].toInt() and 0xFF}.${d[off+3].toInt() and 0xFF}"

    fun bind(s: Socket) {
        val n = LocalHotspotManager.hotspotNetwork
        if (n != null) { try { n.bindSocket(s) } catch (_: Exception) { MyVpnService.protectSocket(s) } }
        else MyVpnService.protectSocket(s)
    }
    fun bind(s: DatagramSocket) {
        val n = LocalHotspotManager.hotspotNetwork
        if (n != null) { try { n.bindSocket(s) } catch (_: Exception) { MyVpnService.protectSocket(s) } }
        else MyVpnService.protectSocket(s)
    }
}

class Tun2Socks(
    private val tunIn: FileInputStream,
    private val tunOut: FileOutputStream,
    private val srvHost: String,
    private val srvPort: Int,
    private val srvUser: String,
    private val srvPass: String,
) {
    companion object {
        private const val TAG = "Tun2Socks"
        private const val MSS = 1400
    }

    @Volatile private var running = false
    private val conns = ConcurrentHashMap<Int, TcpConn>()
    private val udpNat = ConcurrentHashMap<UdpKey, Long>()
    @Volatile private var udpRelay: UdpRelay? = null
    @Volatile private var relayConnecting = false
    @Volatile private var relayRetryAt = 0L
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        running = true
        thread(name = "tun-read") { loop() }
        thread(name = "t2s-cleaner") {
            while (running) {
                try { Thread.sleep(30000) } catch (_: InterruptedException) { break }
                val now = System.currentTimeMillis()
                udpNat.entries.removeIf { now - it.value > 120000 }
                conns.values.forEach { c ->
                    if ((c.serverFin || c.clientFin) && now - c.lastActive > 60000) closeConn(c)
                }
            }
        }
    }

    fun stop() {
        running = false
        conns.values.forEach { closeConn(it) }
        conns.clear()
        udpRelay?.close(); udpRelay = null
        pool.shutdownNow()
    }

    private fun loop() {
        val buf = ByteArray(32767)
        while (running) {
            val n = try { tunIn.read(buf) } catch (_: Exception) { break }
            if (n < 0) break
            if (n == 0) continue
            try { handlePacket(buf, n) } catch (e: Exception) { Log.w(TAG, "packet error: ${e.message}") }
        }
    }

    private fun handlePacket(buf: ByteArray, rawLen: Int) {
        if (rawLen < 20) return
        val vihl = buf[0].toInt() and 0xFF
        if ((vihl ushr 4) != 4) return
        val ihl = (vihl and 0x0F) * 4
        val totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val len = if (totalLen in 20..rawLen) totalLen else rawLen
        if (len < ihl + 8) return
        val frag = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if ((frag and 0x3FFF) != 0) return
        when (buf[9].toInt() and 0xFF) {
            6 -> handleTcp(buf, len, ihl)
            17 -> handleUdp(buf, len, ihl)
        }
    }

    private fun handleTcp(buf: ByteArray, len: Int, ihl: Int) {
        if (len < ihl + 20) return
        val srcIp = Utils.readInt(buf, 12); val dstIp = Utils.readInt(buf, 16)
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl+1].toInt() and 0xFF)
        val dstPort = ((buf[ihl+2].toInt() and 0xFF) shl 8) or (buf[ihl+3].toInt() and 0xFF)
        val seq = Utils.readInt(buf, ihl + 4)
        val flags = buf[ihl + 13].toInt() and 0xFF
        val payloadOff = ihl + ((buf[ihl + 12].toInt() and 0xFF) ushr 4) * 4
        if (payloadOff > len) return
        val payload = if (len > payloadOff) buf.copyOfRange(payloadOff, len) else ByteArray(0)
        val isSyn = (flags and 0x02) != 0; val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0; val isRst = (flags and 0x04) != 0

        if (isSyn && !isAck) {
            val old = conns[srcPort]
            if (old != null && old.dstIp == dstIp && old.dstPort == dstPort && old.isn == seq && !old.closed) return
            if (old != null) closeConn(old)
            val c = TcpConn(srcIp, dstIp, srcPort, dstPort, seq, seq + 1)
            conns[srcPort] = c
            pool.execute { connectUpstream(c) }
            return
        }

        val c = conns[srcPort] ?: return
        if (c.dstIp != dstIp || c.dstPort != dstPort) return
        if (isRst) { closeConn(c); return }
        if (!c.established) return

        synchronized(c) {
            c.lastActive = System.currentTimeMillis()

            if (payload.isNotEmpty()) {
                if (seq == c.clientSeq) {
                    c.clientSeq += payload.size
                    emit(c, 0x10)
                    c.writer.execute {
                        try { c.socket?.getOutputStream()?.write(payload) }
                        catch (_: Exception) { closeConn(c) }
                    }
                } else {
                    emit(c, 0x10)
                    return
                }
            }

            if (isFin) {
                if (seq + payload.size == c.clientSeq) {
                    c.clientSeq += 1
                    c.clientFin = true
                    c.writer.execute { try { c.socket?.shutdownOutput() } catch (_: Exception) {} }
                }
                emit(c, 0x10)
                if (c.serverFin) closeConn(c)
            }
        }
    }

    private fun openSocks(dstIp: Int, dstPort: Int): Socket? {
        val sock = Socket()
        try {
            try { sock.sendBufferSize = 65536; sock.receiveBufferSize = 65536 } catch (_: Exception) {}
            sock.tcpNoDelay = true
            Utils.bind(sock)
            sock.connect(InetSocketAddress(srvHost, srvPort), 20000)
            sock.soTimeout = 20000
            val i = DataInputStream(sock.getInputStream()); val o = sock.getOutputStream()
            o.write(byteArrayOf(5, 1, 2)); o.flush()
            val r1 = ByteArray(2); i.readFully(r1)
            if (r1[1].toInt() != 2) throw Exception("auth method")
            val u = srvUser.toByteArray(); val p = srvPass.toByteArray()
            o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); o.flush()
            val r2 = ByteArray(2); i.readFully(r2)
            if (r2[1].toInt() != 0) throw Exception("auth failed")
            o.write(byteArrayOf(5, 1, 0, 1) + Utils.intToBytes(dstIp) +
                byteArrayOf((dstPort ushr 8).toByte(), dstPort.toByte())); o.flush()
            val r3 = ByteArray(10); i.readFully(r3)
            if (r3[1].toInt() != 0) throw Exception("connect rep=${r3[1]}")
            sock.soTimeout = 0
            return sock
        } catch (e: Exception) {
            Log.w(TAG, "socks connect fail ${Utils.ipStr(Utils.intToBytes(dstIp), 0)}:$dstPort ${e.message}")
            try { sock.close() } catch (_: Exception) {}
            return null
        }
    }

    private fun connectUpstream(c: TcpConn) {
        val sock = openSocks(c.dstIp, c.dstPort)
        if (sock == null || c.closed) {
            try { sock?.close() } catch (_: Exception) {}
            if (!c.closed) emit(c, 0x14)
            closeConn(c); return
        }
        c.socket = sock
        c.established = true
        emit(c, 0x12)
        val i = sock.getInputStream()
        val rb = ByteArray(MSS)
        while (running && !c.closed) {
            val n = try { i.read(rb) } catch (_: Exception) { -1 }
            if (n <= 0) break
            c.lastActive = System.currentTimeMillis()
            emit(c, 0x18, rb.copyOfRange(0, n))
        }
        if (!c.closed) {
            synchronized(c) {
                emit(c, 0x11)
                c.serverFin = true
                if (c.clientFin) closeConn(c)
            }
        }
    }

    private fun closeConn(c: TcpConn) {
        c.closed = true
        try { c.socket?.close() } catch (_: Exception) {}
        try { c.writer.shutdown() } catch (_: Exception) {}
        conns.remove(c.srcPort, c)
    }

    private fun emit(c: TcpConn, flags: Int, payload: ByteArray? = null) {
        if (!running) return
        synchronized(c) {
            val plen = payload?.size ?: 0
            writeTcp(c, c.ourSeq, c.clientSeq, flags, payload)
            if ((flags and 0x03) != 0) c.ourSeq += 1
            c.ourSeq += plen
        }
    }

    private fun writeTcp(c: TcpConn, seq: Int, ack: Int, flags: Int, payload: ByteArray?) {
        val plen = payload?.size ?: 0
        val optLen = if ((flags and 0x02) != 0) 4 else 0
        val tcpLen = 20 + optLen + plen
        val total = 20 + tcpLen
        val pkt = ByteArray(total)
        pkt[0] = 0x45; pkt[2] = (total ushr 8).toByte(); pkt[3] = total.toByte()
        pkt[6] = 0x40; pkt[8] = 64; pkt[9] = 6
        Utils.writeInt(pkt, 12, c.dstIp); Utils.writeInt(pkt, 16, c.srcIp)
        val t = 20
        pkt[t] = (c.dstPort ushr 8).toByte(); pkt[t+1] = c.dstPort.toByte()
        pkt[t+2] = (c.srcPort ushr 8).toByte(); pkt[t+3] = c.srcPort.toByte()
        Utils.writeInt(pkt, t+4, seq); Utils.writeInt(pkt, t+8, ack)
        pkt[t+12] = (((20 + optLen) / 4) shl 4).toByte(); pkt[t+13] = flags.toByte()
        pkt[t+14] = 0xFF.toByte(); pkt[t+15] = 0xFF.toByte()
        if (optLen > 0) {
            pkt[t+20] = 2; pkt[t+21] = 4
            pkt[t+22] = (MSS ushr 8).toByte(); pkt[t+23] = MSS.toByte()
        }
        if (payload != null) System.arraycopy(payload, 0, pkt, t + 20 + optLen, plen)
        val ipCk = Utils.checksum(pkt, 0, 20)
        pkt[10] = (ipCk ushr 8).toByte(); pkt[11] = ipCk.toByte()
        val tcpCk = Utils.tcpChecksum(c.dstIp, c.srcIp, pkt, t, tcpLen)
        pkt[t+16] = (tcpCk ushr 8).toByte(); pkt[t+17] = tcpCk.toByte()
        try { tunOut.write(pkt) } catch (_: Exception) {}
    }

    private fun handleUdp(buf: ByteArray, len: Int, ihl: Int) {
        val srcIp = Utils.readInt(buf, 12); val dstIp = Utils.readInt(buf, 16)
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl+1].toInt() and 0xFF)
        val dstPort = ((buf[ihl+2].toInt() and 0xFF) shl 8) or (buf[ihl+3].toInt() and 0xFF)
        val off = ihl + 8
        if (off >= len) return
        val payload = buf.copyOfRange(off, len)

        if (dstPort == 53) {
            pool.execute {
                var s: Socket? = null
                try {
                    s = openSocks(dstIp, 53) ?: return@execute
                    s.soTimeout = 5000
                    val o = s.getOutputStream(); val i = DataInputStream(s.getInputStream())
                    o.write(byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload); o.flush()
                    val n = i.readUnsignedShort()
                    val resp = ByteArray(n); i.readFully(resp)
                    tunOut.write(buildUdpToTun(srcIp, srcPort, dstIp, dstPort, resp))
                } catch (_: Exception) {
                } finally { try { s?.close() } catch (_: Exception) {} }
            }
            return
        }

        val relay = getUdpRelay() ?: return
        udpNat[UdpKey(srcIp, srcPort, dstIp, dstPort)] = System.currentTimeMillis()
        relay.send(dstIp, dstPort, payload)
    }

    private fun getUdpRelay(): UdpRelay? {
        udpRelay?.let { if (it.isAlive) return it }
        val now = System.currentTimeMillis()
        if (relayConnecting || now < relayRetryAt) return null
        relayConnecting = true
        pool.execute {
            try {
                val r = UdpRelay(srvHost, srvPort, srvUser, srvPass)
                r.setResponseHandler { h, p, d -> onUdpResponse(h, p, d) }
                r.start()
                udpRelay = r
            } catch (e: Exception) {
                Log.w(TAG, "udp relay fail: ${e.message}")
                relayRetryAt = System.currentTimeMillis() + 3000
            } finally { relayConnecting = false }
        }
        return null
    }

    private fun onUdpResponse(srcHost: String, srcPort: Int, payload: ByteArray) {
        var bestKey: UdpKey? = null; var bestTime = 0L
        for ((k, t) in udpNat) {
            if (k.dstPort == srcPort && t > bestTime && Utils.ipStr(Utils.intToBytes(k.dstIp), 0) == srcHost) { bestKey = k; bestTime = t }
        }
        if (bestKey == null) {
            for ((k, t) in udpNat) if (k.dstPort == srcPort && t > bestTime) { bestKey = k; bestTime = t }
        }
        if (bestKey == null) {
            for ((k, t) in udpNat) if (t > bestTime) { bestKey = k; bestTime = t }
        }
        val k = bestKey ?: return
        try { tunOut.write(buildUdpToTun(k.srcIp, k.srcPort, k.dstIp, k.dstPort, payload)) } catch (_: Exception) {}
        udpNat[k] = System.currentTimeMillis()
    }

    private fun buildUdpToTun(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45; pkt[2] = (total ushr 8).toByte(); pkt[3] = total.toByte()
        pkt[8] = 64; pkt[9] = 17
        Utils.writeInt(pkt, 12, dstIp); Utils.writeInt(pkt, 16, srcIp)
        val u = 20
        pkt[u] = (dstPort ushr 8).toByte(); pkt[u+1] = dstPort.toByte()
        pkt[u+2] = (srcPort ushr 8).toByte(); pkt[u+3] = srcPort.toByte()
        val ulen = 8 + payload.size
        pkt[u+4] = (ulen ushr 8).toByte(); pkt[u+5] = ulen.toByte()
        System.arraycopy(payload, 0, pkt, u + 8, payload.size)
        var uc = Utils.udpChecksum(dstIp, srcIp, pkt, u, ulen)
        if (uc == 0) uc = 0xFFFF
        pkt[u+6] = (uc ushr 8).toByte(); pkt[u+7] = uc.toByte()
        val ipCk = Utils.checksum(pkt, 0, 20)
        pkt[10] = (ipCk ushr 8).toByte(); pkt[11] = ipCk.toByte()
        return pkt
    }

    data class UdpKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    private class UdpRelay(private val host: String, private val port: Int, private val user: String, private val pass: String) {
        @Volatile private var alive = false
        private var control: Socket? = null
        private var udp: DatagramSocket? = null
        private var relayAddr: InetAddress? = null
        private var relayPort = 0
        @Volatile private var responseHandler: ((String, Int, ByteArray) -> Unit)? = null

        val isAlive get() = alive
        fun setResponseHandler(h: (String, Int, ByteArray) -> Unit) { responseHandler = h }

        fun start() {
            val c = Socket()
            control = c
            try {
                Utils.bind(c)
                c.connect(InetSocketAddress(host, port), 10000)
                c.tcpNoDelay = true
                c.soTimeout = 10000
                val ci = DataInputStream(c.getInputStream()); val co = c.getOutputStream()
                co.write(byteArrayOf(5, 1, 2)); co.flush()
                val r1 = ByteArray(2); ci.readFully(r1); if (r1[1].toInt() != 2) throw Exception("auth method")
                val u = user.toByteArray(); val p = pass.toByteArray()
                co.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); co.flush()
                val r2 = ByteArray(2); ci.readFully(r2); if (r2[1].toInt() != 0) throw Exception("auth failed")
                co.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); co.flush()
                val r3 = ByteArray(10); ci.readFully(r3); if (r3[1].toInt() != 0) throw Exception("associate failed")
                var addr = InetAddress.getByAddress(r3.copyOfRange(4, 8))
                if (addr.isAnyLocalAddress) addr = InetAddress.getByName(host)
                relayAddr = addr
                relayPort = ((r3[8].toInt() and 0xFF) shl 8) or (r3[9].toInt() and 0xFF)
                c.soTimeout = 0

                val ds = DatagramSocket()
                try { ds.sendBufferSize = 262144; ds.receiveBufferSize = 262144 } catch (_: Exception) {}
                Utils.bind(ds)
                udp = ds
                alive = true
                thread(name = "udp-recv") { recvLoop() }
                thread(name = "udp-ctrl") {
                    try { val b = ByteArray(1); while (alive && ci.read(b) != -1) {} } catch (_: Exception) {}
                    close()
                }
            } catch (e: Exception) { close(); throw e }
        }

        fun send(dstIp: Int, dstPort: Int, payload: ByteArray) {
            if (!alive) return
            val h = ByteArray(10); h[3] = 0x01
            System.arraycopy(Utils.intToBytes(dstIp), 0, h, 4, 4)
            h[8] = (dstPort ushr 8).toByte(); h[9] = dstPort.toByte()
            val pkt = h + payload
            try { udp?.send(DatagramPacket(pkt, pkt.size, relayAddr, relayPort)) } catch (_: Exception) {}
        }

        private fun recvLoop() {
            val buf = ByteArray(65535)
            while (alive) {
                try {
                    val p = DatagramPacket(buf, buf.size); udp?.receive(p)
                    val data = p.data.copyOfRange(p.offset, p.offset + p.length)
                    if (data.size < 10) continue
                    val srcHost: String
                    val off: Int
                    when (data[3].toInt() and 0xFF) {
                        0x01 -> { srcHost = Utils.ipStr(data, 4); off = 8 }
                        0x03 -> { val l = data[4].toInt() and 0xFF; srcHost = String(data, 5, l); off = 5 + l }
                        0x04 -> { srcHost = "::"; off = 20 }
                        else -> continue
                    }
                    if (data.size < off + 2) continue
                    val srcPort = ((data[off].toInt() and 0xFF) shl 8) or (data[off+1].toInt() and 0xFF)
                    val payload = data.copyOfRange(off + 2, data.size)
                    responseHandler?.invoke(srcHost, srcPort, payload)
                } catch (_: Exception) { if (!alive) break }
            }
        }

        fun close() {
            alive = false
            try { control?.close() } catch (_: Exception) {}
            try { udp?.close() } catch (_: Exception) {}
        }
    }

    class TcpConn(
        val srcIp: Int, val dstIp: Int, val srcPort: Int, val dstPort: Int,
        val isn: Int,
        var clientSeq: Int
    ) {
        var ourSeq: Int = Random.nextInt() and 0x7FFFFFFF
        @Volatile var closed = false
        @Volatile var established = false
        @Volatile var clientFin = false
        @Volatile var serverFin = false
        @Volatile var lastActive = System.currentTimeMillis()
        @Volatile var socket: Socket? = null
        val writer = Executors.newSingleThreadExecutor()
    }
}
