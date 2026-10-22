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
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            listener?.onError("Wi-Fi Direct가 꺼져 있습니다")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
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

    fun discoverPeers() {
        init()
        try {
            channel?.let {
                manager?.discoverPeers(it, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) { listener?.onError("검색 실패: $reason") }
                })
            }
        } catch (_: SecurityException) { listener?.onError("위치 권한 필요") }
    }

    private fun requestPeers() {
        try {
            channel?.let {
                manager?.requestPeers(it) { peers ->
                    listener?.onPeersChanged(peers.deviceList.toList())
                }
            }
        } catch (_: SecurityException) {}
    }

    fun connect(device: WifiP2pDevice) {
        val cfg = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        try {
            channel?.let {
                manager?.connect(it, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) { listener?.onError("연결 실패: $reason") }
                })
            }
        } catch (_: SecurityException) { listener?.onError("위치 권한 필요") }
    }

    fun createGroup() {
        init()
        try {
            channel?.let {
                manager?.createGroup(it, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) { listener?.onError("그룹 생성 실패: $reason") }
                })
            }
        } catch (_: SecurityException) { listener?.onError("권한 필요") }
    }

    fun removeGroup() {
        try { channel?.let { manager?.removeGroup(it, null) } } catch (_: SecurityException) {}
    }

    fun stopDiscovery() {
        try { channel?.let { manager?.stopPeerDiscovery(it, null) } } catch (_: SecurityException) {}
    }

    fun close() {
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null
        channel?.close()
        channel = null
    }

    val groupOwnerAddress: String? get() = groupOwnerIp
}
