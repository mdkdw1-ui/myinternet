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
            val configFile = File(cacheDir, "hev_config.yml")
            
            // MTU 8500 (hev 2.14.4 표준값)
            val yaml = "tunnel:\n" +
                       "  mtu: 8500\n" +
                       "socks5:\n" +
                       "  port: $srvPort\n" +
                       "  address: $srvHost\n" +
                       "  udp: udp\n" +
                       "  username: $user\n" +
                       "  password: $pass\n"
            
            configFile.writeText(yaml)
            
            // 파일 내용을 다시 읽어서 로그에 남김 (실제로 어떻게 쓰였는지 확인)
            val actual = try { configFile.readText() } catch (e: Exception) { "읽기 실패: ${e.message}" }
            
            Log.i(TAG, "===== HEV CONFIG =====")
            Log.i(TAG, "path=${configFile.absolutePath}")
            Log.i(TAG, "size=${configFile.length()} bytes")
            Log.i(TAG, "actual_content_begin")
            actual.split("\n").forEach { line -> Log.i(TAG, "  | $line") }
            Log.i(TAG, "actual_content_end")
            Log.i(TAG, "tunFd=$tunFd")
            
            // JNI 호출 전 로그
            Log.i(TAG, ">>> TProxyStartService 호출 직전")
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            Log.i(TAG, "<<< TProxyStartService 호출 완료")
            
            running = true
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
