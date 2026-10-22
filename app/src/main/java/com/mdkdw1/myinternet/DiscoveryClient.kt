package com.mdkdw1.myinternet

import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class DiscoveryClient {
    companion object { const val DISCOVERY_PORT = 25555 }

    data class Server(val host: String, val port: Int, val user: String, val pass: String)

    fun discover(timeoutMs: Long = 5000, callback: (List<Server>) -> Unit) {
        thread(name = "discovery-client") {
            val found = LinkedHashMap<String, Server>()
            var s: DatagramSocket? = null
            try {
                s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    soTimeout = 800
                }
                val buf = ByteArray(1024)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length, Charsets.UTF_8)
                        if (text.startsWith("MYINT1|")) {
                            val parts = text.split("|")
                            if (parts.size >= 4) {
                                val host = p.address?.hostAddress ?: continue
                                val port = parts[1].toIntOrNull() ?: continue
                                if (port > 0) found[host] = Server(host, port, parts[2], parts[3])
                            }
                        }
                    } catch (_: SocketTimeoutException) {}
                }
            } catch (_: Exception) {} finally {
                try { s?.close() } catch (_: Exception) {}
            }
            Handler(Looper.getMainLooper()).post { callback(found.values.toList()) }
        }
    }
}
