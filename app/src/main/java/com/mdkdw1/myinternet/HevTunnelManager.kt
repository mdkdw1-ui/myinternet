package com.mdkdw1.myinternet

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object HevTunnelManager {
    @Volatile private var running = false
    @Volatile var lastError: String? = null
    private var process: Process? = null

    fun start(context: Context, tunFd: Int, srvHost: String, srvPort: Int, user: String, pass: String, cacheDir: File): Boolean {
        lastError = null
        if (running) return true

        val binaryFile = File(context.filesDir, "hev-socks5-tunnel")
        if (!binaryFile.exists()) {
            try {
                context.assets.open("hev-socks5-tunnel").use { input ->
                    FileOutputStream(binaryFile).use { output -> input.copyTo(output) }
                }
                // 실행 권한 강제 부여
                binaryFile.setExecutable(true, false)
                val chmod = Runtime.getRuntime().exec(arrayOf("chmod", "755", binaryFile.absolutePath))
                chmod.waitFor()
            } catch (e: Exception) {
                val assetList = try { context.assets.list("")?.joinToString(", ") ?: "비어있음" } catch (_: Exception) { "확인불가" }
                lastError = "바이너리 추출 실패: ${e.javaClass.simpleName} - ${e.message}\n\n[assets 내용]\n$assetList"
                return false
            }
        }

        // 파일 검증
        if (!binaryFile.canExecute()) {
            lastError = "파일은 있지만 실행 권한 없음. (파일 크기: ${binaryFile.length()} bytes)"
            return false
        }

        return try {
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

            val cmd = arrayOf(binaryFile.absolutePath, "-c", configFile.absolutePath)
            process = Runtime.getRuntime().exec(cmd)
            running = true
            true
        } catch (e: Exception) {
            lastError = "실행 오류: ${e.javaClass.simpleName} - ${e.message}"
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
