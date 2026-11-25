package com.yuka.cutebrowser

import android.app.Application

class UmuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
