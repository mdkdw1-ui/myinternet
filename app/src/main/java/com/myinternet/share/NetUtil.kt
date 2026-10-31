package com.myinternet.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address

object NetUtil {
    const val FALLBACK = "192.168.43.1"

    // ★ 특정 Network(스펙파이어로 막 연결한 그 네트워크)에서 게이트웨이를 얻는다
    fun serverHostFromNetwork(ctx: Context, network: Network): String {
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(network) ?: return serverHost(ctx)

            for (r in lp.routes) {
                val g = r.gateway
                if (r.isDefaultRoute && g is Inet4Address && !g.isAnyLocalAddress) {
                    AppLog.i("net", "특정 네트워크 게이트웨이 = ${g.hostAddress} iface=${lp.interfaceName}")
                    return g.hostAddress!!
                }
            }
            for (la in lp.linkAddresses) {
                val a = la.address
                if (a is Inet4Address) {
                    val b = a.address
                    val guess = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.1"
                    AppLog.i("net", "특정 네트워크 추정 = $guess")
                    return guess
                }
            }
        } catch (t: Throwable) {
            AppLog.e("net", "특정 네트워크 IP 탐지 실패", t)
        }
        return serverHost(ctx)
    }

    fun saveWgConf(ctx: Context, conf: String) {
        try { java.io.File(ctx.filesDir, "wg.conf").writeText(conf) } catch (_: Throwable) {}
    }

    fun loadWgConf(ctx: Context): String = try {
        val f = java.io.File(ctx.filesDir, "wg.conf")
        if (f.exists()) f.readText() else ""
    } catch (_: Throwable) { "" }

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
                    if (r.isDefaultRoute && g is Inet4Address && !g.isAnyLocalAddress) {
                        AppLog.i("net", "서버 IP(게이트웨이) = ${g.hostAddress} iface=${lp.interfaceName}")
                        return g.hostAddress!!
                    }
                }
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address) {
                        val b = a.address
                        val guess = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.1"
                        AppLog.i("net", "게이트웨이 없음, 내 IP ${a.hostAddress} 기준 추정 = $guess")
                        return guess
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.e("net", "서버 IP 탐지 실패", t)
        }
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val g = wm.dhcpInfo.gateway
            if (g != 0) return "${g and 255}.${(g shr 8) and 255}.${(g shr 16) and 255}.${(g shr 24) and 255}"
        } catch (_: Throwable) {}
        AppLog.i("net", "탐지 실패, 기본값 $FALLBACK 사용")
        return FALLBACK
    }
}
