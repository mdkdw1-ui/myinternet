package com.myinternet.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LogActivity : Activity() {
    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView
    private var text = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (8 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) {
            row.addView(
                Button(this).apply { this.text = t; setOnClickListener { f() } },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        btn("새로고침") { load() }
        btn("복사") { copy() }
        btn("공유") { share() }
        btn("지우기") { AppLog.clear(this); load() }
        root.addView(row)

        tv = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(tv) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        load()
    }

    private fun load() {
        tv.text = "불러오는 중..."
        Thread {
            val sb = StringBuilder()
            sb.append("=== app.log ===\n").append(AppLog.readFile(this, "app.log"))
            sb.append("\n=== hev.log ===\n").append(AppLog.readFile(this, "hev.log"))
            sb.append("\n=== crash 버퍼 ===\n").append(AppLog.readCrashBuffer())
            sb.append("\n=== logcat (이 앱) ===\n").append(AppLog.readLogcat())
            text = sb.toString().takeLast(200_000)
            runOnUiThread {
                tv.text = text
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("myinternet log", text))
        Toast.makeText(this, "로그를 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, "로그 공유"))
    }
}
