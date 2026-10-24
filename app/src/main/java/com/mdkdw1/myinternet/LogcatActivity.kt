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
    private var isRunning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val buttonBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clearBtn = Button(this).apply {
            text = "지우기"
            setOnClickListener { logText.text = "" }
        }
        val closeBtn = Button(this).apply {
            text = "닫기"
            setOnClickListener { finish() }
        }
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

        Thread {
            try {
                val process = Runtime.getRuntime().exec("logcat -d -v brief")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val sb = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.contains("myinternet") || line.contains("AndroidRuntime") || line.contains("System.err")) {
                        sb.append(line).append("\n")
                    }
                }
                val result = sb.toString()
                handler.post {
                    if (result.isNotEmpty()) logText.text = result
                    else logText.text = "로그가 없습니다."
                }
            } catch (e: Exception) {
                handler.post { logText.text = "로그 읽기 실패: ${e.message}" }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
