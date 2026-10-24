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
            Log.i(TAG, "lib loaded")
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
            // 공식 conf/main.yml 스키마 그대로
                        val content = "tunnel:
" +
                          "  mtu: 1400
" +
                          "  ipv4: 198.18.0.1
" +
                          "socks5:
" +
                          "  port: $srvPort
" +
                          "  address: $srvHost
" +
                          "  udp: 'udp'
" +
                          "  username: $user
" +
                          "  password: $pass
" +
                          "misc:
" +
                          "  log-level: info
" +
                          "  udp-recv-buffer-size: 524288
" +
                          "  task-stack-size: 86016\n"
            val bytes = content.toByteArray(Charsets.UTF_8)

            Log.i(TAG, "=========== HEV CONFIG WRITE START ===========")
            Log.i(TAG, "content_length=${bytes.size} bytes")
            Log.i(TAG, "content_string=<$content>")

            FileOutputStream(configFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            Log.i(TAG, "written_size=${configFile.length()} bytes")
            Log.i(TAG, ">>> TProxyStartService CALL (fd=$tunFd, official schema)")

            val watchdog = Thread {
                try {
                    Thread.sleep(8000)
                    Log.e(TAG, "🚨 TProxyStartService 8초 넘게 반환 안 됨")
                } catch (_: InterruptedException) {}
            }
            watchdog.isDaemon = true
            watchdog.start()

            Log.i(TAG, ">>> TProxyStartService CALLING ...")
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            Log.i(TAG, "<<< TProxyStartService RETURNED")
            watchdog.interrupt()
            running = true
            return true
        } catch (e: Throwable) {
            lastError = "Hev 시작 오류: ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "EXCEPTION", e)
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
