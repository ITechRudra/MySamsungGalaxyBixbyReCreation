package com.bixi.agent.core

import android.app.Application
import com.bixi.agent.data.Routines

/** App-wide singletons. */
object Bixi {
    lateinit var app: Application
        private set
    lateinit var prefs: Prefs
        private set

    fun init(application: Application) {
        app = application
        prefs = Prefs(application)
        AgentBus.bubbleVisible.value = prefs.showBubble
        Speaker.init(application)
        Torch.init(application)
        Routines.seedIfEmpty(application)
    }
}
