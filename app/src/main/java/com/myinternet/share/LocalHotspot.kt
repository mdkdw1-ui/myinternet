package com.myinternet.share

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

object LocalHotspot {
    @Volatile var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile var ssid = ""
    @Volatile var pass = ""
    @Volatile var sec = "WPA"
    @Volatile var state = "중지"

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(ctx: Context, onDone: (Boolean) -> Unit) {
        if (reservation != null) {
            onDone(true)
            return
        }
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        state = "시작 중"
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val c = r.softApConfiguration
                            ssid = c.ssid ?: ""
                            pass = c.passphrase ?: ""
                            sec = if (c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE) "SAE" else "WPA"
                        } else {
                            val c = r.wifiConfiguration
                            ssid = c?.SSID ?: ""
                            pass = c?.preSharedKey ?: ""
                        }
                    } catch (t: Throwable) {
                        AppLog.e("hotspot", "설정 읽기 실패", t)
                    }
                    state = "실행 중"
                    AppLog.i("hotspot", "로컬 핫스팟 시작 ssid=$ssid")
                    onDone(true)
                }

                override fun onStopped() {
                    reservation = null
                    state = "중지"
                    AppLog.i("hotspot", "로컬 핫스팟 중지됨")
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    state = "실패(코드 $reason)"
                    AppLog.e("hotspot", "로컬 핫스팟 실패 reason=$reason (1=채널없음 2=호환안됨 3=테더링금지)")
                    onDone(false)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            state = "예외: ${t.javaClass.simpleName}"
            AppLog.e("hotspot", "startLocalOnlyHotspot 예외", t)
            onDone(false)
        }
    }

    fun stop() {
        try { reservation?.close() } catch (_: Throwable) {}
        reservation = null
        ssid = ""
        pass = ""
        state = "중지"
        AppLog.i("hotspot", "로컬 핫스팟 중지 요청")
    }
}
