package com.mdkdw1.myinternet

import android.util.Log
import java.io.File

object HevTunnelManager {
    private const val TAG = "HevTunnel"
    const val MTU = 1350 // VPN과 hev 설정 통일

    @Volatile private var running = false
    @Volatile var lastError: String? = null
    @Volatile private var libLoaded = false

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            libLoaded = true
            Log.i(TAG, "libhev-socks5-tunnel loaded successfully")
        } catch (e: Throwable) {
            lastError = "라이브러리 로드 실패: ${e.message}"
            Log.e(TAG, "loadLibrary failed", e)
        }
    }

    fun isLibLoaded(): Boolean = libLoaded

    fun start(tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
        if (!libLoaded) { lastError = "라이브러리 미탑재"; return false }
        if (running) return true
        lastError = null
        
        return try {
            val configFile = File(cacheDir, "hev_config.yml")
            // 메일에서 지적된 올바른 스키마 적용
            val yaml = """
                tunnel:
                  mtu: $MTU
                  ipv4: 198.18.0.1
                socks5:
                  port: $srvPort
                  address: $srvHost
                  udp: 'udp'
                  username: $user
                  password: $pass
                misc:
                  log-level: debug
                  task-stack-size: 86016
                  tcp-buffer-size: 131072
                  udp-buffer-size: 131072
            """.trimIndent()
            configFile.writeText(yaml)
            
            Log.i(TAG, "TProxyStartService 시작 (cfg=${configFile.absolutePath}, fd=$tunFd, host=$srvHost:$srvPort)")
            
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            
            running = true
            Log.i(TAG, "TProxyStartService 정상 호출 완료")
            true
        } catch (e: Throwable) {
            lastError = "JNI 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "TProxyStartService 중 에러 발생", e)
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
