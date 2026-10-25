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
    }
}
