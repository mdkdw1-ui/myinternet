package com.mdkdw1.myinternet

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 앱이 시작하자마자 죽는 것을 감지하고 파일로 저장
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val file = File(filesDir, "crash.txt")
                file.writeText("=== CRASH ===\n" + sw.toString())
            } catch (_: Exception) {}
            System.exit(2)
        }
        
        // 이전에 크래시가 났던 로그가 있으면 앱이 완전히 켜진 후(500ms 뒤) 강제로 팝업 띄우기
        val crashFile = File(filesDir, "crash.txt")
        if (crashFile.exists()) {
            val errorLog = crashFile.readText()
            crashFile.delete() // 다음 크래시를 위해 삭제
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val intent = android.content.Intent(this, CrashActivity::class.java)
                    intent.putExtra("error", errorLog)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) {}
            }, 1000) // 앱이 메모리에 올라올 때까지 1초 대기
        }
    }
}
