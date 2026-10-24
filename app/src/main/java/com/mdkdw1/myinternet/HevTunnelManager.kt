package com.mdkdw1.myinternet

import android.util.Log
import java.io.File
import java.io.FileOutputStream

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
            
            // 줄바꿈을 배열로 명확하게 명시
            val lines = arrayOf(
                "tunnel:",
                "  mtu: 8500",
                "socks5:",
                "  port: $srvPort",
                "  address: $srvHost",
                "  udp: udp",
                "  username: $user",
                "  password: $pass"
            )
            
            // 파일에 직접 바이트 단위로 쓰기
            FileOutputStream(configFile).use { fos ->
                for (line in lines) {
                    fos.write(line.toByteArray(Charsets.UTF_8))
                    fos.write('\n'.code)
                }
                fos.flush()
            }
            
            // 파일 내용 다시 읽어서 로그 덤프
            val actual = configFile.readText()
            Log.i(TAG, "===== HEV CONFIG START =====")
            Log.i(TAG, "path=${configFile.absolutePath} size=${configFile.length()}B")
            for (l in actual.split("\n")) {
                Log.i(TAG, "  | $l")
            }
            Log.i(TAG, "===== HEV CONFIG END =====")
            Log.i(TAG, "tunFd=$tunFd  →  TProxyStartService 직전")
            
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            Log.i(TAG, "TProxyStartService 정상 리턴")
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
