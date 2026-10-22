package com.mdkdw1.myinternet

import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

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

    fun start() { running = true; thread(name = "tun-read") { loop() } }
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
        val srcIp = readInt(buf, 12)
        val dstIp = readInt(buf, 16)

        if (proto == 6) handleTcp(buf, len, ihl, srcIp, dstIp)
        else if (proto == 17) handleUdp(buf, len, ihl, srcIp, dstIp)
    }

    private fun handleTcp(buf: ByteArray, len: Int, ihl: Int, srcIp: Int, dstIp: Int) {
        if (len < ihl + 20) return
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl+1].toInt() and 0xFF)
        val dstPort = ((buf[ihl+2].toInt() and 0xFF) shl 8) or (buf[ihl+3].toInt() and 0xFF)
        val seq = readInt(buf, ihl + 4)
        val flags = buf[ihl + 13].toInt() and 0xFF
        val payloadOff = ihl + ((buf[ihl + 12].toInt() and 0xFF) ushr 4) * 4
        if (payloadOff > len) return
        val payload = if (len > payloadOff) buf.copyOfRange(payloadOff, len) else ByteArray(0)

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        if (isSyn && !isAck) {
            val c = TcpConn(srcIp, dstIp, srcPort, dstPort, seq + 1)
            conns[srcPort] = c
            thread { connectUpstream(c) }
            return
        }
        val c = conns[srcPort] ?: return
        if (isRst) { closeConn(c); return }
        if (payload.isNotEmpty()) {
            c.clientSeq = seq + payload.size
            sendTcp(c, seq = c.ourSeq, ack = c.clientSeq, flags = 0x10, payload = null)
            thread {
                try { c.socket?.getOutputStream()?.write(payload) }
                catch (_: Exception) { closeConn(c) }
            }
        }
        if (isFin) {
            try { c.socket?.shutdownOutput() } catch (_: Exception) {}
            sendTcp(c, seq = c.ourSeq, ack = seq + 1 + payload.size, flags = 0x11, payload = null)
            c.ourSeq += 1
        }
    }

    private fun connectUpstream(c: TcpConn) {
        try {
            val sock = Socket()
            MyVpnService.protectSocket(sock)
            sock.connect(InetSocketAddress(srvHost, srvPort), 10000)
            sock.soTimeout = 0
            val i = sock.getInputStream(); val o = sock.getOutputStream()

            o.write(byteArrayOf(0x05, 0x01, 0x02)); o.flush()
            val r1 = ByteArray(2); i.read(r1)
            if (r1.size < 2 || r1[1].toInt() != 0x02) { sock.close(); return }

            val u = srvUser.toByteArray(); val p = srvPass.toByteArray()
            val auth = ByteArray(3 + u.size + p.size)
            auth[0] = 0x01; auth[1] = u.size.toByte(); System.arraycopy(u, 0, auth, 2, u.size)
            auth[2 + u.size] = p.size.toByte(); System.arraycopy(p, 0, auth, 3 + u.size, p.size)
            o.write(auth); o.flush()
            val r2 = ByteArray(2); i.read(r2)
            if (r2.size < 2 || r2[1].toInt() != 0x00) { sock.close(); return }

            val req = ByteArray(10)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
            System.arraycopy(intToBytes(c.dstIp), 0, req, 4, 4)
            req[8] = (c.dstPort ushr 8).toByte(); req[9] = c.dstPort.toByte()
            o.write(req); o.flush()
            val r3 = ByteArray(10); i.read(r3)
            if (r3.size < 2 || r3[1].toInt() != 0x00) { sock.close(); return }

            c.socket = sock
            c.ourSeq = ourSeqBase
            sendTcp(c, seq = c.ourSeq, ack = c.clientSeq, flags = 0x12, payload = null)
            c.ourSeq += 1

            val rb = ByteArray(8192)
            while (running && !c.closed) {
                val n = try { i.read(rb) } catch (_: Exception) { -1 }
                if (n <= 0) break
                sendTcp(c, seq = c.ourSeq, ack = c.clientSeq, flags = 0x18, payload = rb.copyOfRange(0, n))
                c.ourSeq += n
            }
            sendTcp(c, seq = c.ourSeq, ack = c.clientSeq, flags = 0x11, payload = null)
            c.ourSeq += 1
        } catch (_: Exception) {
            try { c.socket?.close() } catch (_: Exception) {}
            conns.remove(c.srcPort)
        }
    }

    private fun closeConn(c: TcpConn) {
        c.closed = true
        try { c.socket?.close() } catch (_: Exception) {}
        conns.remove(c.srcPort)
    }

    private fun sendTcp(c: TcpConn, seq: Int, ack: Int, flags: Int, payload: ByteArray?) {
        if (!running) return
        val plen = payload?.size ?: 0
        val total = 20 + 20 + plen
        val pkt = ByteArray(total)
        pkt[0] = 0x45
        pkt[2] = (total ushr 8).toByte(); pkt[3] = total.toByte()
        pkt[6] = 0x40; pkt[8] = 64; pkt[9] = 6
        writeInt(pkt, 12, c.dstIp); writeInt(pkt, 16, c.srcIp)
        val t = 20
        pkt[t] = (c.dstPort ushr 8).toByte(); pkt[t+1] = c.dstPort.toByte()
        pkt[t+2] = (c.srcPort ushr 8).toByte(); pkt[t+3] = c.srcPort.toByte()
        writeInt(pkt, t+4, seq); writeInt(pkt, t+8, ack)
        pkt[t+12] = (5 shl 4).toByte(); pkt[t+13] = flags.toByte()
        pkt[t+14] = 0xFF.toByte(); pkt[t+15] = 0xFF.toByte()
        if (payload != null) System.arraycopy(payload, 0, pkt, t+20, plen)
        val ipCk = checksum(pkt, 0, 20)
        pkt[10] = (ipCk ushr 8).toByte(); pkt[11] = ipCk.toByte()
        val tcpCk = tcpChecksum(c.dstIp, c.srcIp, pkt, t, 20 + plen)
        pkt[t+16] = (tcpCk ushr 8).toByte(); pkt[t+17] = tcpCk.toByte()
        try { tunOut.write(pkt) } catch (_: Exception) {}
    }

    // ---- UDP (DNS 53만) ----
    private fun handleUdp(buf: ByteArray, len: Int, ihl: Int, srcIp: Int, dstIp: Int) {
        if (len < ihl + 8) return
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl+1].toInt() and 0xFF)
        val dstPort = ((buf[ihl+2].toInt() and 0xFF) shl 8) or (buf[ihl+3].toInt() and 0xFF)
        if (dstPort != 53) return
        val off = ihl + 8
        if (off >= len) return
        val payload = buf.copyOfRange(off, len)
        thread {
            try {
                val s = DatagramSocket()
                MyVpnService.protectSocket(s)
                s.soTimeout = 5000
                s.send(DatagramPacket(payload, payload.size, InetAddress.getByAddress(intToBytes(dstIp)), dstPort))
                val rbuf = ByteArray(4096)
                val rp = DatagramPacket(rbuf, rbuf.size)
                s.receive(rp); s.close()
                sendUdp(srcIp, dstIp, srcPort, dstPort, rbuf.copyOfRange(0, rp.length))
            } catch (_: Exception) {}
        }
    }

    private fun sendUdp(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, payload: ByteArray) {
        if (!running) return
        val total = 20 + 8 + payload.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45
        pkt[2] = (total ushr 8).toByte(); pkt[3] = total.toByte()
        pkt[8] = 64; pkt[9] = 17
        writeInt(pkt, 12, dstIp); writeInt(pkt, 16, srcIp)
        val u = 20
        pkt[u] = (dstPort ushr 8).toByte(); pkt[u+1] = dstPort.toByte()
        pkt[u+2] = (srcPort ushr 8).toByte(); pkt[u+3] = srcPort.toByte()
        val ulen = 8 + payload.size
        pkt[u+4] = (ulen ushr 8).toByte(); pkt[u+5] = ulen.toByte()
        System.arraycopy(payload, 0, pkt, u + 8, payload.size)
        val ipCk = checksum(pkt, 0, 20)
        pkt[10] = (ipCk ushr 8).toByte(); pkt[11] = ipCk.toByte()
        try { tunOut.write(pkt) } catch (_: Exception) {}
    }

    // ---- helpers ----
    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L; var i = off
        while (i < off + len - 1) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i+1].toInt() and 0xFF); i += 2
        }
        if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun tcpChecksum(srcIp: Int, dstIp: Int, tcp: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        sum += (srcIp ushr 16) and 0xFFFF; sum += srcIp and 0xFFFF
        sum += (dstIp ushr 16) and 0xFFFF; sum += dstIp and 0xFFFF
        sum += 6; sum += len
        var i = off
        while (i < off + len - 1) {
            sum += ((tcp[i].toInt() and 0xFF) shl 8) or (tcp[i+1].toInt() and 0xFF); i += 2
        }
        if (i < off + len) sum += (tcp[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off+1].toInt() and 0xFF) shl 16) or
        ((b[off+2].toInt() and 0xFF) shl 8) or (b[off+3].toInt() and 0xFF)

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off+1] = (v ushr 16).toByte()
        b[off+2] = (v ushr 8).toByte(); b[off+3] = v.toByte()
    }

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    class TcpConn(
        val srcIp: Int, val dstIp: Int,
        val srcPort: Int, val dstPort: Int,
        var clientSeq: Int,
    ) {
        var ourSeq: Int = 0
        @Volatile var closed = false
        var socket: Socket? = null
    }
}
