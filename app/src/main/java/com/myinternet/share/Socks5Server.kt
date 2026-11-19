package com.myinternet.share

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class Socks5Server(private val port: Int) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    val activeConnections = AtomicInteger(0)

    // ★ 버그 수정 1: DNS 캐시 크기 제한 (최대 1000개 유지)
    private val dnsCache = object : LinkedHashMap<String, InetAddress>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, InetAddress>?): Boolean {
            return size > 1000
        }
    }

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
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(c: Socket) {
        activeConnections.incrementAndGet()
        try {
            c.tcpNoDelay = true
            // ★ DoS 방어: 10초 내에 데이터를 안 보내면 연결 강제 종료
            c.soTimeout = 10000 

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
            
            // 핸드셰이크 완료 후에는 타임아웃 해제
            c.soTimeout = 0 

            when (cmd) {
                1 -> tcpConnect(c, i, o, host, dport)
                3 -> udpAssociate(c, i, o)
                else -> reply(o, 7)
            }
        } catch (e: Exception) {
            AppLog.e("srv", "세션 예외 ${e.javaClass.simpleName} ${e.message}")
        } finally {
            activeConnections.decrementAndGet()
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun reply(o: OutputStream, code: Int) {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        o.flush()
    }

    private fun resolve(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).sortedBy { if (it is Inet4Address) 0 else 1 }

    private fun tcpConnect(c: Socket, i: InputStream, o: OutputStream, host: String, dport: Int) {
        if (host.startsWith("198.18.")) { reply(o, 5); return }
        var r: Socket? = null
        var err: Exception? = null
        try {
            for (a in resolve(host).take(3)) {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(a, dport), if (a is Inet4Address) 7000 else 3000)
                    r = s; break
                } catch (e: Exception) {
                    err = e
                    try { s.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { err = e }
        
        val rs = r
        if (rs == null) {
            AppLog.e("srv", "CONNECT 실패 $host:$dport ${err?.javaClass?.simpleName}")
            reply(o, 5); return
        }
        rs.tcpNoDelay = true
        reply(o, 0)
        val t = thread(isDaemon = true) {
            try { rs.getInputStream().copyTo(o, 65536) } catch (_: Exception) {}
            try { c.shutdownOutput() } catch (_: Exception) {}
        }
        try { i.copyTo(rs.getOutputStream(), 65536) } catch (_: Exception) {}
        try { rs.shutdownOutput() } catch (_: Exception) {}
        t.join(3000)
        try { rs.close() } catch (_: Exception) {}
    }

    private fun udpAssociate(c: Socket, i: InputStream, o: OutputStream) {
        val us = DatagramSocket(0)
        // ★ 버그 수정 2: 60초 동안 패킷이 없으면 소켓을 자동으로 닫아 좀비 소켓 방지
        try { us.soTimeout = 60000 } catch (_: Exception) {}

        val bnd = if (c.localAddress is Inet4Address) ByteArray(4) else ByteArray(16)
        val rep = ByteArrayOutputStream()
        rep.write(byteArrayOf(5, 0, 0, if (bnd.size == 4) 1 else 4))
        rep.write(bnd)
        rep.write(us.localPort ushr 8)
        rep.write(us.localPort and 255)
        o.write(rep.toByteArray())
        o.flush()

        val clientAddr: InetAddress = c.inetAddress
        thread(isDaemon = true) {
            val hdr = 22
            val buf = ByteArray(65535 + hdr)
            val p = DatagramPacket(buf, hdr, buf.size - hdr)
            val sp = DatagramPacket(buf, 0)
            var clientPort = -1
            var lastActive = System.currentTimeMillis()
            try {
                while (true) {
                    p.setData(buf, hdr, buf.size - hdr)
                    try {
                        us.receive(p)
                        lastActive = System.currentTimeMillis()
                    } catch (e: SocketTimeoutException) {
                        // 60초 동안 패킷이 없으면 루프 종료 및 소켓 회수
                        if (System.currentTimeMillis() - lastActive > 60000) break
                        continue
                    }
                    
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
                                try {
                                    synchronized(dnsCache) { dnsCache.getOrPut(h) { resolve(h).first() } }
                                } catch (e: Exception) { continue }
                            }
                            else -> continue
                        }
                        val dp = ((buf[idx].toInt() and 255) shl 8) or (buf[idx + 1].toInt() and 255)
                        idx += 2
                        sp.setData(buf, idx, hdr + len - idx)
                        sp.address = dst
                        sp.port = dp
                        us.send(sp)
                    } else if (clientPort != -1) {
                        val a = p.address.address
                        val hl = 4 + a.size + 2
                        val st = hdr - hl
                        buf[st] = 0; buf[st + 1] = 0; buf[st + 2] = 0
                        buf[st + 3] = (if (a.size == 4) 1 else 4).toByte()
                        System.arraycopy(a, 0, buf, st + 4, a.size)
                        buf[st + 4 + a.size] = (p.port ushr 8).toByte()
                        buf[st + 5 + a.size] = (p.port and 255).toByte()
                        sp.setData(buf, st, hl + len)
                        sp.address = clientAddr
                        sp.port = clientPort
                        us.send(sp)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { us.close() } catch (_: Exception) {}
            }
        }

        try { while (i.read() >= 0) { } } catch (_: Exception) {}
    }
}
