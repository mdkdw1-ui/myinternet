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
        pool.execute {
            while (running) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (e: Exception) {
                    if (!running) break
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
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun reply(o: OutputStream, code: Int) {
        o.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        o.flush()
    }

    private fun tcpConnect(c: Socket, i: InputStream, o: OutputStream, host: String, dport: Int) {
        val r = Socket()
        try {
            r.connect(InetSocketAddress(host, dport), 10000)
            r.tcpNoDelay = true
        } catch (e: Exception) {
            try { r.close() } catch (_: Exception) {}
            reply(o, 5)
            return
        }
        reply(o, 0)
        val t = thread(isDaemon = true) {
            try { r.getInputStream().copyTo(o) } catch (_: Exception) {}
            try { c.shutdownOutput() } catch (_: Exception) {}
        }
        try { i.copyTo(r.getOutputStream()) } catch (_: Exception) {}
        try { r.shutdownOutput() } catch (_: Exception) {}
        t.join(3000)
        try { r.close() } catch (_: Exception) {}
    }

    private fun udpAssociate(c: Socket, i: InputStream, o: OutputStream) {
        val us = DatagramSocket(0)
        val bnd = c.localAddress.address
        val rep = ByteArrayOutputStream()
        rep.write(byteArrayOf(5, 0, 0, if (bnd.size == 4) 1 else 4))
        rep.write(bnd)
        rep.write(us.localPort ushr 8)
        rep.write(us.localPort and 255)
        o.write(rep.toByteArray())
        o.flush()

        val clientAddr: InetAddress = c.inetAddress
        thread(isDaemon = true) {
            var clientPort = -1
            val buf = ByteArray(65535)
            val p = DatagramPacket(buf, buf.size)
            try {
                while (true) {
                    p.length = buf.size
                    us.receive(p)
                    val len = p.length
                    if (p.address == clientAddr && (clientPort == -1 || p.port == clientPort)) {
                        clientPort = p.port
                        if (len < 10 || buf[2].toInt() != 0) continue
                        var idx = 3
                        val at = buf[idx++].toInt()
                        val dst: InetAddress = when (at) {
                            1 -> { val a = buf.copyOfRange(idx, idx + 4); idx += 4; InetAddress.getByAddress(a) }
                            4 -> { val a = buf.copyOfRange(idx, idx + 16); idx += 16; InetAddress.getByAddress(a) }
                            3 -> { val l = buf[idx++].toInt() and 255; val h = String(buf, idx, l); idx += l; InetAddress.getByName(h) }
                            else -> continue
                        }
                        val dp = ((buf[idx].toInt() and 255) shl 8) or (buf[idx + 1].toInt() and 255)
                        idx += 2
                        val payload = buf.copyOfRange(idx, len)
                        us.send(DatagramPacket(payload, payload.size, dst, dp))
                    } else if (clientPort != -1) {
                        val a = p.address.address
                        val out = ByteArrayOutputStream()
                        out.write(byteArrayOf(0, 0, 0, if (a.size == 4) 1 else 4))
                        out.write(a)
                        out.write(p.port ushr 8)
                        out.write(p.port and 255)
                        out.write(buf, 0, len)
                        val data = out.toByteArray()
                        us.send(DatagramPacket(data, data.size, clientAddr, clientPort))
                    }
                }
            } catch (_: Exception) {
            }
        }

        try { while (i.read() >= 0) { } } catch (_: Exception) {}
        us.close()
    }
}
