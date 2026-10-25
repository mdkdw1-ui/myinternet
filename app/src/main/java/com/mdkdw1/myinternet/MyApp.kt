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
                val log = "=== CRASH thread=${thread.name} time=${System.currentTimeMillis()} ===\n" + sw.toString()

                // 앱 전용 외부 저장소 (Termux에서 접근 가능)
                try {
                    val ext = getExternalFilesDir(null) ?: filesDir
                    File(ext, "myinternet_crash.log").appendText(log + "\n\n")
                } catch (_: Exception) {}

                // 내부 저장소 (CrashActivity 표시용)
                try {
                    File(filesDir, "crash.txt").writeText(log)
                } catch (_: Exception) {}
            } catch (_: Exception) {}

            Log.e("MyApp", "Uncaught exception in ${thread.name}", e)

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
