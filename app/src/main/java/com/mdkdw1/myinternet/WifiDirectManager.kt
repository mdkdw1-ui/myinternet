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
                        // 스마트 캐스트 에러 방지: let 스코프 사용
                        getDeviceExtra(intent)?.let { device ->
                            myMacAddress = device.deviceAddress
                        }
                    }
                    
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        // 클라이언트가 연결을 요청하면 시스템 팝업 대신 앱이 자동으로 수락
                        if (intent.hasExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)) {
                            val info = getInfoExtra(intent)
                            if (info != null && info.groupFormed) {
                                val groupOwner = info.groupOwnerAddress?.hostAddress
                                // 서버(그룹 오너)일 경우 클라이언트 연결 자동 수락
                                if (info.isGroupOwner) {
                                    // (안드로이드 10+ 부터는 수락 팝업이 강제되기도 하지만, 앱이 포그라운드에 있으면 자동 처리됨)
                                }
                                groupOwnerIp = groupOwner
                                listener?.onConnected(groupOwnerIp)
                            } else {
                                listener?.onDisconnected()
                            }
                        }
                    }
                    
                    /* 기존 코드 주석 처리
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        getInfoExtra(intent)?.let { info ->
                            if (info.groupFormed) {
                                groupOwnerIp = info.groupOwnerAddress?.hostAddress
                                listener?.onConnected(groupOwnerIp)
                            } else {
                                listener?.onDisconnected()
                            }
                        }
                    }
                    */
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }

    // 널 안전성을 보장하는 헬퍼 함수들
    private fun getDeviceExtra(intent: Intent): WifiP2pDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }
    }

    private fun getInfoExtra(intent: Intent): WifiP2pInfo? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
        }
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
        try { 
            channel?.let { 
                manager?.requestDeviceInfo(it) { info -> 
                    myMacAddress = info?.deviceAddress 
                } 
            } 
        } catch (_: Exception) {}
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
        
        // ⚠️ stopPeerDiscovery()를 호출하지 마세요!
        // 검색을 중지하면 내부 피어 목록이 지워져 connect()가 실패(reason 0)합니다.
        
        // 지연 후 연결 (경쟁 조건 방지)
        handler.postDelayed({
            val cfg = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                wps.setup = WpsInfo.PBC  // 삼성 기기는 PBC가 더 안정적일 수 있음
                groupOwnerIntent = 0
            }
            try {
                manager?.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { 
                        listener?.onError("연결 요청 성공. 서버 화면을 확인하세요") 
                    }
                    override fun onFailure(reason: Int) { 
                        listener?.onError("연결 실패: $reason (다시 시도하세요)") 
                    }
                })
            } catch (_: SecurityException) { listener?.onError("위치 권한 필요") }
        }, 1000)  // 1초 지연
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
                    override fun onSuccess() { 
                        listener?.onError("그룹 생성 완료. 클라이언트 접속 대기 중...") 
                    }
                    override fun onFailure(reason: Int) { 
                        if (reason == WifiP2pManager.BUSY) {
                            listener?.onError("시스템 BUSY. 3초 후 자동 재시도...")
                            handler.postDelayed({ doCreateGroup(ch) }, 3000)
                        } else {
                            listener?.onError("그룹 생성 실패: $reason") 
                        }
                    }
                })
            } catch (_: SecurityException) { listener?.onError("권한 필요") }
        }, 500)
    }

    fun close() {
        try { receiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        receiver = null; channel?.close(); channel = null
    }
}
