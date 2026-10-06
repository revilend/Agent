package com.revilend.ai.assistant

import android.app.Application

class MainApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MainApp
            private set
    }
}
