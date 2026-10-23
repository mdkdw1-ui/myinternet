package com.mdkdw1.myinternet

import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

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
    fun ipStr(d: ByteArray, off: Int) = "\${d[off].toInt() and 0xFF}.\${d[off+1].toInt() and 0xFF}.\${d[off+2].toInt() and 0xFF}.\${d[off+3].toInt() and 0xFF}"
}

class Tun2Socks(
    private val tunIn: FileInputStream,
    private val tunOut: FileOutputStream,
    private val srvHost: String,
    private val srvPort: Int,
    private val srvUser: String,
    private val srvPass: String,
) {
    @Volatile private var running = false
    private val conns = ConcurrentHashMap<Int, TcpConn>()
    private val ourSeqBase = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
    private val udpNat = ConcurrentHashMap<UdpKey, Long>()
    @Volatile private var udpRelay: UdpRelay? = null

    fun start() {
        running = true
        thread(name = "tun-read") { loop() }
    }

    fun stop() {
        running = false
        conns.values.forEach { try { it.socket?.close() } catch (_: Exception) {} }
        conns.clear()
        udpRelay?.close(); udpRelay = null
    }

    private fun loop() {
        val buf = ByteArray(32767)
        while (running) {
            try {
                val n = tunIn.read(buf)
                if (n <= 0) continue
                handlePacket(buf, n)
            } catch (_: Exception) { break }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        if (len < 20) return
        val vihl = buf[0].toInt() and 0xFF
        if ((vihl ushr 4) != 4) return
        val ihl = (vihl and 0x0F) * 4
        if (len < ihl + 8) return
        val proto = buf[9].toInt() and 0xFF
        buf[8] = 65.toByte()
        if (proto == 6) handleTcp(buf, len, ihl)
        else if (proto == 17) handleUdp(buf, len, ihl)
    }

    private fun bind(s: Socket) {
        val n = LocalHotspotManager.hotspotNetwork
        if (n != null) { try { n.bindSocket(s) } catch (_: Exception) { MyVpnService.protectSocket(s) } }
        else MyVpnService.protectSocket(s)
    }
    private fun bind(s: DatagramSocket) {
        val n = LocalHotspotManager.hotspotNetwork
        if (n != null) { try { n.bindSocket(s) } catch (_: Exception) { MyVpnService.protectSocket(s) } }
        else MyVpnService.protectSocket(s)
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
            val c = TcpConn(srcIp, dstIp, srcPort, dstPort, seq + 1)
            conns[srcPort] = c; thread { connectUpstream(c) }; return
        }
        val c = conns[srcPort] ?: return
        if (isRst) { closeConn(c); return }
        if (payload.isNotEmpty()) {
            c.clientSeq = seq + payload.size
            sendTcp(c, c.ourSeq, c.clientSeq, 0x10, null)
            c.writer.execute {
                try { c.socket?.getOutputStream()?.write(payload) } catch (_: Exception) { closeConn(c) }
            }
        }
        if (isFin) {
            try { c.socket?.shutdownOutput() } catch (_: Exception) {}
            sendTcp(c, c.ourSeq, seq + 1 + payload.size, 0x11, null); c.ourSeq += 1
        }
    }

    private fun openSocks(dstIp: Int, dstPort: Int): Socket? {
        val sock = Socket()
        try {
            bind(sock)
            sock.connect(InetSocketAddress(srvHost, srvPort), 10000)
            sock.soTimeout = 10000
            val i = DataInputStream(sock.getInputStream()); val o = sock.getOutputStream()
            o.write(byteArrayOf(5, 1, 2)); o.flush()
            val r1 = ByteArray(2); i.readFully(r1)
            if (r1[1].toInt() != 2) throw Exception("auth method")
            val u = srvUser.toByteArray(); val p = srvPass.toByteArray()
            o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); o.flush()
            val r2 = ByteArray(2); i.readFully(r2)
            if (r2[1].toInt() != 0) throw Exception("auth failed")
            o.write(byteArrayOf(5, 1, 0, 1) + Utils.intToBytes(dstIp) + byteArrayOf((dstPort ushr 8).toByte(), dstPort.toByte())); o.flush()
            val r3 = ByteArray(10); i.readFully(r3)
            if (r3[1].toInt() != 0) throw Exception("connect rep=\${r3[1]}")
            sock.soTimeout = 0
            return sock
        } catch (e: Exception) {
            try { sock.close() } catch (_: Exception) {}
            return null
        }
    }

    private fun connectUpstream(c: TcpConn) {
        val sock = openSocks(c.dstIp, c.dstPort)
        if (sock == null) {
            sendTcp(c, 0, c.clientSeq, 0x14, null)
            conns.remove(c.srcPort); return
        }
        c.socket = sock; c.ourSeq = ourSeqBase
        sendTcp(c, c.ourSeq, c.clientSeq, 0x12, null); c.ourSeq += 1
        val i = sock.getInputStream()
        val rb = ByteArray(1360)
        while (running && !c.closed) {
            val n = try { i.read(rb) } catch (_: Exception) { -1 }
            if (n <= 0) break
            sendTcp(c, c.ourSeq, c.clientSeq, 0x18, rb.copyOfRange(0, n)); c.ourSeq += n
        }
        sendTcp(c, c.ourSeq, c.clientSeq, 0x11, null); c.ourSeq += 1
    }

    private fun closeConn(c: TcpConn) {
        c.closed = true; try { c.socket?.close() } catch (_: Exception) {}
        try { c.writer.shutdown() } catch (_: Exception) {}
        conns.remove(c.srcPort)
    }

    private fun sendTcp(c: TcpConn, seq: Int, ack: Int, flags: Int, payload: ByteArray?) {
        if (!running) return
        val plen = payload?.size ?: 0; val total = 40 + plen
        val pkt = ByteArray(total)
        pkt[0] = 0x45; pkt[2] = (total ushr 8).toByte(); pkt[3] = total.toByte()
        pkt[6] = 0x40; pkt[8] = 65; pkt[9] = 6
        Utils.writeInt(pkt, 12, c.dstIp); Utils.writeInt(pkt, 16, c.srcIp)
        val t = 20
        pkt[t] = (c.dstPort ushr 8).toByte(); pkt[t+1] = c.dstPort.toByte()
        pkt[t+2] = (c.srcPort ushr 8).toByte(); pkt[t+3] = c.srcPort.toByte()
        Utils.writeInt(pkt, t+4, seq); Utils.writeInt(pkt, t+8, ack)
        pkt[t+12] = (5 shl 4).toByte(); pkt[t+13] = flags.toByte()
        pkt[t+14] = 0xFF.toByte(); pkt[t+15] = 0xFF.toByte()
        if (payload != null) System.arraycopy(payload, 0, pkt, t+20, plen)
        val ipCk = Utils.checksum(pkt, 0, 20)
        pkt[10] = (ipCk ushr 8).toByte(); pkt[11] = ipCk.toByte()
        val tcpCk = Utils.tcpChecksum(c.dstIp, c.srcIp, pkt, t, 20 + plen)
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
            thread {
                try {
                    val s = openSocks(dstIp, 53) ?: return@thread
                    s.soTimeout = 5000
                    val o = s.getOutputStream(); val i = DataInputStream(s.getInputStream())
                    o.write(byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload); o.flush()
                    val lenResp = i.readUnsignedShort()
                    val resp = ByteArray(lenResp); i.readFully(resp)
                    s.close()
                    try { tunOut.write(buildUdpToTun(srcIp, srcPort, dstIp, dstPort, resp)) } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
            return
        }

        val relay = ensureUdpRelay() ?: return
        val key = UdpKey(srcIp, srcPort, dstIp, dstPort)
        udpNat[key] = System.currentTimeMillis()
        relay.send(dstIp, dstPort, payload)
    }

    private fun ensureUdpRelay(): UdpRelay? {
        udpRelay?.let { if (it.isAlive) return it }
        return try {
            UdpRelay(srvHost, srvPort, srvUser, srvPass).also { relay ->
                relay.start()
                relay.setResponseHandler { srcHost, srcPort, payload ->
                    val now = System.currentTimeMillis()
                    var bestKey: UdpKey? = null; var bestTime = 0L
                    for ((k, t) in udpNat) {
                        val dstHost = Utils.ipStr(Utils.intToBytes(k.dstIp), 0)
                        if (dstHost == srcHost && k.dstPort == srcPort && t > bestTime) { bestKey = k; bestTime = t }
                    }
                    val k = bestKey ?: return@setResponseHandler
                    val pkt = buildUdpToTun(k.srcIp, k.srcPort, k.dstIp, k.dstPort, payload)
                    try { tunOut.write(pkt) } catch (_: Exception) {}
                    udpNat[k] = now
                }
                udpRelay = relay
            }
        } catch (_: Exception) { null }
    }

    private fun buildUdpToTun(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45; pkt[2] = (total ushr 8).toByte(); pkt[3] = total.toByte()
        pkt[8] = 65; pkt[9] = 17
        Utils.writeInt(pkt, 12, dstIp); Utils.writeInt(pkt, 16, srcIp)
        val u = 20
        pkt[u] = (dstPort ushr 8).toByte(); pkt[u+1] = dstPort.toByte()
        pkt[u+2] = (srcPort ushr 8).toByte(); pkt[u+3] = srcPort.toByte()
        val ulen = 8 + payload.size
        pkt[u+4] = (ulen ushr 8).toByte(); pkt[u+5] = ulen.toByte()
        System.arraycopy(payload, 0, pkt, u + 8, payload.size)
        val uc = Utils.udpChecksum(dstIp, srcIp, pkt, u, ulen)
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
            control = Socket()
            val n = LocalHotspotManager.hotspotNetwork
            if (n != null) { try { n.bindSocket(control!!) } catch (_: Exception) { MyVpnService.protectSocket(control!!) } }
            else MyVpnService.protectSocket(control!!)
            control!!.connect(InetSocketAddress(host, port), 10000)
            val ci = control!!.getInputStream(); val co = control!!.getOutputStream()
            co.write(byteArrayOf(0x05, 0x01, 0x02)); co.flush()
            val r1 = ByteArray(2); ci.read(r1); if (r1[1].toInt() != 0x02) throw Exception("auth method")
            val u = user.toByteArray(); val p = pass.toByteArray()
            val a = ByteArray(3 + u.size + p.size)
            a[0] = 0x01; a[1] = u.size.toByte(); System.arraycopy(u, 0, a, 2, u.size)
            a[2 + u.size] = p.size.toByte(); System.arraycopy(p, 0, a, 3 + u.size, p.size)
            co.write(a); co.flush()
            val r2 = ByteArray(2); ci.read(r2); if (r2[1].toInt() != 0x00) throw Exception("auth failed")
            co.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); co.flush()
            val r3 = ByteArray(10); ci.read(r3); if (r3[1].toInt() != 0x00) throw Exception("associate failed")
            relayAddr = InetAddress.getByAddress(r3.copyOfRange(4, 8))
            if (relayAddr!!.isAnyLocalAddress) relayAddr = InetAddress.getByName(host)
            relayPort = ((r3[8].toInt() and 0xFF) shl 8) or (r3[9].toInt() and 0xFF)
            udp = DatagramSocket()
            if (n != null) { try { n.bindSocket(udp!!) } catch (_: Exception) { MyVpnService.protectSocket(udp!!) } }
            else MyVpnService.protectSocket(udp!!)
            alive = true
            thread(name = "udp-recv") { recvLoop() }
            thread(name = "udp-ctrl") { try { val b = ByteArray(1); while (alive && ci.read(b) != -1) {} } catch (_: Exception) {}; close() }
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
                    val atyp = data[3].toInt() and 0xFF
                    var off = 4
                    val srcHost: String
                    when (atyp) {
                        0x01 -> { srcHost = Utils.ipStr(data, 4); off = 8 }
                        0x03 -> { val l = data[4].toInt() and 0xFF; srcHost = String(data, 5, l); off = 5 + l }
                        0x04 -> { srcHost = try { InetAddress.getByAddress(data.copyOfRange(4, 20)).hostAddress ?: "::" } catch (_: Exception) { "::" }; off = 20 }
                        else -> continue
                    }
                    val srcPort = ((data[off].toInt() and 0xFF) shl 8) or (data[off+1].toInt() and 0xFF)
                    val payload = data.copyOfRange(off + 2, data.size)
                    responseHandler?.invoke(srcHost, srcPort, payload)
                } catch (_: Exception) { if (!alive) break }
            }
        }
        fun close() { alive = false; try { control?.close() } catch (_: Exception) {}; try { udp?.close() } catch (_: Exception) {} }
    }

    class TcpConn(val srcIp: Int, val dstIp: Int, val srcPort: Int, val dstPort: Int, var clientSeq: Int) {
        var ourSeq = 0; @Volatile var closed = false; var socket: Socket? = null
        val writer = Executors.newSingleThreadExecutor()
    }
}
