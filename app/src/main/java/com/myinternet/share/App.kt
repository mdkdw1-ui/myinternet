package com.myinternet.share

import android.app.Application
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            AppLog.e("CRASH", "thread=${th.name}", ex)
            prev?.uncaughtException(th, ex)
        }
        AppLog.i("app", "시작 pid=${android.os.Process.myPid()} ${Build.MANUFACTURER} ${Build.MODEL} SDK=${Build.VERSION.SDK_INT}")
        try {
            Class.forName("hev.htproxy.TProxyService")
            AppLog.i("app", "hev 네이티브 라이브러리 로드 OK")
        } catch (t: Throwable) {
            AppLog.e("app", "hev 네이티브 라이브러리 로드 실패", t)
        }
    }
}
