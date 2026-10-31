#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")" || exit 1
[ -d app/src/main ] || { echo "myinternet 폴더에서 실행하세요"; exit 1; }

echo "== 1) build.gradle.kts 패치 =="
python3 - <<'PY'
p = "app/build.gradle.kts"
s = open(p).read()
if "isCoreLibraryDesugaringEnabled" not in s:
    s = s.replace(
        "compileOptions {\n        sourceCompatibility = JavaVersion.VERSION_17\n        targetCompatibility = JavaVersion.VERSION_17\n    }",
        "compileOptions {\n        sourceCompatibility = JavaVersion.VERSION_17\n        targetCompatibility = JavaVersion.VERSION_17\n        isCoreLibraryDesugaringEnabled = true\n    }"
    )
if "com.wireguard.android:tunnel" not in s:
    s = s.replace(
        'implementation("com.journeyapps:zxing-android-embedded:4.3.0")',
        'implementation("com.journeyapps:zxing-android-embedded:4.3.0")\n    implementation("com.wireguard.android:tunnel:1.0.20230706")\n    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")'
    )
open(p, "w").write(s)
PY

echo "== 2) AndroidManifest.xml 패치 =="
python3 - <<'PY'
p = "app/src/main/AndroidManifest.xml"
s = open(p).read()
if "GoBackend$VpnService" not in s:
    s = s.replace(
        "</application>",
        '    <service android:name="com.wireguard.android.backend.GoBackend$VpnService" android:permission="android.permission.BIND_VPN_SERVICE" android:exported="true" />\n    </application>'
    )
open(p, "w").write(s)
PY

echo "== 3) WireGuardService.kt 생성 =="
cat > app/src/main/java/com/myinternet/share/WireGuardService.kt <<'KOTLIN'
package com.myinternet.share

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.File

class WireGuardService : Service() {
    private var backend: Backend? = null
    private var tunnel: Tunnel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "start" -> {
                val confPath = intent.getStringExtra("confPath")
                if (confPath.isNullOrEmpty()) {
                    AppLog.e("wg", "confPath 없음")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startWg(confPath)
            }
            "stop" -> stopWg()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startWg(confPath: String) {
        try {
            val f = File(confPath)
            if (!f.exists()) {
                AppLog.e("wg", "conf 파일 없음: $confPath")
                stopSelf()
                return
            }
            val cfg = Config.parse(f)
            backend = GoBackend(this)
            tunnel = object : Tunnel {
                override fun getName() = "myinternet-wg"
                override fun onStateChange(newState: Tunnel.State) {
                    AppLog.i("wg", "state=$newState")
                }
            }
            backend?.setState(tunnel, Tunnel.State.UP, cfg)
            running = true
            AppLog.i("wg", "WireGuard UP")
        } catch (t: Throwable) {
            AppLog.e("wg", "WireGuard 시작 실패", t)
            running = false
            stopSelf()
        }
    }

    private fun stopWg() {
        try {
            backend?.setState(tunnel, Tunnel.State.DOWN, null)
            AppLog.i("wg", "WireGuard DOWN")
        } catch (t: Throwable) {
            AppLog.e("wg", "WireGuard 중지 실패", t)
        }
        running = false
        stopSelf()
    }

    override fun onDestroy() {
        stopWg()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
    }
}
KOTLIN

echo "== 4) MainActivity.kt 패치 =="
python3 - <<'PY'
p = "app/src/main/java/com/myinternet/share/MainActivity.kt"
s = open(p).read()

# 4-1) 상태 변수 추가 (isClientMode 선언 옆)
if "private var isWgMode = false" not in s:
    s = s.replace(
        "private var isClientMode = false",
        "private var isClientMode = false\n    private var isWgMode = false\n    private var btnWg: Button? = null\n    private var wgConfEt: EditText? = null\n    private var btnWgStop: Button? = null"
    )

# 4-2) UI에 WireGuard 영역 추가 (btnDisconnect 추가 직후)
ui_add = '''
        // ★ WireGuard 클라이언트 영역
        val wgLabel = TextView(this).apply {
            text = "WireGuard client.conf 붙여넣기"; textSize = 13f
            setTextColor(Color.parseColor("#4A148C")); setPadding(0, pad, 0, 4)
        }
        wgConfEt = EditText(this).apply {
            hint = "[Interface]\\nPrivateKey = ...\\nAddress = ...\\n\\n[Peer]\\nPublicKey = ...\\nEndpoint = ...\\nAllowedIPs = ..."
            textSize = 11f; gravity = Gravity.START or Gravity.TOP
            setSingleLine(false); setLines(6)
            setText(NetUtil.loadWgConf(this@MainActivity))
            setPadding(pad, pad, pad, pad)
        }
        val wgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnWg = Button(this).apply {
            text = "WireGuard ON"; textSize = 15f
            setBackgroundColor(Color.parseColor("#6A1B9A")); setTextColor(Color.WHITE)
            setOnClickListener { startWireGuard() }
        }
        btnWgStop = Button(this).apply {
            text = "WireGuard OFF"; textSize = 15f
            setBackgroundColor(Color.parseColor("#4A148C")); setTextColor(Color.WHITE)
            setOnClickListener { stopWireGuard() }
        }
        wgRow.addView(btnWg, LinearLayout.LayoutParams(0, (56 * resources.displayMetrics.density).toInt(), 1f).apply { setMargins(0, 0, 4, 0) })
        wgRow.addView(btnWgStop, LinearLayout.LayoutParams(0, (56 * resources.displayMetrics.density).toInt(), 1f).apply { setMargins(4, 0, 0, 0) })
        root.addView(wgLabel)
        root.addView(wgConfEt, LinearLayout.LayoutParams(-1, (140 * resources.displayMetrics.density).toInt()).apply { setMargins(0, 0, 0, 8) })
        root.addView(wgRow)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })
'''
if "WireGuard client.conf 붙여넣기" not in s:
    s = s.replace(
        'root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })',
        ui_add
    )

# 4-3) WireGuard 시작/중지 메서드 추가 (stopClientSafely 앞)
methods = '''
    private fun startWireGuard() {
        val conf = wgConfEt?.text?.toString()?.trim() ?: ""
        if (conf.isEmpty() || !conf.contains("[Interface]") || !conf.contains("[Peer]")) {
            Toast.makeText(this, "client.conf 전체를 붙여넣으세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (isClientMode) {
            Toast.makeText(this, "myinternet VPN을 먼저 끄세요", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            NetUtil.saveWgConf(this, conf)
            val f = java.io.File(filesDir, "wg.conf")
            val prep = VpnService.prepare(this)
            if (prep != null) {
                pendingHost = "WG"
                @Suppress("DEPRECATION")
                startActivityForResult(prep, 101)
            } else {
                reallyStartWg(f.absolutePath)
            }
        } catch (t: Throwable) {
            AppLog.e("ui", "WireGuard 시작 준비 실패", t)
            Toast.makeText(this, "오류: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun reallyStartWg(confPath: String) {
        try {
            startService(Intent(this, WireGuardService::class.java).setAction("start").putExtra("confPath", confPath))
            isWgMode = true
            updateStatus("WireGuard 연결됨", "client.conf 적용", Color.parseColor("#6A1B9A"))
            refresh()
        } catch (t: Throwable) {
            AppLog.e("ui", "WireGuard 서비스 시작 실패", t)
            Toast.makeText(this, "서비스 시작 실패: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopWireGuard() {
        try {
            startService(Intent(this, WireGuardService::class.java).setAction("stop"))
        } catch (_: Throwable) {}
        isWgMode = false
        updateStatus("대기 중", "WireGuard 해제됨", Color.parseColor("#333333"))
        refresh()
    }
'''
if "private fun startWireGuard()" not in s:
    s = s.replace("    private fun stopClientSafely() {", methods + "\n    private fun stopClientSafely() {")

# 4-4) onActivityResult에 101 분기 추가
if "requestCode == 101" not in s:
    s = s.replace(
        "} else if (requestCode == 100) {",
        """} else if (requestCode == 101) {
            if (resultCode == Activity.RESULT_OK) {
                val f = java.io.File(filesDir, "wg.conf")
                reallyStartWg(f.absolutePath)
            } else updateStatus("권한 거부", "WireGuard VPN 권한 필요", Color.RED)
        } else if (requestCode == 100) {"""
    )

# 4-5) refresh()에서 상호 배제
if "btnWg?.isEnabled" not in s:
    s = s.replace(
        "        if (isClientMode) {",
        """        btnWg?.isEnabled = !isClientMode && !isWgMode
        btnWgStop?.isEnabled = isWgMode
        if (isWgMode) {
            btnClient.isEnabled = false
            btnDisconnect.isEnabled = false
        } else if (!isClientMode) {
            btnClient.isEnabled = true
        }
        if (isClientMode) {"""
    )
open(p, "w").write(s)
PY

echo "== 5) NetUtil.kt에 save/load 메서드 추가 =="
python3 - <<'PY'
p = "app/src/main/java/com/myinternet/share/NetUtil.kt"
s = open(p).read()
if "fun saveWgConf" not in s:
    s = s.replace(
        "    fun serverHost(ctx: Context): String {",
        '''    fun saveWgConf(ctx: Context, conf: String) {
        try { java.io.File(ctx.filesDir, "wg.conf").writeText(conf) } catch (_: Throwable) {}
    }

    fun loadWgConf(ctx: Context): String = try {
        val f = java.io.File(ctx.filesDir, "wg.conf")
        if (f.exists()) f.readText() else ""
    } catch (_: Throwable) { "" }

    fun serverHost(ctx: Context): String {'''
    )
open(p, "w").write(s)
PY

echo "== 완료. git diff 로 확인 후 push =="
git --no-pager diff --stat