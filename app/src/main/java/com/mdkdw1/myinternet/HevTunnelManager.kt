package com.mdkdw1.myinternet

import java.io.File
import java.io.FileOutputStream

object HevTunnelManager {
    @Volatile private var running = false

    fun start(
        tunFd: Int,
        srvHost: String,
        srvPort: Int,
        user: String,
        pass: String,
        cacheDir: File
    ): Boolean {
        if (running) return true
        return try {
            val configFile = File(cacheDir, "hev_config.yml")
            // MTU 1500: 게임 UDP 패킷 손실 방지 (Hotspot-Bypass-VPN도 게임용으로 튜닝함)
            val yaml = """
                tunnel:
                  mtu: 1500
                  ipv4: 198.18.0.1
                socks5:
                  port: $srvPort
                  address: $srvHost
                  udp: udp
                  username: $user
                  password: $pass
                misc:
                  task-stack-size: 86016
                  tcp-buffer-size: 65536
                  connect-timeout: 10000
                  tcp-read-write-timeout: 300000
                  udp-read-write-timeout: 60000
                  log-level: warn
            """.trimIndent()
            FileOutputStream(configFile).use { it.write(yaml.toByteArray()) }

            val result = hev.htproxy.TProxyService.TProxyStartService(
                configFile.absolutePath,
                tunFd
            )
            if (result) {
                running = true
                configFile.delete()
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stop() {
        if (!running) return
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (_: Exception) {}
        running = false
    }

    fun isRunning(): Boolean = running
}
