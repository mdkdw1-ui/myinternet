package com.mdkdw1.myinternet

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object HevTunnelManager {
    @Volatile private var running = false
    private var process: Process? = null

    fun start(context: Context, tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
        if (running) return true
        return try {
            // assets에서 바이너리 추출 (없다면 실패)
            val binaryFile = File(context.filesDir, "hev-socks5-tunnel")
            if (!binaryFile.exists()) {
                try {
                    context.assets.open("hev-socks5-tunnel").use { input ->
                        FileOutputStream(binaryFile).use { output -> input.copyTo(output) }
                    }
                    binaryFile.setExecutable(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                    return false
                }
            }

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

            // TUN FD를 인자로 넘겨 실행 (hev-socks5-tunnel은 --tun-fd 옵션 지원 안함, 우회 필요)
            // 현재는 단순 실행만 테스트
            val cmd = arrayOf(binaryFile.absolutePath, "-c", configFile.absolutePath)
            process = Runtime.getRuntime().exec(cmd)
            running = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stop() {
        if (!running) return
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        running = false
    }

    fun isRunning(): Boolean = running
}
