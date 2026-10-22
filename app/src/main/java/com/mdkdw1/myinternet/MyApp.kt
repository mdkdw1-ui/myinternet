package com.mdkdw1.myinternet

import android.app.Application

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
    }
}
