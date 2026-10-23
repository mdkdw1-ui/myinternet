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
            // 1. APK 내부의 lib 폴더에서 바이너리 위치 찾기
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val libFile = File(nativeLibDir, "libhev-socks5-tunnel.so")
            
            if (!libFile.exists()) {
                android.util.Log.e("HevTunnel", "Library not found in $nativeLibDir")
                return false
            }

            // 2. 설정 파일 생성
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

            // 3. 실행 파일 실행 (TUN FD는 현재 넘기지 않음. 단순 실행 테스트)
            // 실제로는 --tun-fd 옵션을 넘겨야 하지만, 일단 실행 가능 여부만 확인
            val cmd = arrayOf(libFile.absolutePath, "-c", configFile.absolutePath)
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
