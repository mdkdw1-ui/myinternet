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
    private val connectionLimit = java.util.concurrent.Semaphore(300)
    private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var running = false
    val activeConnections = AtomicInteger(0)

    private val dnsCache = object : LinkedHashMap<String, List<InetAddress>>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<InetAddress>>?): Boolean {
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
        try {
            for (s in activeSockets) {
                try { s.close() } catch (_: Exception) {}
            }
            activeSockets.clear()
        } catch (_: Exception) {}
    }

    private fun handle(c: Socket) {
        if (!connectionLimit.tryAcquire()) {
            AppLog.e("srv", "연결 거부: 최대 접속자 수 초과")
            try { c.close() } catch (_: Exception) {}
            return
        }
        activeConnections.incrementAndGet()
        activeSockets.add(c)
        try {
            c.tcpNoDelay = true
            c.keepAlive = true
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

            c.soTimeout = 0

            when (cmd) {
                1 -> tcpConnect(c, i, o, host, dport)
                3 -> udpAssociate(c, i, o)
                else -> reply(o, 7)
            }
        } catch (e: Exception) {
            if (e !is java.io.EOFException && e !is SocketException) {
                AppLog.e("srv", "세션 예외 ${e.javaClass.simpleName} ${e.message}")
            }
        } finally {
            activeConnections.decrementAndGet()
            activeSockets.remove(c)
            try { c.close() } catch (_: Exception) {}
            connectionLimit.release()
        }
    }

    private fun reply(o: OutputStream, code: Int) {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        o.flush()
    }

    private fun resolve(host: String): List<InetAddress> {
        synchronized(dnsCache) {
            dnsCache[host]?.let { return it }
        }
        val list = InetAddress.getAllByName(host).sortedBy { if (it is Inet4Address) 0 else 1 }
        if (list.isNotEmpty()) {
            synchronized(dnsCache) { dnsCache[host] = list }
        }
        return list
    }

    private fun tcpConnect(c: Socket, i: InputStream, o: OutputStream, host: String, dport: Int) {
        if (host.startsWith("198.18.")) { reply(o, 5); return }
        var r: Socket? = null
        var err: Exception? = null
        val startTime = System.currentTimeMillis()
        try {
            val addrs = resolve(host)
            val v4Only = addrs.filterIsInstance<Inet4Address>()
            val targets = if (v4Only.isNotEmpty()) v4Only.take(3) else addrs.take(3)

            for (a in targets) {
                if (System.currentTimeMillis() - startTime > 15000) {
                    err = Exception("전체 타임아웃 15초 초과")
                    break
                }
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress(a, dport), 6000)
                    r = s
                    break
                } catch (e: Exception) {
                    err = e
                    try { s.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { err = e }

        val rs = r
        if (rs == null) {
            AppLog.e("srv", "CONNECT 실패 $host:$dport ${err?.javaClass?.simpleName}")
            reply(o, 5)
            return
        }
        activeSockets.add(rs)
        reply(o, 0)
        val t = thread(isDaemon = true) {
            try { rs.getInputStream().copyTo(o, 65536) } catch (_: Exception) {}
            try { c.shutdownOutput() } catch (_: Exception) {}
        }
        try { i.copyTo(rs.getOutputStream(), 65536) } catch (_: Exception) {}
        try { rs.shutdownOutput() } catch (_: Exception) {}
        t.join(3000)
        activeSockets.remove(rs)
        try { rs.close() } catch (_: Exception) {}
    }

    private fun udpAssociate(c: Socket, i: InputStream, o: OutputStream) {
        val us = DatagramSocket(0)
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
                                try { resolve(h).first() } catch (e: Exception) { continue }
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
        try { us.close() } catch (_: Exception) {}
    }
}
