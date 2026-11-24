package com.bixi.agent.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Bixi's voice (Android text-to-speech, works offline with the downloaded voice). */
object Speaker : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var counter = 0

    fun init(context: Context) {
        if (tts == null) tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        val india = Locale("en", "IN")
        engine.language = if (engine.isLanguageAvailable(india) >= TextToSpeech.LANG_AVAILABLE) india else Locale.US
        engine.setSpeechRate(1.05f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (AgentBus.mood.value != RobotMood.LISTENING) AgentBus.mood.value = RobotMood.SPEAKING
            }

            override fun onDone(utteranceId: String?) {
                if (AgentBus.mood.value == RobotMood.SPEAKING) AgentBus.mood.value = RobotMood.IDLE
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (AgentBus.mood.value == RobotMood.SPEAKING) AgentBus.mood.value = RobotMood.IDLE
            }
        })
        pending?.let { speak(it) }
        pending = null
    }

    fun speak(text: String) {
        if (text.isBlank() || !Bixi.prefs.speakReplies) return
        val engine = tts
        if (engine == null || !ready) {
            pending = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bixi-${counter++}")
    }

    fun stop() {
        tts?.stop()
        if (AgentBus.mood.value == RobotMood.SPEAKING) AgentBus.mood.value = RobotMood.IDLE
    }
}
