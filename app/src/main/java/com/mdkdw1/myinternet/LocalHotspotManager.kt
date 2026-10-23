package com.mdkdw1.myinternet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper

class LocalHotspotManager(private val context: Context) {
    companion object {
        @Volatile var hotspotNetwork: Network? = null
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var activeCb: HotspotCallback? = null

    interface HotspotCallback {
        fun onHotspotStarted(ssid: String, password: String)
        fun onHotspotFailed(reason: String)
        fun onClientConnected()
        fun onClientDisconnected()
    }

    fun startHotspot(callback: HotspotCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            callback.onHotspotFailed("Android 8.0 이상 필요"); return
        }
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    super.onStarted(res); reservation = res
                    val cfg = res.wifiConfiguration
                    val ssid = cfg?.SSID; val pw = cfg?.preSharedKey
                    Handler(Looper.getMainLooper()).post {
                        if (ssid.isNullOrBlank() || pw.isNullOrBlank())
                            callback.onHotspotFailed("SSID/비번 확인 실패")
                        else callback.onHotspotStarted(ssid, pw)
                    }
                }
                override fun onStopped() {
                    Handler(Looper.getMainLooper()).post { callback.onHotspotFailed("핫스팟 중지됨") }
                }
                override fun onFailed(reason: Int) {
                    Handler(Looper.getMainLooper()).post { callback.onHotspotFailed("핫스팟 시작 실패: $reason") }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { callback.onHotspotFailed("오류: ${e.message}") }
    }

    fun stopHotspot() { try { reservation?.close() } catch (_: Exception) {}; reservation = null }

    fun cancelPending() {
        activeCallback?.let { try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        activeCallback = null; activeCb = null; hotspotNetwork = null
    }

    fun connectToHotspot(ssid: String, password: String, callback: HotspotCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callback.onHotspotFailed("Android 10 이상 필요"); return
        }
        cancelPending(); activeCb = callback

        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .setNetworkSpecifier(specifier).build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                hotspotNetwork = network
                try { connectivityManager.bindProcessToNetwork(network) } catch (_: Exception) {}
                Handler(Looper.getMainLooper()).post { activeCb?.onClientConnected() }
            }
            override fun onUnavailable() {
                Handler(Looper.getMainLooper()).post { activeCb?.onHotspotFailed("연결 실패 (시간 초과/거부)") }
                hotspotNetwork = null; activeCallback = null
            }
            override fun onLost(network: Network) {
                if (hotspotNetwork == network) hotspotNetwork = null
                try { connectivityManager.bindProcessToNetwork(null) } catch (_: Exception) {}
                Handler(Looper.getMainLooper()).post { activeCb?.onClientDisconnected() }
            }
        }
        activeCallback = cb
        try { connectivityManager.requestNetwork(request, cb, 15000) }
        catch (e: Exception) { activeCallback = null; callback.onHotspotFailed("연결 요청 오류: ${e.message}") }
    }
}
