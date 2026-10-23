package com.mdkdw1.myinternet

import java.io.File
import java.io.FileOutputStream

object HevTunnelManager {
    @Volatile private var running = false

    // C 라이브러리의 JNI 함수 선언 (패키지명과 클래스명이 일치해야 함)
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray?

    init {
        // 빌드된 .so 라이브러리 로드
        System.loadLibrary("hev-socks5-tunnel")
    }

    fun start(tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
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

            // JNI 호출: 설정 파일 경로와 TUN 파일 디스크립터를 넘김
            val result = TProxyStartService(configFile.absolutePath, tunFd)
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
        try { TProxyStopService() } catch (_: Exception) {}
        running = false
    }

    fun isRunning(): Boolean = running
}
