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

            // C 라이브러리가 기대하는 클래스의 JNI 함수 호출
            val result = hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            if (result) {
                running = true
                configFile.delete()
            } else {
                lastError = "TProxyStartService false 반환"
            }
            result
        } catch (e: Throwable) {
            lastError = "JNI 호출 실패: ${e.javaClass.simpleName} - ${e.message}"
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
