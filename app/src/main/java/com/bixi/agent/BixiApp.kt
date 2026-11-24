package com.bixi.agent

import android.app.Application
import com.bixi.agent.core.Bixi

class BixiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Bixi.init(this)
    }
}
