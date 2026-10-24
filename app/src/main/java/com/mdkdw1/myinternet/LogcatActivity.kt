package com.mdkdw1.myinternet

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader

class LogcatActivity : Activity() {
    private lateinit var logText: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val buttonBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refreshBtn = Button(this).apply {
            text = "새로고침"
            setOnClickListener { loadLogs() }
        }
        val clearBtn = Button(this).apply {
            text = "지우기"
            setOnClickListener { logText.text = "" }
        }
        val closeBtn = Button(this).apply {
            text = "닫기"
            setOnClickListener { finish() }
        }
        buttonBar.addView(refreshBtn)
        buttonBar.addView(clearBtn)
        buttonBar.addView(closeBtn)
        root.addView(buttonBar)

        val scroll = ScrollView(this)
        logText = TextView(this).apply {
            textSize = 10f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }
        scroll.addView(logText)
        root.addView(scroll)

        setContentView(root)
        loadLogs()
    }

    private fun loadLogs() {
        logText.text = "로딩 중..."
        Thread {
            try {
                val pid = android.os.Process.myPid()
                // PID로 필터: 이 앱 프로세스의 모든 로그를 잡음
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "brief", "--pid=$pid"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val sb = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append("\n")
                }
                val result = sb.toString()
                handler.post {
                    if (result.isNotBlank()) logText.text = result
                    else logText.text = "로그가 없습니다. (새로고침을 눌러 다시 시도)"
                }
            } catch (e: Exception) {
                handler.post { logText.text = "로그 읽기 실패: ${e.message}" }
            }
        }.start()
    }
}
