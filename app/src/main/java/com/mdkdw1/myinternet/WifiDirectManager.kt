package com.mdkdw1.myinternet

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {
    interface Listener {
        fun onPeersChanged(peers: List<WifiP2pDevice>)
        fun onConnected(ownerIp: String?)
        fun onDisconnected()
        fun onError(msg: String)
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var listener: Listener? = null
    private var receiver: BroadcastReceiver? = null
    private var isGroupOwner = false
    private var groupOwnerIp: String? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var myMacAddress: String? = null

    fun setListener(l: Listener?) { listener = l }
    fun isSupported(): Boolean = manager != null

    fun init() {
        if (channel != null) return
        channel = manager?.initialize(context, Looper.getMainLooper()) { }
        registerReceiver()
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) listener?.onError("Wi-Fi Direct가 꺼져 있습니다")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION") intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        if (device != null) myMacAddress = device.deviceAddress
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val netInfo = if (Build.VERSION.SDK_INT >= 29)
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        if (netInfo?.groupFormed == true) {
                            isGroupOwner = netInfo.isGroupOwner
                            groupOwnerIp = netInfo.groupOwnerAddress?.hostAddress
                            listener?.onConnected(groupOwnerIp)
                        } else listener?.onDisconnected()
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }

    /** 기기 검색 (BUSY/ERROR 방지: 기존 검색 중지 후 시작, 실패 시 채널 리셋 및 자동 재시도) */
    fun discoverPeers() {
        init()
        val ch = channel ?: run { listener?.onError("Wi-Fi Direct 초기화 실패"); return }

        val wm = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        if (wm == null || !wm.isWifiEnabled) {
            listener?.onError("Wi-Fi가 꺼져 있습니다. Wi-Fi를 켜주세요")
            return
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            val gps = lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ?: false
            val net = lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ?: false
            if (!gps && !net) {
                listener?.onError("위치 서비스(GPS)가 꺼져 있습니다. 설정에서 켜주세요")
                return
            }
        }

        try { manager?.stopPeerDiscovery(ch, null) } catch (_: Exception) {}
        handler.postDelayed({
            try {
                manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { listener?.onError("검색 시작됨. 주변 기기를 기다리세요") }
                    override fun onFailure(reason: Int) {
                        val msg = when (reason) {
                            WifiP2pManager.BUSY -> "BUSY: 다른 작업 중. 2초 후 재시도"
                            WifiP2pManager.ERROR -> "ERROR: Wi-Fi 초기화 중. 자동 재시도..."
                            WifiP2pManager.P2P_UNSUPPORTED -> "이 기기는 Wi-Fi Direct 미지원"
                            else -> "검색 실패: $reason"
                        }
                        listener?.onError(msg)
                        
                        // ERROR(0) 시 채널 리셋 후 재시도
                        if (reason == WifiP2pManager.ERROR) {
                            handler.postDelayed({
                                try { channel?.close() } catch (_: Exception) {}
                                channel = manager?.initialize(context, Looper.getMainLooper()) { }
                                handler.postDelayed({
                                    try {
                                        manager?.discoverPeers(channel!!, object : WifiP2pManager.ActionListener {
                                            override fun onSuccess() { listener?.onError("재시도 성공: 검색 시작됨") }
                                            override fun onFailure(r: Int) { listener?.onError("재시도도 실패: $r") }
                                        })
                                    } catch (_: SecurityException) {}
                                }, 1000)
                            }, 2000)
                        }
                    }
                })
            } catch (_: SecurityException) { listener?.onError("위치 권한 필요 (설정에서 허용)") }
        }, 500)
    }

    private fun requestPeers() {
        try {
            channel?.let { manager?.requestPeers(it) { peers -> listener?.onPeersChanged(peers.deviceList.toList()) } }
        } catch (_: SecurityException) {}
    }

    fun connect(device: WifiP2pDevice) {
        val ch = channel ?: return
        try { manager?.stopPeerDiscovery(ch, null) } catch (_: Exception) {}
        val cfg = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        try {
            manager?.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {
                    cfg.wps.setup = WpsInfo.DISPLAY
                    try {
                        manager?.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {}
                            override fun onFailure(r2: Int) { listener?.onError("연결 실패: $r2") }
                        })
                    } catch (_: SecurityException) { listener?.onError("권한 필요") }
                }
            })
        } catch (_: SecurityException) { listener?.onError("위치 권한 필요") }
    }

    fun createGroup() {
        init()
        val ch = channel ?: return
        try { manager?.stopPeerDiscovery(ch, null) } catch (_: Exception) {}
        try {
            manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { scheduleCreate(ch) }
                override fun onFailure(reason: Int) { scheduleCreate(ch) }
            })
        } catch (_: SecurityException) { listener?.onError("권한 필요") }
    }

    private fun scheduleCreate(ch: WifiP2pManager.Channel) {
        handler.postDelayed({
            try {
                manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { listener?.onError("그룹 생성됨. 클라이언트에서 검색하세요") }
                    override fun onFailure(reason: Int) {
                        if (reason == WifiP2pManager.BUSY) {
                            handler.postDelayed({
                                try {
                                    manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() { listener?.onError("그룹 생성됨") }
                                        override fun onFailure(r2: Int) { listener?.onError(busyMsg(r2)) }
                                    })
                                } catch (_: SecurityException) {}
                            }, 2000)
                        } else listener?.onError(busyMsg(reason))
                    }
                })
            } catch (_: SecurityException) { listener?.onError("권한 필요") }
        }, 500)
    }

    private fun busyMsg(reason: Int) = when (reason) {
        WifiP2pManager.BUSY -> "BUSY: 핫스팟을 끄고 다시 시도하세요"
        WifiP2pManager.ERROR -> "ERROR: Wi-Fi를 껐다 켜세요"
        WifiP2pManager.P2P_UNSUPPORTED -> "이 기기는 Wi-Fi Direct 미지원"
        else -> "그룹 생성 실패: $reason"
    }

    fun removeGroup() { try { channel?.let { manager?.removeGroup(it, null) } } catch (_: SecurityException) {} }
    fun stopDiscovery() { try { channel?.let { manager?.stopPeerDiscovery(it, null) } } catch (_: SecurityException) {} }

    fun close() {
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null; channel?.close(); channel = null
    }

    val groupOwnerAddress: String? get() = groupOwnerIp
}
