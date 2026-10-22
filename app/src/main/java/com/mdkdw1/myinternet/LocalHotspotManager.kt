package com.mdkdw1.myinternet

import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class LocalHotspotManager(private val context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    interface HotspotCallback {
        fun onHotspotStarted(ssid: String, password: String)
        fun onHotspotFailed(reason: String)
        fun onClientConnected()
        fun onClientDisconnected()
    }

    // 서버: 로컬 핫스팟 시작
    fun startHotspot(callback: HotspotCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onHotspotFailed("Android 8.0 이상 필요")
            return
        }

        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    super.onStarted(res)
                    reservation = res
                    val config = res.wifiConfiguration
                    // 안드로이드 버전에 따라 SSID/비번 가져오는 방식이 다름
                    val ssid = config?.SSID ?: "Unknown"
                    val password = config?.preSharedKey ?: "Unknown"
                    Handler(Looper.getMainLooper()).post {
                        callback.onHotspotStarted(ssid, password)
                    }
                }

                override fun onStopped() {
                    super.onStopped()
                    Handler(Looper.getMainLooper()).post {
                        callback.onHotspotFailed("핫스팟 중지됨")
                    }
                }

                override fun onFailed(reason: Int) {
                    super.onFailed(reason)
                    Handler(Looper.getMainLooper()).post {
                        callback.onHotspotFailed("핫스팟 시작 실패: $reason")
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            callback.onHotspotFailed("오류: ${e.message}")
        }
    }

    // 서버: 핫스팟 중지
    fun stopHotspot() {
        try {
            reservation?.close()
            reservation = null
        } catch (_: Exception) {}
    }

    // 클라이언트: 특정 SSID에 연결 요청
    fun connectToHotspot(ssid: String, password: String, callback: HotspotCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callback.onHotspotFailed("Android 10 이상 필요")
            return
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // 인터넷 없음 명시
            .setNetworkSpecifier(specifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                super.onAvailable(network)
                Handler(Looper.getMainLooper()).post {
                    callback.onClientConnected()
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Handler(Looper.getMainLooper()).post {
                    callback.onHotspotFailed("연결 실패 (시간 초과 또는 거부됨)")
                }
            }

            override fun onLost(network: android.net.Network) {
                super.onLost(network)
                Handler(Looper.getMainLooper()).post {
                    callback.onClientDisconnected()
                }
            }
        }

        try {
            connectivityManager.requestNetwork(request, networkCallback)
        } catch (e: Exception) {
            callback.onHotspotFailed("연결 요청 오류: ${e.message}")
        }
    }
}
