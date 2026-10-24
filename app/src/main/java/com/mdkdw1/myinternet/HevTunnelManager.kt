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
            val content = "tunnel:\n  mtu: 8500\nsocks5:\n  port: $srvPort\n  address: $srvHost\n  udp: udp\n  username: $user\n  password: $pass\n"
            val bytes = content.toByteArray(Charsets.UTF_8)

            Log.i(TAG, "=========== HEV CONFIG WRITE START ===========")
            Log.i(TAG, "content_length=${bytes.size} bytes")
            Log.i(TAG, "content_string=<$content>")

            FileOutputStream(configFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            Log.i(TAG, "written_size=${configFile.length()} bytes")

            val written = configFile.readBytes()
            val sb = StringBuilder()
            for (b in written) {
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E) sb.append(c.toChar())
                else if (c == 0x0A) sb.append("\\n")
                else sb.append("\\x").append(String.format("%02x", c))
            }
            Log.i(TAG, "HEXDUMP=<${sb.toString()}>")
            Log.i(TAG, "=========== HEV CONFIG WRITE END ===========")

            Log.i(TAG, ">>> TProxyStartService CALL (fd=$tunFd, path=${configFile.absolutePath})")
            hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, tunFd)
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
