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
        
        try {
            // 1. YAML 문자열 준비
            val yaml = "tunnel:\n" +
                       "  mtu: 1350\n" +
                       "socks5:\n" +
                       "  port: $srvPort\n" +
                       "  address: $srvHost\n" +
                       "  udp: udp\n" +
                       "  username: $user\n" +
                       "  password: $pass\n"
            
            Log.d(TAG, "==== YAML 준비 ====\n$yaml")
            
            // 2. 파일 쓰기 (실패 시 에러 기록)
            val configFile = File(cacheDir, "hev_config.yml")
            if (!cacheDir.exists()) {
                if (!cacheDir.mkdirs()) {
                    lastError = "캐시 디렉토리 생성 실패"
                    Log.e(TAG, lastError!!)
                    return false
                }
            }
            configFile.writeText(yaml)
            Log.i(TAG, "설정 파일 쓰기 완료: ${configFile.absolutePath} (size=${configFile.length()})")
            
            // 3. JNI 호출
            Log.i(TAG, "TProxyStartService 호출 (fd=$tunFd)")
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            
            running = true
            Log.i(TAG, "TProxyStartService 호출 완료, running=true")
            return true
        } catch (e: Throwable) {
            lastError = "Hev 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "예외 발생!", e)
            return false
        }
    }

    fun stop() {
        if (!running) return
        try { hev.htproxy.TProxyService.TProxyStopService() } catch (_: Exception) {}
        running = false
    }

    fun isRunning(): Boolean = running
}
