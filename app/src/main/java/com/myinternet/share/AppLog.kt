package com.myinternet.share

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX = 400_000L

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "app.log")
    }

    @Synchronized
    fun log(level: String, tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.println(if (level == "E") android.util.Log.ERROR else android.util.Log.INFO, "MI-$tag", msg)
        val f = file ?: return
        try {
            if (f.length() > MAX) f.writeText(f.readText().takeLast((MAX / 2).toInt()))
            val sb = StringBuilder()
            sb.append(fmt.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
            if (t != null) sb.append(android.util.Log.getStackTraceString(t)).append('\n')
            f.appendText(sb.toString())
        } catch (_: Exception) {
        }
    }

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    fun readFile(ctx: Context, name: String): String = try {
        val f = File(ctx.filesDir, name)
        if (f.exists()) f.readText() else "(없음)"
    } catch (e: Exception) {
        "(읽기 실패: ${e.message})"
    }

    fun readLogcat(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "500"))
        p.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        "(logcat 실패: ${e.message})"
    }

    fun readCrashBuffer(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-b", "crash", "-v", "time", "-t", "200"))
        p.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        "(crash 버퍼 읽기 실패: ${e.message})"
    }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, "app.log").delete() } catch (_: Exception) {}
        try { File(ctx.filesDir, "hev.log").delete() } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("logcat", "-c")) } catch (_: Exception) {}
    }
}
