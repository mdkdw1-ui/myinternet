package com.mdkdw1.myinternet

import java.io.File
import java.io.FileOutputStream

object HevTunnelManager {
    @Volatile private var running = false
    @Volatile private var loadError: String? = null

    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray?

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (e: Throwable) {
            loadError = "라이브러리 로드 실패: ${e.javaClass.simpleName} - ${e.message}"
            e.printStackTrace()
        }
    }

    fun getLoadError(): String? = loadError

    fun start(tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
        if (loadError != null) return false
        if (running) return true
        return try {
            val configFile = File(cacheDir, "hev_config.yml")
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
                  log-level: warn
            """.trimIndent()
            FileOutputStream(configFile).use { it.write(yaml.toByteArray()) }

            val result = TProxyStartService(configFile.absolutePath, tunFd)
            if (result) {
                running = true
                configFile.delete()
            }
            result
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    fun stop() {
        if (!running) return
        try { TProxyStopService() } catch (_: Exception) {}
        running = false
    }

    fun isRunning(): Boolean = running
}
