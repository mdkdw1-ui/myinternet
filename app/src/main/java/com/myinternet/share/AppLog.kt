package com.myinternet.share

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private const val MAX = 400_000L
    
    // ★ 버그 수정: 파일 I/O를 메인 스레드가 아닌 백그라운드에서 처리
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLog-IO").apply { isDaemon = true }
    }

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "app.log")
    }

    fun log(level: String, tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.println(if (level == "E") android.util.Log.ERROR else android.util.Log.INFO, "MI-$tag", msg)
        val f = file ?: return
        val timestamp = fmt.format(Date())
        val trace = if (t != null) android.util.Log.getStackTraceString(t) else null
        
        // ★ 백그라운드에서 비동기로 쓰기 (UI 블로킹 방지)
        ioExecutor.execute {
            try {
                if (f.length() > MAX) {
                    val txt = f.readText()
                    f.writeText(txt.takeLast((MAX / 2).toInt()))
                }
                val sb = StringBuilder()
                sb.append(timestamp).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
                if (trace != null) sb.append(trace).append('\n')
                f.appendText(sb.toString())
            } catch (_: Exception) {}
        }
    }

    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    fun readFile(ctx: Context, name: String): String = try {
        val f = File(ctx.filesDir, name)
        if (f.exists()) f.readText() else "(없음)"
    } catch (e: Exception) { "(읽기 실패: ${e.message})" }

    // ★ logcat 실행 시 stderr도 함께 소비 + 타임아웃
    fun readLogcat(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "500"))
        // stderr를 별도 스레드에서 소비 (버퍼 차서 멈추는 현상 방지)
        Thread { try { p.errorStream.bufferedReader().readText() } catch (_: Exception) {} }.start()
        p.inputStream.bufferedReader().readText()
    } catch (e: Exception) { "(logcat 실패: ${e.message})" }

    fun readCrashBuffer(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-b", "crash", "-v", "time", "-t", "200"))
        Thread { try { p.errorStream.bufferedReader().readText() } catch (_: Exception) {} }.start()
        p.inputStream.bufferedReader().readText()
    } catch (e: Exception) { "(crash 버퍼 읽기 실패: ${e.message})" }

    fun clear(ctx: Context) {
        ioExecutor.execute {
            try { File(ctx.filesDir, "app.log").delete() } catch (_: Exception) {}
            try { File(ctx.filesDir, "hev.log").delete() } catch (_: Exception) {}
            try { 
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
                p.waitFor()
            } catch (_: Exception) {}
        }
    }
}
