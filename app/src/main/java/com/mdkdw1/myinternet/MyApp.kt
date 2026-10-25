package com.mdkdw1.myinternet

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val file = File(filesDir, "crash.txt")
                file.writeText("=== CRASH thread=${thread.name} time=${System.currentTimeMillis()} ===\n" + sw.toString())
            } catch (_: Exception) {}
            Log.e("MyApp", "Uncaught exception in ${thread.name}", e)

            // 메인 스레드 크래시: 시스템 핸들러로 위임 (앱 종료)
            // 백그라운드 스레드 크래시: 로그만 남기고 앱 유지
            if (thread === Looper.getMainLooper().thread) {
                defaultHandler?.uncaughtException(thread, e)
            }
        }

        val crashFile = File(filesDir, "crash.txt")
        if (crashFile.exists()) {
            val errorLog = crashFile.readText()
            crashFile.delete()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val intent = android.content.Intent(this, CrashActivity::class.java)
                    intent.putExtra("error", errorLog)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) {}
            }, 1000)
        }
    }
}
