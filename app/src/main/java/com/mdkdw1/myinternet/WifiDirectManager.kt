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
    private var groupOwnerIp: String? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile var myMacAddress: String? = null

    fun setListener(l: Listener?) { listener = l }
    
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
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        // 89번 줄 에러 해결: 널 체크를 확실하게 수행
                        val device: WifiP2pDevice?
                        if (Build.VERSION.SDK_INT >= 33) {
                            device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        if (device != null) {
                            myMacAddress = device.deviceAddress
                        }
                    }
                    
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        // 에러 해결: 명시적 타입 캐스팅과 완벽한 널 체크
                        val parcelable = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        }
                        
                        if (parcelable != null && parcelable.groupFormed) {
                            groupOwnerIp = parcelable.groupOwnerAddress?.hostAddress
                            listener?.onConnected(groupOwnerIp)
                        } else {
                            listener?.onDisconnected()
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }

    fun fetchMyMac(): String? {
        if (myMacAddress != null) return myMacAddress
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            for (intf in interfaces) {
                if (intf.name.contains("p2p") || intf.name.contains("wlan")) {
                    val mac = intf.hardwareAddress
                    if (mac != null && mac.isNotEmpty()) {
                        return mac.joinToString(":") { String.format("%02x", it) }
                    }
                }
            }
        } catch (_: Exception) {}
        try { channel?.let { manager?.requestDeviceInfo(it) { info -> myMacAddress = info.deviceAddress } } } catch (_: Exception) {}
        return myMacAddress
    }

    fun discoverPeers() {
        init()
        val ch = channel ?: return
        try { manager?.stopPeerDiscovery(ch, null) } catch (_: Exception) {}
        handler.postDelayed({
            try {
                manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { listener?.onError("검색 시작됨") }
                    override fun onFailure(reason: Int) { listener?.onError("검색 실패: $reason") }
                })
            } catch (_: SecurityException) { listener?.onError("위치 권한 필요") }
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
        handler.postDelayed({
            val cfg = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.DISPLAY
                groupOwnerIntent = 0
            }
            try {
                manager?.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) { listener?.onError("연결 실패: $reason") }
                })
            } catch (_: SecurityException) { listener?.onError("위치 권한 필요") }
        }, 500)
    }

    fun createGroup() {
        init()
        val ch = channel ?: return
        try { manager?.stopPeerDiscovery(ch, null) } catch (_: Exception) {}
        try {
            manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { doCreateGroup(ch) }
                override fun onFailure(reason: Int) { doCreateGroup(ch) }
            })
        } catch (_: SecurityException) { listener?.onError("권한 필요") }
    }

    private fun doCreateGroup(ch: WifiP2pManager.Channel) {
        handler.postDelayed({
            try {
                manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { listener?.onError("그룹 생성됨") }
                    override fun onFailure(reason: Int) { listener?.onError("그룹 생성 실패: $reason") }
                })
            } catch (_: SecurityException) { listener?.onError("권한 필요") }
        }, 500)
    }

    fun close() {
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null; channel?.close(); channel = null
    }
}
