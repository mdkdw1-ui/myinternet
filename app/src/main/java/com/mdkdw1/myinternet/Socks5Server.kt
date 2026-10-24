package com.mdkdw1.myinternet

import java.io.DataInputStream
import java.io.OutputStream
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

    private fun getHotspotIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback) {
                    val name = intf.name
                    if (name.startsWith("ap") || name.startsWith("swlan") || name.contains("wlan1") || name.contains("softap")) {
                        for (addr in intf.inetAddresses.toList()) {
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val ip = addr.hostAddress
                                if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10."))) return ip
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "socks5-accept") {
            try {
                val hotspotIp = getHotspotIp()
                val bindAddr = if (hotspotIp != null) InetAddress.getByName(hotspotIp) else InetAddress.getByName("0.0.0.0")
                android.util.Log.i("Socks5Server", "Starting SOCKS5 server on $bindAddr:$port")

                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddr, port), 50)
                }
                while (running) {
                    val c = try { serverSocket?.accept() ?: break } catch (_: Exception) { break }
                    thread { handle(c) }
                }
            } catch (e: Exception) {
                android.util.Log.e("Socks5Server", "Server error, falling back to 0.0.0.0", e)
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", port), 50)
                    }
                    while (running) {
                        val c = try { serverSocket?.accept() ?: break } catch (_: Exception) { break }
                        thread { handle(c) }
                    }
                } catch (_: Exception) {}
            }
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
        android.util.Log.i("Socks5Server", "클라이언트 접속: ${s.inetAddress.hostAddress}:${s.port}")
        var authenticated = false
        try {
            s.tcpNoDelay = true
            s.soTimeout = 15000
            val i = DataInputStream(s.getInputStream()); val o = s.getOutputStream()

            if (i.readUnsignedByte() != 0x05) return
            val n = i.readUnsignedByte(); val m = ByteArray(n); i.readFully(m)
            if (!m.contains(0x02.toByte())) { o.write(byteArrayOf(0x05, 0xFF.toByte())); return }
            o.write(byteArrayOf(0x05, 0x02)); o.flush()

            if (i.readUnsignedByte() != 0x01) return
            val un = ByteArray(i.readUnsignedByte()); i.readFully(un)
            val pw = ByteArray(i.readUnsignedByte()); i.readFully(pw)
            if (String(un) != username || String(pw) != password) {
                o.write(byteArrayOf(0x01, 0x01)); return
            }
            o.write(byteArrayOf(0x01, 0x00)); o.flush()
            authenticated = true; activeConnections.incrementAndGet()

            if (i.readUnsignedByte() != 0x05) return
            val cmd = i.readUnsignedByte(); i.readUnsignedByte()
            val host: String
            when (i.readUnsignedByte()) {
                0x01 -> { val b = ByteArray(4); i.readFully(b); host = InetAddress.getByAddress(b).hostAddress ?: return }
                0x03 -> { val b = ByteArray(i.readUnsignedByte()); i.readFully(b); host = String(b) }
                0x04 -> { val b = ByteArray(16); i.readFully(b); host = InetAddress.getByAddress(b).hostAddress ?: return }
                else -> return
            }
            val dstPort = i.readUnsignedShort()
            s.soTimeout = 0

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

    private fun handleConnect(client: Socket, out: OutputStream, host: String, port: Int) {
        android.util.Log.i("Socks5Server", "CONNECT 요청: $host:$port")
        val remote = Socket()
        try {
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(host, port), 10000)
            android.util.Log.i("Socks5Server", "CONNECT 성공: $host:$port")
        } catch (_: Exception) {
            try { remote.close() } catch (_: Exception) {}
            out.write(byteArrayOf(0x05, 0x05, 0, 1, 0, 0, 0, 0, 0, 0)); return
        }
        activeSockets.add(remote)
        try {
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()
            val t = thread { pipe(client, remote) }
            pipe(remote, client); t.join()
        } finally {
            activeSockets.remove(remote)
            try { remote.close() } catch (_: Exception) {}
        }
    }

    private fun handleUdpAssociate(client: Socket, out: OutputStream) {
        android.util.Log.i("Socks5Server", "UDP 요청 수신 (게임 데이터용) from ${client.inetAddress.hostAddress}:${client.port}, local=${client.localAddress.hostAddress}:${client.localPort}")
        val relay = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
        relay.soTimeout = 0
        val bndPort = relay.localPort
        val bnd = client.localAddress.address.takeIf { it.size == 4 } ?: ByteArray(4)
        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01) + bnd + byteArrayOf((bndPort ushr 8).toByte(), bndPort.toByte())); out.flush()

        val session = UdpRelaySession(relay, client)
        val key = "${client.inetAddress.hostAddress}:${client.port}"
        udpSessions[key] = session
        session.start()

        try { val buf = ByteArray(1); while (running && client.getInputStream().read(buf) != -1) {} } catch (_: Exception) {}
        session.close()
        udpSessions.remove(key)
    }

    private class UdpRelaySession(
        private val relay: DatagramSocket,
        private val client: Socket
    ) {
        @Volatile private var running = true
        private val clientAddr: InetAddress = client.inetAddress
        @Volatile private var clientUdpPort = 0
        private val upstream = DatagramSocket()
        private val resolved = ConcurrentHashMap<String, InetAddress>()

        init { try { upstream.receiveBufferSize = 262144; upstream.sendBufferSize = 262144; relay.receiveBufferSize = 262144; relay.sendBufferSize = 262144 } catch (_: Exception) {} }

        fun start() { thread(name = "udp-client-${relay.localPort}") { clientLoop() }; thread(name = "udp-upstream-${relay.localPort}") { upstreamLoop() } }
        fun close() { running = false; try { relay.close() } catch (_: Exception) {}; try { upstream.close() } catch (_: Exception) {} }

        private fun clientLoop() {
            val buf = ByteArray(65535); val p = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    p.length = buf.size; relay.receive(p)
                    if (p.address != clientAddr) continue
                    clientUdpPort = p.port
                    val (dst, dstPort, off) = parse(buf, p.length) ?: continue
                    upstream.send(DatagramPacket(buf, off, p.length - off, dst, dstPort))
                } catch (_: Exception) { if (!running) break }
            }
        }

        private fun upstreamLoop() {
            val buf = ByteArray(65535); val p = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    p.length = buf.size; upstream.receive(p)
                    val port = clientUdpPort; if (port == 0) continue
                    val header = buildUdpHeader(p.address, p.port)
                    val out = ByteArray(header.size + p.length)
                    System.arraycopy(header, 0, out, 0, header.size); System.arraycopy(buf, 0, out, header.size, p.length)
                    relay.send(DatagramPacket(out, out.size, clientAddr, port))
                } catch (_: Exception) { if (!running) break }
            }
        }

        private fun parse(d: ByteArray, len: Int): Triple<InetAddress, Int, Int>? {
            if (len < 10) return null
            val addr: InetAddress; val off: Int
            when (d[3].toInt() and 0xFF) {
                0x01 -> { addr = InetAddress.getByAddress(d.copyOfRange(4, 8)); off = 8 }
                0x03 -> { val l = d[4].toInt() and 0xFF; if (len < 5 + l + 2) return null; addr = resolved.getOrPut(String(d, 5, l)) { InetAddress.getByName(String(d, 5, l)) }; off = 5 + l }
                0x04 -> { if (len < 22) return null; addr = InetAddress.getByAddress(d.copyOfRange(4, 20)); off = 20 }
                else -> return null
            }
            if (len < off + 2) return null
            val port = ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
            return Triple(addr, port, off + 2)
        }

        private fun buildUdpHeader(addr: InetAddress, port: Int): ByteArray {
            val ip = addr.address; val h = ByteArray(4 + ip.size + 2)
            h[3] = (if (ip.size == 4) 0x01 else 0x04).toByte(); System.arraycopy(ip, 0, h, 4, ip.size)
            h[4 + ip.size] = (port ushr 8).toByte(); h[5 + ip.size] = port.toByte(); return h
        }
    }

    private fun pipe(from: Socket, to: Socket) {
        try {
            val a = from.getInputStream(); val b = to.getOutputStream(); val buf = ByteArray(16384)
            while (running) { val r = a.read(buf); if (r < 0) break; b.write(buf, 0, r); b.flush(); bytesTransferred.addAndGet(r.toLong()) }
        } catch (_: Exception) {}
        try { to.shutdownOutput() } catch (_: Exception) {}
    }
}
