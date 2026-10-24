package com.mdkdw1.myinternet

import android.util.Log
import java.io.File

object HevTunnelManager {
    private const val TAG = "HevTunnel"
    @Volatile private var running = false
    @Volatile var lastError: String? = null
    @Volatile private var libLoaded = false

    init {
        try {
            System.loadLibrary("hev-socks5-tunnel")
            libLoaded = true
            Log.i(TAG, "libhev-socks5-tunnel loaded")
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
            
            // 💡 hev-config.c 소스에서 확인한 정확한 스키마
            // - tunnel.mtu: 필수
            // - tunnel.ipv4: TUN 인터페이스 IP (필수, 이게 없으면 sendto 실패)
            // - socks5.port / socks5.address: 필수
            // - socks5.udp: "udp" 문자열
            // - socks5.username / socks5.password: 둘 다 있거나 둘 다 없어야 함
            val yaml = "tunnel:\n" +
                       "  mtu: 1350\n" +
                       "  ipv4: 198.18.0.1\n" +
                       "socks5:\n" +
                       "  port: $srvPort\n" +
                       "  address: $srvHost\n" +
                       "  udp: udp\n" +
                       "  username: $user\n" +
                       "  password: $pass\n"
            
            configFile.writeText(yaml)
            Log.i(TAG, "TProxyStartService 시작 (host=$srvHost:$srvPort, fd=$tunFd)")
            Log.d(TAG, "YAML 설정:\n$yaml")
            
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            
            running = true
            Log.i(TAG, "TProxyStartService 호출 완료")
            true
        } catch (e: Throwable) {
            lastError = "JNI 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "TProxyStartService 에러 발생", e)
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
