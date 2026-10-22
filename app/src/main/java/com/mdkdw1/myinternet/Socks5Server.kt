package com.mdkdw1.myinternet

import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.concurrent.thread

class Socks5Server(
    private val port: Int,
    private val username: String,
    private val password: String
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val udpSessions = ConcurrentHashMap<String, UdpRelaySession>()

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
        udpSessions.values.forEach { it.close() }
        udpSessions.clear()
    }

    private fun handle(s: Socket) {
        activeSockets.add(s); totalConnections.incrementAndGet()
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
            authenticated = true; activeConnections.incrementAndGet()

            if (i.read() != 0x05) return
            val cmd = i.read(); i.read()
            val atyp = i.read()
            val host: String; val dstPort: Int
            when (atyp) {
                0x01 -> { val b = ByteArray(4); i.read(b)
                    host = InetAddress.getByAddress(b).hostAddress ?: return
                    dstPort = (i.read() shl 8) or i.read() }
                0x03 -> { val l = i.read(); val b = ByteArray(l); i.read(b)
                    host = String(b); dstPort = (i.read() shl 8) or i.read() }
                0x04 -> { val b = ByteArray(16); i.read(b)
                    host = InetAddress.getByAddress(b).hostAddress ?: return
                    dstPort = (i.read() shl 8) or i.read() }
                else -> return
            }

            when (cmd) {
                0x01 -> handleConnect(s, o, host, dstPort)
                0x03 -> handleUdpAssociate(s, o)
                else -> o.write(byteArrayOf(0x05, 0x07, 0, 1, 0, 0, 0, 0, 0, 0))
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
            activeSockets.remove(s)
            if (authenticated) activeConnections.decrementAndGet()
        }
    }

    // ---- TCP CONNECT ----
    private fun handleConnect(client: Socket, out: java.io.OutputStream, host: String, port: Int) {
        val remote = try { Socket(host, port) } catch (_: Exception) {
            out.write(byteArrayOf(0x05, 0x05, 0, 1, 0, 0, 0, 0, 0, 0)); return
        }
        activeSockets.add(remote)
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
        val t = thread { pipe(client, remote) }
        pipe(remote, client); t.join()
        try { remote.close() } catch (_: Exception) {}
    }

    // ---- UDP ASSOCIATE ----
    private fun handleUdpAssociate(client: Socket, out: java.io.OutputStream) {
        val relay = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
        relay.soTimeout = 0
        val bndPort = relay.localPort
        // BND.ADDR = 0.0.0.0, BND.PORT = relay port
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0,
            (bndPort ushr 8).toByte(), bndPort.toByte())); out.flush()

        val session = UdpRelaySession(relay, client)
        val key = "${client.inetAddress.hostAddress}:${client.port}"
        udpSessions[key] = session
        session.start()

        // TCP 제어 연결이 유지되는 동안 대기; 끊기면 UDP 세션 종료
        try {
            val buf = ByteArray(1)
            while (running && client.getInputStream().read(buf) != -1) { /* idle */ }
        } catch (_: Exception) {}
        session.close()
        udpSessions.remove(key)
    }

    private class UdpRelaySession(
        private val relay: DatagramSocket,
        private val client: Socket
    ) {
        @Volatile private var running = true
        private val upstreamMap = ConcurrentHashMap<String, DatagramSocket>()
        private val clientAddr: InetAddress = client.inetAddress
        private var clientUdpPort: Int = 0

        fun start() {
            thread(name = "udp-relay-${relay.localPort}") { relayLoop() }
        }

        fun close() {
            running = false
            try { relay.close() } catch (_: Exception) {}
            upstreamMap.values.forEach { try { it.close() } catch (_: Exception) {} }
            upstreamMap.clear()
        }

        private fun relayLoop() {
            val buf = ByteArray(65535)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    relay.receive(p)
                    // 첫 패킷에서 클라이언트 UDP 포트 학습
                    if (clientUdpPort == 0) clientUdpPort = p.port
                    val data = p.data.copyOfRange(p.offset, p.offset + p.length)
                    val (dstHost, dstPort, payload) = parseUdpHeader(data) ?: continue

                    val up = upstreamMap.getOrPut("$dstHost:$dstPort") {
                        DatagramSocket().also { it.soTimeout = 0 }
                    }
                    up.send(DatagramPacket(payload, payload.size,
                        InetAddress.getByName(dstHost), dstPort))

                    thread {
                        try {
                            val rbuf = ByteArray(65535)
                            val rp = DatagramPacket(rbuf, rbuf.size)
                            up.receive(rp)
                            val respPayload = rbuf.copyOfRange(0, rp.length)
                            val header = buildUdpHeader(rp.address.hostAddress ?: dstHost, rp.port, respPayload.size)
                            val out = header + respPayload
                            relay.send(DatagramPacket(out, out.size, clientAddr, clientUdpPort))
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) { if (!running) break }
            }
        }

        private fun parseUdpHeader(data: ByteArray): Triple<String, Int, ByteArray>? {
            if (data.size < 10) return null
            val atyp = data[3].toInt() and 0xFF
            var off = 4
            val host: String
            when (atyp) {
                0x01 -> { if (data.size < 8) return null
                    host = "${data[4].toInt() and 0xFF}.${data[5].toInt() and 0xFF}." +
                           "${data[6].toInt() and 0xFF}.${data[7].toInt() and 0xFF}"
                    off = 8 }
                0x03 -> { val l = data[4].toInt() and 0xFF
                    host = String(data, 5, l); off = 5 + l }
                0x04 -> { if (data.size < 20) return null
                    val b = data.copyOfRange(4, 20)
                    host = InetAddress.getByAddress(b).hostAddress ?: return null
                    off = 20 }
                else -> return null
            }
            if (data.size < off + 2) return null
            val port = ((data[off].toInt() and 0xFF) shl 8) or (data[off+1].toInt() and 0xFF)
            val payload = data.copyOfRange(off + 2, data.size)
            return Triple(host, port, payload)
        }

        private fun buildUdpHeader(host: String, port: Int, len: Int): ByteArray {
            val ip = InetAddress.getByName(host).address
            val h = ByteArray(4 + 4 + 2)
            h[3] = 0x01
            System.arraycopy(ip, 0, h, 4, 4)
            h[8] = (port ushr 8).toByte(); h[9] = port.toByte()
            return h
        }
    }

    private fun pipe(from: Socket, to: Socket) {
        try {
            val a = from.getInputStream(); val b = to.getOutputStream()
            val buf = ByteArray(8192)
            while (running) { val r = a.read(buf); if (r < 0) break; b.write(buf, 0, r); b.flush()
                bytesTransferred.addAndGet(r.toLong()) }
        } catch (_: Exception) {}
        try { to.shutdownOutput() } catch (_: Exception) {}
    }
}
