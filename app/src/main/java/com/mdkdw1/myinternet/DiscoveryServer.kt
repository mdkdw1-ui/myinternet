package com.mdkdw1.myinternet

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

class DiscoveryServer(
    private val port: Int,
    private val user: String,
    private val pass: String,
) {
    companion object { const val DISCOVERY_PORT = 25555 }

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "discovery-server") {
            try {
                val s = DatagramSocket().apply { broadcast = true }
                socket = s
                val msg = "MYINT1|$port|$user|$pass".toByteArray(Charsets.UTF_8)
                while (running) {
                    try {
                        for (addr in broadcastAddresses()) {
                            try {
                                s.send(DatagramPacket(msg, msg.size, addr, DISCOVERY_PORT))
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    Thread.sleep(2000)
                }
                try { s.close() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val out = mutableSetOf<InetAddress>()
        try {
            NetworkInterface.getNetworkInterfaces().toList().forEach { iface ->
                if (iface.isUp && !iface.isLoopback) {
                    iface.interfaceAddresses.forEach { ia ->
                        ia.broadcast?.let { out.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        if (out.isEmpty()) {
            try { out.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        }
        return out
    }
}
