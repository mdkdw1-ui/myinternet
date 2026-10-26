package com.myinternet.share

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address

object NetUtil {
    const val FALLBACK = "192.168.43.1"

    // 현재 붙어 있는 Wi-Fi(핫스팟)의 게이트웨이 = 서버 폰 IP
    fun serverHost(ctx: Context): String {
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(n) ?: continue

                // 1순위: 기본 라우트의 게이트웨이
                for (r in lp.routes) {
                    val g = r.gateway
                    if (r.isDefaultRoute && g is Inet4Address && !g.isAnyLocalAddress) {
                        AppLog.i("net", "서버 IP(게이트웨이) = ${g.hostAddress} iface=${lp.interfaceName}")
                        return g.hostAddress!!
                    }
                }
                // 2순위: 내 IP 대역의 .1
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
        } catch (_: Throwable) {
        }
        AppLog.i("net", "탐지 실패, 기본값 $FALLBACK 사용")
        return FALLBACK
    }
}
