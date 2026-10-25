package com.myinternet.share

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class Socks5Server(private val port: Int) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    fun start() {
        running = true
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        AppLog.i("srv", "SOCKS5 서버 시작 :$port")
        pool.execute {
            while (running) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    if (!running) break
                    AppLog.e("srv", "accept 오류", e)
                }
            }
        }
    }

    fun stop() {
        running = false
        AppLog.i("srv", "SOCKS5 서버 중지")
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(c: Socket) {
        try {
            c.tcpNoDelay = true
            val i = DataInputStream(c.getInputStream())
            val o = c.getOutputStream()

            if (i.readUnsignedByte() != 5) return
            val n = i.readUnsignedByte()
            i.readFully(ByteArray(n))
            o.write(byteArrayOf(5, 0))
            o.flush()

            i.readUnsignedByte()
            val cmd = i.readUnsignedByte()
            i.readUnsignedByte()
            val atyp = i.readUnsignedByte()
            val host: String = when (atyp) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { i.readFully(it) }).hostAddress!!
                3 -> String(ByteArray(i.readUnsignedByte()).also { i.readFully(it) })
                4 -> InetAddress.getByAddress(ByteArray(16).also { i.readFully(it) }).hostAddress!!
                else -> return
            }
            val dport = i.readUnsignedShort()

            when (cmd) {
                1 -> tcpConnect(c, i, o, host, dport)
                3 -> udpAssociate(c, i, o)
                else -> reply(o, 7)
            }
        } catch (e: Exception) {
            AppLog.e("srv", "세션 예외 ${e.javaClass.simpleName} ${e.message}")
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun reply(o: OutputStream, code: Int) {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        o.flush()
    }

    private fun tcpConnect(c: Socket, i: InputStream, o: OutputStream, host: String, dport: Int) {
        AppLog.i("srv", "CONNECT ${c.inetAddress.hostAddress} -> $host:$dport")
        val r = Socket()
        try {
            r.connect(InetSocketAddress(host, dport), 10000)
            r.tcpNoDelay = true
        } catch (e: Exception) {
            AppLog.e("srv", "CONNECT 실패 $host:$dport ${e.javaClass.simpleName} ${e.message}")
            try { r.close() } catch (_: Exception) {}
            reply(o, 5)
            return
        }
        reply(o, 0)
        val t = thread(isDaemon = true) {
            try { r.getInputStream().copyTo(o, 65536) } catch (_: Exception) {}
            try { c.shutdownOutput() } catch (_: Exception) {}
        }
        try { i.copyTo(r.getOutputStream(), 65536) } catch (_: Exception) {}
        try { r.shutdownOutput() } catch (_: Exception) {}
        t.join(3000)
        try { r.close() } catch (_: Exception) {}
    }

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, InetAddress>()

    private fun udpAssociate(c: Socket, i: InputStream, o: OutputStream) {
        val us = DatagramSocket(0)
        try { us.receiveBufferSize = 4 shl 20 } catch (_: Exception) {}
        try { us.sendBufferSize = 4 shl 20 } catch (_: Exception) {}
        try { us.trafficClass = 0xB8 } catch (_: Exception) {}
        val bnd = c.localAddress.address
        val rep = ByteArrayOutputStream()
        rep.write(byteArrayOf(5, 0, 0, if (bnd.size == 4) 1 else 4))
        rep.write(bnd)
        rep.write(us.localPort ushr 8)
        rep.write(us.localPort and 255)
        o.write(rep.toByteArray())
        o.flush()

        val clientAddr: InetAddress = c.inetAddress
        AppLog.i("srv", "UDP ASSOCIATE ${clientAddr.hostAddress} 중계포트 ${us.localPort}")
        thread(isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val hdr = 22
            val buf = ByteArray(65535 + hdr)
            val p = DatagramPacket(buf, hdr, buf.size - hdr)
            val sp = DatagramPacket(buf, 0)
            var clientPort = -1
            var outN = 0L
            var inN = 0L
            try {
                while (true) {
                    p.setData(buf, hdr, buf.size - hdr)
                    us.receive(p)
                    val len = p.length
                    if (p.address == clientAddr && (clientPort == -1 || p.port == clientPort)) {
                        clientPort = p.port
                        if (len < 10 || buf[hdr + 2].toInt() != 0) continue
                        var idx = hdr + 3
                        val at = buf[idx++].toInt()
                        val dst: InetAddress = when (at) {
                            1 -> { val a = buf.copyOfRange(idx, idx + 4); idx += 4; InetAddress.getByAddress(a) }
                            4 -> { val a = buf.copyOfRange(idx, idx + 16); idx += 16; InetAddress.getByAddress(a) }
                            3 -> {
                                val l = buf[idx++].toInt() and 255
                                val h = String(buf, idx, l)
                                idx += l
                                dnsCache.getOrPut(h) { InetAddress.getByName(h) }
                            }
                            else -> continue
                        }
                        val dp = ((buf[idx].toInt() and 255) shl 8) or (buf[idx + 1].toInt() and 255)
                        idx += 2
                        sp.setData(buf, idx, hdr + len - idx)
                        sp.address = dst
                        sp.port = dp
                        us.send(sp)
                        outN++
                        if (outN == 1L) AppLog.i("srv", "UDP 첫 패킷 -> ${dst.hostAddress}:$dp")
                    } else if (clientPort != -1) {
                        val a = p.address.address
                        val hl = 4 + a.size + 2
                        val st = hdr - hl
                        buf[st] = 0
                        buf[st + 1] = 0
                        buf[st + 2] = 0
                        buf[st + 3] = (if (a.size == 4) 1 else 4).toByte()
                        System.arraycopy(a, 0, buf, st + 4, a.size)
                        buf[st + 4 + a.size] = (p.port ushr 8).toByte()
                        buf[st + 5 + a.size] = (p.port and 255).toByte()
                        sp.setData(buf, st, hl + len)
                        sp.address = clientAddr
                        sp.port = clientPort
                        us.send(sp)
                        inN++
                    }
                }
            } catch (e: Exception) {
                AppLog.i("srv", "UDP 종료 out=$outN in=$inN (${e.javaClass.simpleName})")
            }
        }

        try { while (i.read() >= 0) { } } catch (_: Exception) {}
        us.close()
    }
}
