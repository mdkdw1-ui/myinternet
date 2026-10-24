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
            val content = "tunnel:\n" +
                          "  mtu: 1500\n" +
                          "  ipv4: 198.18.0.1\n" +
                          "  ipv6: 'fc00::1'\n" +
                          "socks5:\n" +
                          "  port: $srvPort\n" +
                          "  address: $srvHost\n" +
                          "  udp: 'udp'\n" +
                          "  username: $user\n" +
                          "  password: $pass\n" +
                          "mapdns:\n" +
                          "  address: 198.18.0.2\n" +
                          "  port: 53\n" +
                          "  network: 240.0.0.0\n" +
                          "  netmask: 240.0.0.0\n" +
                          "  cache-size: 10000\n" +
                          "misc:\n" +
                          "  log-level: debug\n"
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

            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
            watchdog.interrupt()

            Log.i(TAG, "<<< TProxyStartService RETURNED")
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
