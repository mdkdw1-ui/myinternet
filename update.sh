#!/data/data/com.termux/files/usr/bin/bash

# =========================================================
# MyInternet UI 고도화 (AppCompat 제거, 테마 충돌 해결)
# =========================================================

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m'

echo -e "${BLUE}===========================================${NC}"
echo -e "${BLUE} MyInternet 업데이트 시작 (테마 충돌 해결)${NC}"
echo -e "${BLUE}===========================================${NC}"

if [ ! -f "settings.gradle.kts" ]; then
    echo -e "${RED}[오류] 프로젝트 루트에서 실행하세요.${NC}"
    exit 1
fi

mkdir -p app/src/main/res/drawable app/src/main/res/values app/src/main/java/com/myinternet/share

# 1. gradle.properties 정리 (AndroidX는 유지하되 Jetifier는 끔)
if grep -q "android.useAndroidX=true" gradle.properties; then
    echo -e "${GREEN}[OK] useAndroidX 이미 활성화됨${NC}"
else
    echo "android.useAndroidX=true" >> gradle.properties
fi

# 2. 아이콘 복사
if [ -f "icon.png" ]; then
    cp icon.png app/src/main/res/drawable/app_icon.png
    echo -e "${GREEN}[OK] 앱 아이콘 복사 완료${NC}"
fi

# 3. 테마를 Material(기본)로 되돌림 (AppCompat 제거)
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
echo -e "${GREEN}[OK] themes.xml (Material 테마)${NC}"

# 4. build.gradle.kts 에서 appcompat 제거 (QR 라이브러리만 유지)
# 기존 appcompat 라인 삭제
sed -i '/androidx.appcompat:appcompat/d' app/build.gradle.kts

# QR 라이브러리 확인/추가
if ! grep -q "zxing-android-embedded" app/build.gradle.kts; then
    sed -i 's|implementation("com.google.zxing:core:3.5.3")|implementation("com.google.zxing:core:3.5.3")\n    implementation("com.journeyapps:zxing-android-embedded:4.3.0")|' app/build.gradle.kts
fi
echo -e "${GREEN}[OK] build.gradle.kts 정리 (appcompat 제거)${NC}"

# 5. Manifest (테마 원복 및 카메라 권한 유지)
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
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".LogActivity" android:exported="false" />
        <service android:name=".ServerService" android:exported="false" android:foregroundServiceType="connectedDevice" />
        <service android:name=".VpnClientService" android:process=":vpn" android:exported="false" android:permission="android.permission.BIND_VPN_SERVICE">
            <intent-filter><action android:name="android.net.VpnService" /></intent-filter>
        </service>
    </application>
</manifest>
EOF

# 6. MainActivity.kt (Activity 상속 + startActivityForResult 방식으로 원복)
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
    private var pendingHost = ""
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

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
        root.addView(TextView(this).apply {
            text = "MyInternet"; textSize = 28f; setTextColor(Color.parseColor("#0D47A1"))
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad); setElevation(10f)
        }
        statusTitle = TextView(this).apply {
            text = "대기 중"; textSize = 20f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER
        }
        statusDesc = TextView(this).apply {
            text = "아래 버튼을 눌러 시작하세요"; textSize = 14f
            setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER; setPadding(0, 8, 0, 0)
        }
        statusCard.addView(statusTitle); statusCard.addView(statusDesc)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, pad, 0, pad) })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnServer = Button(this).apply {
            text = "서버 시작\n(데이터 폰)"; textSize = 16f; setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE)
            setOnClickListener { startServerMode() }
        }
        btnClient = Button(this).apply {
            text = "수동 연결\n(IP 입력)"; textSize = 16f; setBackgroundColor(Color.parseColor("#2E7D32")); setTextColor(Color.WHITE)
            setOnClickListener { startClientMode() }
        }
        btnRow.addView(btnServer, LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f).apply { setMargins(8, 0, 8, 0) })
        btnRow.addView(btnClient, LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f).apply { setMargins(8, 0, 8, 0) })
        root.addView(btnRow)

        btnScan = Button(this).apply {
            text = "QR 코드 스캔하여 자동 연결"; textSize = 16f; setBackgroundColor(Color.parseColor("#F57C00")); setTextColor(Color.WHITE)
            setOnClickListener { startQrScanLegacy() }
        }
        root.addView(btnScan, LinearLayout.LayoutParams(-1, (60 * resources.displayMetrics.density).toInt()).apply { setMargins(8, pad, 8, 0) })

        hostEt = EditText(this).apply {
            hint = "서버 IP 주소"; inputType = InputType.TYPE_CLASS_TEXT
            setText(NetUtil.serverHost(this@MainActivity)); visibility = View.GONE
            gravity = Gravity.CENTER; setPadding(0, pad, 0, pad)
        }
        root.addView(hostEt)

        qrView = ImageView(this).apply { visibility = View.GONE; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(qrView, LinearLayout.LayoutParams((200 * resources.displayMetrics.density).toInt(), (200 * resources.displayMetrics.density).toInt()).apply { gravity = Gravity.CENTER; setMargins(0, pad, 0, pad) })

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })

        val utilRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun utilBtn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; setBackgroundColor(Color.TRANSPARENT); setTextColor(Color.parseColor("#1565C0")); setOnClickListener { f() }
        }
        utilRow.addView(utilBtn("로그") { startActivity(Intent(this, LogActivity::class.java)) })
        utilRow.addView(utilBtn("설정") { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
        utilRow.addView(utilBtn("테스트") { runTest(hostEt.text.toString().trim()) })
        root.addView(utilRow)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    // 레거시 startActivityForResult 로 QR 스캔 (테마 충돌 회피)
    private fun startQrScanLegacy() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("서버 폰의 Wi-Fi QR 코드를 스캔하세요")
        options.setBeepEnabled(false)
        options.setOrientationLocked(false)
        // ScanContract 대신 IntentIntegrator 스타일로 실행
        val intent = com.journeyapps.barcodescanner.ScanIntentIntegrator(this).apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("서버 폰의 Wi-Fi QR 코드를 스캔하세요")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }.createScanIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 200)
    }

    private fun handleWifiQr(qrText: String) {
        try {
            if (!qrText.startsWith("WIFI:")) {
                val host = qrText.trim()
                if (host.matches(Regex("^[0-9.]+(:[0-9]+)?$"))) {
                    hostEt.setText(host.substringBefore(":"))
                    requestVpnPermissionAndStart(host.substringBefore(":"))
                    return
                }
                updateStatus("QR 오류", "Wi-Fi QR이 아닙니다.", Color.RED); return
            }
            val ssid = Regex("S:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: return
            val pass = Regex("P:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: ""
            updateStatus("Wi-Fi 연결 중...", "SSID: $ssid", Color.parseColor("#F57C00"))
            connectToWifi(ssid, pass)
        } catch (t: Throwable) {
            updateStatus("QR 오류", t.message ?: "해석 실패", Color.RED)
        }
    }

    private fun connectToWifi(ssid: String, pass: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid)
                    .apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }.build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).setNetworkSpecifier(specifier).build()
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                wifiCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        runOnUiThread {
                            updateStatus("Wi-Fi 연결됨", "IP 확인 중...", Color.parseColor("#2E7D32"))
                            statusDesc.postDelayed({
                                val host = NetUtil.serverHost(this@MainActivity)
                                hostEt.setText(host)
                                requestVpnPermissionAndStart(host)
                            }, 1500)
                        }
                    }
                    override fun onUnavailable() {
                        runOnUiThread { updateStatus("Wi-Fi 실패", "수동 연결 필요", Color.RED) }
                    }
                }
                cm.requestNetwork(request, wifiCallback!!)
            } else {
                updateStatus("수동 연결", "설정에서 연결하세요", Color.parseColor("#F57C00"))
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (t: Throwable) {
            updateStatus("Wi-Fi 오류", t.message ?: "오류", Color.RED)
        }
    }

    private fun startServerMode() {
        if (ServerService.running) {
            LocalHotspot.stop(); stopService(Intent(this, ServerService::class.java))
            updateStatus("대기 중", "서버 중지됨", Color.parseColor("#333333"))
        } else {
            updateStatus("서버 시작 중...", "핫스팟 활성화 중", Color.parseColor("#F57C00"))
            LocalHotspot.start(this) { ok ->
                if (ok) {
                    try {
                        startForegroundService(Intent(this, ServerService::class.java))
                        updateStatus("서버 실행 중", "SSID: ${LocalHotspot.ssid}\nPW: ${LocalHotspot.pass}", Color.parseColor("#1565C0"))
                        showQr()
                    } catch (t: Throwable) { updateStatus("오류", t.message ?: "실패", Color.RED) }
                } else updateStatus("실패", "핫스팟 불가", Color.RED)
            }
        }
        refresh()
    }

    private fun startClientMode() {
        if (isClientMode) {
            startService(Intent(this, VpnClientService::class.java).setAction("stop"))
            isClientMode = false; updateStatus("대기 중", "연결 해제됨", Color.parseColor("#333333"))
        } else {
            hostEt.visibility = View.VISIBLE
            val host = hostEt.text.toString().trim()
            if (host.isEmpty()) { Toast.makeText(this, "IP 입력 필요", Toast.LENGTH_SHORT).show(); return }
            requestVpnPermissionAndStart(host)
        }
        refresh()
    }

    private fun requestVpnPermissionAndStart(host: String) {
        val prep = VpnService.prepare(this)
        if (prep != null) { pendingHost = host; @Suppress("DEPRECATION") startActivityForResult(prep, 100) }
        else startVpn(host)
    }

    private fun startVpn(host: String) {
        try {
            startService(Intent(this, VpnClientService::class.java).putExtra("host", host))
            isClientMode = true; updateStatus("클라이언트 연결됨", "서버 $host", Color.parseColor("#2E7D32"))
        } catch (t: Throwable) { updateStatus("오류", t.message ?: "VPN 실패", Color.RED) }
    }

    private fun updateStatus(title: String, desc: String, color: Int) {
        statusTitle.text = title; statusTitle.setTextColor(color); statusDesc.text = desc
    }

    private fun showQr() {
        if (LocalHotspot.ssid.isEmpty()) { qrView.visibility = View.GONE; return }
        val key = "${LocalHotspot.ssid}|${LocalHotspot.pass}|${LocalHotspot.sec}"
        if (key != lastQr) {
            try { qrView.setImageBitmap(QrHelper.wifi(LocalHotspot.ssid, LocalHotspot.pass, LocalHotspot.sec)); lastQr = key } catch (_: Throwable) {}
        }
        qrView.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == Activity.RESULT_OK) startVpn(pendingHost)
            else updateStatus("권한 거부", "VPN 필요", Color.RED)
        } else if (requestCode == 200) {
            if (resultCode == Activity.RESULT_OK) {
                val contents = data?.getStringExtra("SCAN_RESULT")
                if (contents != null) handleWifiQr(contents)
            }
        }
    }

    private fun runTest(host: String) {
        if (host.isEmpty()) return
        Toast.makeText(this, "테스트 중...", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = "TCP: ${Socks5Test.tcp(host, ServerService.PORT)}\nUDP: ${Socks5Test.udp(host, ServerService.PORT)}"
            runOnUiThread { AlertDialog.Builder(this).setTitle("결과").setMessage(msg).setPositiveButton("확인", null).show() }
        }.start()
    }

    private fun refresh() {
        if (ServerService.running) {
            btnServer.text = "서버 중지"; btnServer.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnServer.text = "서버 시작\n(데이터 폰)"; btnServer.setBackgroundColor(Color.parseColor("#1565C0"))
        }
        if (isClientMode) {
            btnClient.text = "연결 해제"; btnClient.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            btnClient.text = "수동 연결\n(IP 입력)"; btnClient.setBackgroundColor(Color.parseColor("#2E7D32"))
        }
    }
}
EOF
echo -e "${GREEN}[OK] MainActivity.kt 갱신 (레거시 방식)${NC}"

# 7. Git Push
git add .
git commit -m "fix: resolve QR scan theme crash by reverting to legacy Activity & startActivityForResult"
git push origin $(git rev-parse --abbrev-ref HEAD)

echo -e "${GREEN}===========================================${NC}"
echo -e "${GREEN} 완료! GitHub Actions 빌드 확인${NC}"
echo -e "${GREEN}===========================================${NC}"