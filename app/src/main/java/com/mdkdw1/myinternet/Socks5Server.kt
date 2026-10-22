package com.mdkdw1.myinternet

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class Socks5Server(
    private val port: Int,
    private val username: String,
    private val password: String
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()

    val activeConnections = AtomicInteger(0)
    val totalConnections = AtomicInteger(0)
    val bytesTransferred = AtomicLong(0)

    fun start() {
        if (running) return
        running = true
        thread(name = "socks5-accept") {
            try {
                serverSocket = ServerSocket(port)
                while (running) {
                    val c = try { serverSocket?.accept() ?: break } catch (_: Exception) { break }
                    thread { handle(c) }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        activeSockets.forEach { try { it.close() } catch (_: Exception) {} }
        activeSockets.clear()
    }

    private fun handle(s: Socket) {
        activeSockets.add(s)
        totalConnections.incrementAndGet()
        var authenticated = false
        try {
            val i = s.getInputStream(); val o = s.getOutputStream()
            if (i.read() != 0x05) return
            val n = i.read(); val m = ByteArray(n); i.read(m)
            if (!m.contains(0x02.toByte())) { o.write(byteArrayOf(0x05, 0xFF.toByte())); return }
            o.write(byteArrayOf(0x05, 0x02)); o.flush()

            if (i.read() != 0x01) return
            val ul = i.read(); val un = ByteArray(ul); i.read(un)
            val pl = i.read(); val pw = ByteArray(pl); i.read(pw)
            if (String(un) != username || String(pw) != password) {
                o.write(byteArrayOf(0x01, 0x01)); return
            }
            o.write(byteArrayOf(0x01, 0x00)); o.flush()
            authenticated = true
            activeConnections.incrementAndGet()

            if (i.read() != 0x05) return
            val cmd = i.read(); i.read()
            val host = when (i.read()) {
                0x01 -> { val b = ByteArray(4); i.read(b); InetAddress.getByAddress(b).hostAddress ?: "0.0.0.0" }
                0x03 -> { val l = i.read(); val b = ByteArray(l); i.read(b); String(b) }
                0x04 -> { val b = ByteArray(16); i.read(b); InetAddress.getByAddress(b).hostAddress ?: "::" }
                else -> return
            }
            val dp = (i.read() shl 8) or i.read()
            if (cmd != 0x01) { o.write(byteArrayOf(0x05,0x07,0,1,0,0,0,0,0,0)); return }

            val remote = try { Socket(host, dp) } catch (_: Exception) {
                o.write(byteArrayOf(0x05,0x05,0,1,0,0,0,0,0,0)); return
            }
            activeSockets.add(remote)
            o.write(byteArrayOf(0x05,0x00,0x00,0x01,0,0,0,0,0,0)); o.flush()

            val t = thread { pipe(s, remote) }
            pipe(remote, s); t.join()
            try { remote.close() } catch (_: Exception) {}
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
            activeSockets.remove(s)
            if (authenticated) activeConnections.decrementAndGet()
        }
    }

    private fun pipe(from: Socket, to: Socket) {
        try {
            val a = from.getInputStream(); val b = to.getOutputStream()
            val buf = ByteArray(8192)
            while (running) {
                val r = a.read(buf); if (r < 0) break
                b.write(buf, 0, r); b.flush()
                bytesTransferred.addAndGet(r.toLong())
            }
        } catch (_: Exception) {}
        try { to.shutdownOutput() } catch (_: Exception) {}
    }
}
