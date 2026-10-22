package com.instarec

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                    .putString("last", Log.getStackTraceString(error)).commit()
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }
}
