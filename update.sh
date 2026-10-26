#!/data/data/com.termux/files/usr/bin/bash

# =========================================================
# MyInternet UI 고도화 + 자동화 + QR 스캔 + 아이콘 적용
# Termux 실행용 통합 스크립트
# =========================================================

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m'

echo -e "${BLUE}===========================================${NC}"
echo -e "${BLUE} MyInternet 업데이트 시작${NC}"
echo -e "${BLUE}===========================================${NC}"

# ---------------------------------------------------------
# 0. 프로젝트 루트 확인
# ---------------------------------------------------------
if [ ! -f "settings.gradle.kts" ]; then
    echo -e "${RED}[오류] settings.gradle.kts 가 없습니다. 프로젝트 루트에서 실행하세요.${NC}"
    exit 1
fi

# ---------------------------------------------------------
# 1. 디렉토리 생성
# ---------------------------------------------------------
mkdir -p app/src/main/res/drawable
mkdir -p app/src/main/res/values
mkdir -p app/src/main/res/mipmap-anydpi-v26
mkdir -p app/src/main/java/com/myinternet/share

# ---------------------------------------------------------
# 2. 앱 아이콘 복사 (icon.png 가 있으면)
# ---------------------------------------------------------
if [ -f "icon.png" ]; then
    cp icon.png app/src/main/res/drawable/app_icon.png
    echo -e "${GREEN}[OK] 앱 아이콘 복사 완료${NC}"
else
    echo -e "${YELLOW}[경고] icon.png 가 없습니다. 아이콘은 기본값으로 빌드됩니다.${NC}"
fi

# ---------------------------------------------------------
# 3. 테마 추가
# ---------------------------------------------------------
cat << 'EOF' > app/src/main/res/values/themes.xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.MyInternet" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:colorPrimary">#1565C0</item>
        <item name="android:colorPrimaryDark">#0D47A1</item>
        <item name="android:colorAccent">#42A5F5</item>
        <item name="android:windowBackground">#F5F7FA</item>
    </style>
</resources>
EOF
echo -e "${GREEN}[OK] themes.xml 생성${NC}"

# ---------------------------------------------------------
# 4. build.gradle.kts - QR 스캔 라이브러리 추가
# ---------------------------------------------------------
# zxing-android-embedded 의존성 보장
if ! grep -q "zxing-android-embedded" app/build.gradle.kts; then
    sed -i 's|implementation("com.google.zxing:core:3.5.3")|implementation("com.google.zxing:core:3.5.3")\n    implementation("com.journeyapps:zxing-android-embedded:4.3.0")|' app/build.gradle.kts
    echo -e "${GREEN}[OK] zxing-android-embedded 의존성 추가${NC}"
else
    echo -e "${GREEN}[OK] zxing-android-embedded 이미 존재${NC}"
fi

# ---------------------------------------------------------
# 5. AndroidManifest.xml (카메라 권한 + QrScanActivity 등록)
# ---------------------------------------------------------
cat << 'EOF' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.CAMERA" />

    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:name=".App"
        android:label="MyInternet"
        android:icon="@drawable/app_icon"
        android:roundIcon="@drawable/app_icon"
        android:theme="@style/Theme.MyInternet"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".LogActivity" android:exported="false" />

        <service
            android:name=".ServerService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />

        <service
            android:name=".VpnClientService"
            android:process=":vpn"
            android:exported="false"
            android:permission="android.permission.BIND_VPN_SERVICE">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>
    </application>
</manifest>
EOF
echo -e "${GREEN}[OK] AndroidManifest.xml 갱신${NC}"

# ---------------------------------------------------------
# 6. MainActivity.kt (프리미엄 UI + QR 스캔 + 자동 연결)
# ---------------------------------------------------------
cat << 'EOF' > app/src/main/java/com/myinternet/share/MainActivity.kt
package com.myinternet.share

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : Activity() {
    private lateinit var hostEt: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var btnServer: Button
    private lateinit var btnClient: Button
    private lateinit var btnScan: Button
    private lateinit var qrView: ImageView
    private var lastQr = ""
    private var isClientMode = false
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleWifiQr(result.contents)
        } else {
            updateStatus("스캔 취소", "QR 스캔이 취소되었습니다.", Color.parseColor("#666666"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNecessaryPermissions()
        buildPremiumUI()
    }

    private fun requestNecessaryPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        perms += Manifest.permission.CAMERA
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun buildPremiumUI() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val title = TextView(this).apply {
            text = "MyInternet"
            textSize = 28f
            setTextColor(Color.parseColor("#0D47A1"))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
            setElevation(10f)
        }
        val cardParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, pad, 0, pad) }

        statusTitle = TextView(this).apply {
            text = "대기 중"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.CENTER
        }
        statusDesc = TextView(this).apply {
            text = "아래 버튼을 눌러 시작하세요"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusDesc)
        root.addView(statusCard, cardParams)

        val btnContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        btnServer = Button(this).apply {
            text = "서버 시작\n(데이터 폰)"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1565C0"))
            setTextColor(Color.WHITE)
            setOnClickListener { startServerMode() }
        }
        btnClient = Button(this).apply {
            text = "수동 연결\n(IP 입력)"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener { startClientMode() }
        }

        val btnParams = LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f)
        btnParams.setMargins(8, 0, 8, 0)
        btnContainer.addView(btnServer, btnParams)
        btnContainer.addView(btnClient, btnParams)
        root.addView(btnContainer)

        btnScan = Button(this).apply {
            text = "QR 코드 스캔하여 자동 연결"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#F57C00"))
            setTextColor(Color.WHITE)
            setOnClickListener { startQrScan() }
        }
        val scanParams = LinearLayout.LayoutParams(-1, (60 * resources.displayMetrics.density).toInt()).apply { setMargins(8, pad, 8, 0) }
        root.addView(btnScan, scanParams)

        hostEt = EditText(this).apply {
            hint = "서버 IP 주소 입력"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(NetUtil.serverHost(this@MainActivity))
            visibility = View.GONE
            setPadding(0, pad, 0, pad)
            gravity = Gravity.CENTER
        }
        root.addView(hostEt)

        qrView = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val qs = (200 * resources.displayMetrics.density).toInt()
        val qrParams = LinearLayout.LayoutParams(qs, qs).apply { gravity = Gravity.CENTER; setMargins(0, pad, 0, pad) }
        root.addView(qrView, qrParams)

        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(spacer)

        val utilRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun utilBtn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            textSize = 12f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#1565C0"))
            setOnClickListener { f() }
        }
        utilRow.addView(utilBtn("로그 확인") { startActivity(Intent(this, LogActivity::class.java)) })
        utilRow.addView(utilBtn("네트워크 설정") { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
        utilRow.addView(utilBtn("진단 테스트") { runTest(hostEt.text.toString().trim()) })

        root.addView(utilRow)
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    // ---------- QR 스캔 ----------
    private fun startQrScan() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("서버 폰의 Wi-Fi QR 코드를 스캔하세요")
        options.setBeepEnabled(false)
        options.setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    private fun handleWifiQr(qrText: String) {
        try {
            if (!qrText.startsWith("WIFI:")) {
                updateStatus("QR 오류", "Wi-Fi QR 코드가 아닙니다.", Color.RED)
                return
            }
            val ssid = Regex("S:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: return
            val pass = Regex("P:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: ""

            AppLog.i("ui", "QR 스캔 성공 ssid=$ssid")
            updateStatus("Wi-Fi 연결 중...", "SSID: $ssid", Color.parseColor("#F57C00"))
            connectToWifi(ssid, pass)
        } catch (t: Throwable) {
            AppLog.e("ui", "QR 처리 실패", t)
            updateStatus("QR 오류", "QR 해석 실패: ${t.message}", Color.RED)
        }
    }

    private fun connectToWifi(ssid: String, pass: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }
                    .build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build()

                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

                wifiCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        runOnUiThread {
                            updateStatus("Wi-Fi 연결됨", "서버 IP 확인 중...", Color.parseColor("#2E7D32"))
                            // 약간 대기 후 게이트웨이 IP 추출
                            statusDesc.postDelayed({
                                val host = NetUtil.serverHost(this@MainActivity)
                                hostEt.setText(host)
                                AppLog.i("ui", "QR 자동 IP = $host")
                                updateStatus("VPN 연결 중...", "서버 $host", Color.parseColor("#F57C00"))
                                requestVpnPermissionAndStart(host)
                            }, 1500)
                        }
                    }

                    override fun onUnavailable() {
                        runOnUiThread {
                            updateStatus("Wi-Fi 실패", "자동 연결 실패. 수동으로 와이파이를 연결해주세요.", Color.RED)
                        }
                    }
                }
                cm.requestNetwork(request, wifiCallback!!)
            } else {
                // Android 9 이하: 시스템 Wi-Fi 설정 화면으로 유도
                updateStatus("수동 연결 필요", "설정에서 '$ssid' 에 연결 후 다시 시도하세요.", Color.parseColor("#F57C00"))
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (t: Throwable) {
            AppLog.e("ui", "Wi-Fi 연결 실패", t)
            updateStatus("Wi-Fi 오류", t.message ?: "알 수 없는 오류", Color.RED)
        }
    }

    // ---------- 서버 모드 ----------
    private fun startServerMode() {
        if (ServerService.running) {
            LocalHotspot.stop()
            stopService(Intent(this, ServerService::class.java))
            updateStatus("대기 중", "서버가 중지되었습니다.", Color.parseColor("#333333"))
        } else {
            updateStatus("서버 시작 중...", "로컬 핫스팟을 활성화하고 있습니다.", Color.parseColor("#F57C00"))
            LocalHotspot.start(this) { ok ->
                if (ok) {
                    try {
                        startForegroundService(Intent(this, ServerService::class.java))
                        updateStatus(
                            "서버 실행 중",
                            "핫스팟: ${LocalHotspot.ssid}\n비밀번호: ${LocalHotspot.pass}",
                            Color.parseColor("#1565C0")
                        )
                        showQr()
                    } catch (t: Throwable) {
                        AppLog.e("ui", "서버 시작 실패", t)
                        updateStatus("오류", "서버 시작 실패: ${t.message}", Color.RED)
                    }
                } else {
                    updateStatus("실패", "핫스팟을 켤 수 없습니다. 설정을 확인하세요.", Color.RED)
                }
            }
        }
        refresh()
    }

    // ---------- 클라이언트 수동 모드 ----------
    private fun startClientMode() {
        if (isClientMode) {
            startService(Intent(this, VpnClientService::class.java).setAction("stop"))
            isClientMode = false
            updateStatus("대기 중", "VPN 연결이 해제되었습니다.", Color.parseColor("#333333"))
        } else {
            hostEt.visibility = View.VISIBLE
            val host = hostEt.text.toString().trim()
            if (host.isEmpty()) {
                Toast.makeText(this, "서버 IP를 입력하거나 QR을 스캔하세요", Toast.LENGTH_SHORT).show()
                return
            }
            updateStatus("연결 중...", "VPN 터널을 생성하고 있습니다.", Color.parseColor("#F57C00"))
            requestVpnPermissionAndStart(host)
        }
        refresh()
    }

    private fun requestVpnPermissionAndStart(host: String) {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            // 결과는 onActivityResult(100) 에서 처리
            pendingHost = host
            startActivityForResult(prep, 100)
        } else {
            startVpn(host)
        }
    }

    private var pendingHost: String = ""

    private fun startVpn(host: String) {
        try {
            startService(Intent(this, VpnClientService::class.java).putExtra("host", host))
            isClientMode = true
            updateStatus("클라이언트 연결됨", "서버 $host 로 우회 중", Color.parseColor("#2E7D32"))
        } catch (t: Throwable) {
            AppLog.e("ui", "VPN 시작 실패", t)
            updateStatus("오류", "VPN 시작 실패: ${t.message}", Color.RED)
        }
    }

    // ---------- 공통 ----------
    private fun updateStatus(title: String, desc: String, color: Int) {
        statusTitle.text = title
        statusTitle.setTextColor(color)
        statusDesc.text = desc
    }

    private fun showQr() {
        if (LocalHotspot.ssid.isEmpty()) {
            qrView.visibility = View.GONE
            return
        }
        val key = "${LocalHotspot.ssid}|${LocalHotspot.pass}|${LocalHotspot.sec}"
        if (key != lastQr) {
            try {
                qrView.setImageBitmap(QrHelper.wifi(LocalHotspot.ssid, LocalHotspot.pass, LocalHotspot.sec))
                lastQr = key
            } catch (t: Throwable) {
                AppLog.e("ui", "QR 생성 실패", t)
            }
        }
        qrView.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK) {
                startVpn(pendingHost)
            } else {
                updateStatus("권한 거부", "VPN 권한이 필요합니다.", Color.RED)
            }
        }
    }

    private fun runTest(host: String) {
        if (host.isEmpty()) return
        Toast.makeText(this, "테스트 중...", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = "대상: $host\nTCP: ${Socks5Test.tcp(host, ServerService.PORT)}\nUDP: ${Socks5Test.udp(host, ServerService.PORT)}"
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("진단 결과").setMessage(msg).setPositiveButton("확인", null).show()
            }
        }.start()
    }

    private fun refresh() {
        if (ServerService.running) {
            btnServer.text = "서버 중지"
            btnServer.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnServer.text = "서버 시작\n(데이터 폰)"
            btnServer.setBackgroundColor(Color.parseColor("#1565C0"))
        }

        if (isClientMode) {
            btnClient.text = "연결 해제"
            btnClient.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnClient.text = "수동 연결\n(IP 입력)"
            btnClient.setBackgroundColor(Color.parseColor("#2E7D32"))
        }
    }
}
EOF
echo -e "${GREEN}[OK] MainActivity.kt 갱신${NC}"

# ---------------------------------------------------------
# 7. Git 커밋 & 푸시 (자동 빌드 트리거)
# ---------------------------------------------------------
echo -e "${BLUE}--- Git 변경사항 커밋 및 푸시 ---${NC}"
git add .
git commit -m "feat: premium UI + one-tap automation + QR scan auto-connect + app icon"

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
echo -e "${BLUE}현재 브랜치: $CURRENT_BRANCH${NC}"
git push origin "$CURRENT_BRANCH"

echo -e "${GREEN}===========================================${NC}"
echo -e "${GREEN} 모든 작업 완료!${NC}"
echo -e "${GREEN} GitHub Actions 탭에서 빌드 결과를 확인하세요.${NC}"
echo -e "${GREEN}===========================================${NC}"