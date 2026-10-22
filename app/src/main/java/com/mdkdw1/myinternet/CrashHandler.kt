package com.mdkdw1.myinternet

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object CrashHandler {
    private lateinit var appContext: Context
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(throwable)
            } catch (_: Exception) {}
            // 앱 재시작 대신 시스템 핸들러 호출 (완전 종료)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun saveCrash(t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val log = "=== CRASH $time ===\n${sw}\n\n"
        val f = crashFile()
        try { f.appendText(log) } catch (_: Exception) {}
        lastCrash = log
    }

    fun crashFile(): File = File(appContext.filesDir, "crash.log")

    @Volatile var lastCrash: String? = null

    fun readCrash(): String? = try {
        val f = crashFile()
        if (f.exists()) f.readText() else null
    } catch (_: Exception) { null }

    fun clearCrash() = try { crashFile().delete() } catch (_: Exception) {}
}
