package com.mdkdw1.myinternet

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val error = intent.getStringExtra("error") ?: "알 수 없는 오류"
        
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = "⚠️ 앱 크래시 발생\n\n$error"
            textSize = 12f
            setPadding(40, 80, 40, 40)
            setTextIsSelectable(true) // 드래그해서 복사 가능하게 함
        }
        scroll.addView(tv)
        setContentView(scroll)
    }
}
