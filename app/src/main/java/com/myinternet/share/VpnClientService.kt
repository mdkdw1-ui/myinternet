package com.myinternet.share

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File

class VpnClientService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra("host") ?: return START_NOT_STICKY
        startTunnel(host)
        return START_STICKY
    }

    private fun startTunnel(host: String) {
        stopTunnel()
        val fd = Builder()
            .setSession("MyInternet")
            .setMtu(1500)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDisallowedApplication(packageName)
            .establish() ?: return
        pfd = fd

        val cfg = File(filesDir, "hev.yml")
        cfg.writeText(
            """
            tunnel:
              mtu: 1500
              ipv4: 198.18.0.1
            socks5:
              address: $host
              port: ${ServerService.PORT}
              udp: 'udp'
            misc:
              log-level: warn
            """.trimIndent() + "\n"
        )
        TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
    }

    private fun stopTunnel() {
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
