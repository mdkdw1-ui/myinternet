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
            // 1. assets에서 바이너리 추출
            val binaryFile = File(context.filesDir, "hev-socks5-tunnel")
            if (!binaryFile.exists()) {
                context.assets.open("hev-socks5-tunnel").use { input ->
                    FileOutputStream(binaryFile).use { output -> input.copyTo(output) }
                }
                binaryFile.setExecutable(true)
            }

            // 2. 설정 파일 생성
            val configFile = File(cacheDir, "hev_config.yml")
            val yaml = """
                tunnel:
                  mtu: 1500
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

            // 3. 명령어 구성: hev-socks5-tunnel -c config.yml (TUN FD는 별도 처리 필요)
            // 주의: hev-socks5-tunnel은 TUN FD를 인자로 받지 않고, 
            // VPN 서비스에서 제공한 FD를 표준 입출력으로 넘겨줘야 합니다.
            // 여기서는 실행 파일만 준비하고, 실제 연동은 나중에 수정이 필요할 수 있습니다.
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
