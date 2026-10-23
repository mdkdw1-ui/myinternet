package com.mdkdw1.myinternet

import java.io.File
import java.io.FileOutputStream

object HevTunnelManager {
    @Volatile private var running = false
    @Volatile var lastError: String? = null

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
        } catch (e: Throwable) {
            lastError = "라이브러리 로드 실패: ${e.javaClass.simpleName} - ${e.message}"
            e.printStackTrace()
        }
    }

    fun start(tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
        if (lastError != null && !running) return false
        if (running) return true
        return try {
            val configFile = File(cacheDir, "hev_config.yml")
            val yaml = """
                tunnel:
                  mtu: 1350
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

            // void 반환: 예외가 없으면 성공
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            running = true
            configFile.delete()
            true
        } catch (e: Throwable) {
            lastError = "JNI 호출 실패: ${e.javaClass.simpleName} - ${e.message}"
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
