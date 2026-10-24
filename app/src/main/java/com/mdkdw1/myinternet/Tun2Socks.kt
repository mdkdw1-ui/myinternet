package com.mdkdw1.myinternet

import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
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
    fun ipStr(d: ByteArray, off: Int) = "${d[off].toInt() and 0xFF}.${d[off+1].toInt() and 0xFF}.${d[off+2].toInt() and 0xFF}.${d[off+3].toInt() and 0xFF}"
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

    fun start() {
        running = true
        thread(name = "tun-read") { loop() }
    }

    fun stop() {
        running = false
        conns.values.forEach { try { it.socket?.close() } catch (_: Exception) {} }
        conns.clear()
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
        buf[8] = 64.toByte()
        if (proto == 6) handleTcp(buf, len, ihl)
    }

    private fun bind(s: Socket) {
        val ok = MyVpnService.protectSocket(s)
        android.util.Log.d("Tun2Socks", "bind: protect=$ok")
        val n = LocalHotspotManager.hotspotNetwork
        if (n != null) {
            try {
                n.bindSocket(s)
                android.util.Log.d("Tun2Socks", "bind: bindSocket OK")
            } catch (e: Exception) {
                android.util.Log.w("Tun2Socks", "bind: bindSocket FAIL (protect 유지)", e)
            }
        }
    }

    private fun newSocket(): Socket {
        return try {
            SocketChannel.open(StandardProtocolFamily.INET).socket()
        } catch (e: Exception) {
            android.util.Log.w("Tun2Socks", "IPv4 SocketChannel 실패, 기본 Socket 사용", e)
            Socket()
        }
    }

    private fun openSocks(dstIp: Int, dstPort: Int): Socket? {
        val sock = newSocket()
        try {
            try { sock.sendBufferSize = 65536; sock.receiveBufferSize = 65536 } catch (_: Exception) {}
            bind(sock)
            val addr = InetAddress.getByName(srvHost)
            android.util.Log.d("Tun2Socks", "openSocks: server=$addr isV4=${addr is Inet4Address} port=$srvPort")
            sock.connect(InetSocketAddress(addr, srvPort), 15000)
            sock.soTimeout = 15000
            val i = sock.getInputStream(); val o = sock.getOutputStream()
            o.write(byteArrayOf(5, 1, 2)); o.flush()
            val r1 = ByteArray(2); i.read(r1)
            if (r1.size < 2 || r1[1].toInt() != 2) throw Exception("auth method")
            val u = srvUser.toByteArray(); val p = srvPass.toByteArray()
            o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); o.flush()
            val r2 = ByteArray(2); i.read(r2)
            if (r2.size < 2 || r2[1].toInt() != 0) throw Exception("auth failed")
            val req = byteArrayOf(5, 1, 0, 1) + Utils.intToBytes(dstIp) +
                      byteArrayOf((dstPort ushr 8).toByte(), dstPort.toByte())
            o.write(req); o.flush()
            val r3 = ByteArray(10); i.read(r3)
            if (r3.size < 2 || r3[1].toInt() != 0) throw Exception("connect rep=${r3[1]}")
            sock.soTimeout = 0
            return sock
        } catch (e: Exception) {
            android.util.Log.e("Tun2Socks", "openSocks fail: $e", e)
            try { sock.close() } catch (_: Exception) {}
            return null
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
            val c = TcpConn(srcIp, dstIp, srcPort, dstPort, seq + 1)
            conns[srcPort] = c; thread { connectUpstream(c) }; return
        }
        val c = conns[srcPort] ?: return
        if (isRst) { closeConn(c); return }
        if (payload.isNotEmpty()) {
            c.clientSeq = seq + payload.size
            sendTcp(c, c.ourSeq, c.clientSeq, 0x10, null)
            thread { try { c.socket?.getOutputStream()?.write(payload) } catch (_: Exception) { closeConn(c) } }
        }
        if (isFin) {
            try { c.socket?.shutdownOutput() } catch (_: Exception) {}
            sendTcp(c, c.ourSeq, seq + 1 + payload.size, 0x11, null); c.ourSeq += 1
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
        val rb = ByteArray(1350)
        while (running && !c.closed) {
            val n = try { i.read(rb) } catch (_: Exception) { -1 }
            if (n <= 0) break
            sendTcp(c, c.ourSeq, c.clientSeq, 0x18, rb.copyOfRange(0, n)); c.ourSeq += n
        }
        sendTcp(c, c.ourSeq, c.clientSeq, 0x11, null); c.ourSeq += 1
    }

    private fun closeConn(c: TcpConn) {
        c.closed = true; try { c.socket?.close() } catch (_: Exception) {}
        conns.remove(c.srcPort)
    }

    private fun sendTcp(c: TcpConn, seq: Int, ack: Int, flags: Int, payload: ByteArray?) {
        if (!running) return
        val plen = payload?.size ?: 0; val total = 40 + plen
        val pkt = ByteArray(total)
        pkt[0] = 0x45; pkt[2] = (total ushr 8).toByte(); pkt[3] = total.toByte()
        pkt[6] = 0x40; pkt[8] = 64; pkt[9] = 6
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

    class TcpConn(val srcIp: Int, val dstIp: Int, val srcPort: Int, val dstPort: Int, var clientSeq: Int) {
        var ourSeq = 0; @Volatile var closed = false; var socket: Socket? = null
    }
}
