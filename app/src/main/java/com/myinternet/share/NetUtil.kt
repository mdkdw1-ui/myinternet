package com.myinternet.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.io.File

object NetUtil {
    const val FALLBACK = "192.168.43.1"

    fun serverHostFromNetwork(ctx: Context, network: Network): String {
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(network) ?: return serverHost(ctx)
            for (r in lp.routes) {
                val g = r.gateway
                if (r.isDefaultRoute && g is Inet4Address && !g.isAnyLocalAddress) return g.hostAddress!!
            }
            for (la in lp.linkAddresses) {
                val a = la.address
                if (a is Inet4Address) {
                    val b = a.address
                    return "\${b[0].toInt() and 255}.\${b[1].toInt() and 255}.\${b[2].toInt() and 255}.1"
                }
            }
        } catch (_: Throwable) {}
        return serverHost(ctx)
    }

    fun serverHost(ctx: Context): String {
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(n) ?: continue
                for (r in lp.routes) {
                    val g = r.gateway
                    if (r.isDefaultRoute && g is Inet4Address && !g.isAnyLocalAddress) return g.hostAddress!!
                }
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address) {
                        val b = a.address
                        return "\${b[0].toInt() and 255}.\${b[1].toInt() and 255}.\${b[2].toInt() and 255}.1"
                    }
                }
            }
        } catch (_: Throwable) {}
        return FALLBACK
    }

    fun saveWgConf(ctx: Context, conf: String) { File(ctx.filesDir, "wg.conf").writeText(conf) }
    fun loadWgConf(ctx: Context): String {
        val f = File(ctx.filesDir, "wg.conf")
        return if (f.exists()) f.readText() else ""
    }
}
