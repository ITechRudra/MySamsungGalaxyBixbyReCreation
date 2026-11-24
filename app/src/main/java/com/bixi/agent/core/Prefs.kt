package com.bixi.agent.core

import android.content.Context
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("bixi_prefs", Context.MODE_PRIVATE)

    /** Say replies out loud. */
    var speakReplies by bool("speak_replies", true)

    /** Ask before messaging, calling, commenting, sharing to someone or paying. */
    var confirmSensitive by bool("confirm_sensitive", true)

    /** Show the floating robot over other apps. */
    var showBubble by bool("show_bubble", true)

    /** Ask the speech recogniser to stay on-device. */
    var preferOffline by bool("prefer_offline", true)

    /** en-IN understands Indian names and Hinglish best. */
    var speechLanguage by string("speech_language", "en-IN")

    var autoScrollSeconds by int("auto_scroll_seconds", 8)

    /** Added to 10-digit numbers for WhatsApp links. */
    var countryCode by string("country_code", "91")

    /** "whatsapp" or "sms" — used when you just say "message Mom saying hi". */
    var defaultMessenger by string("default_messenger", "whatsapp")

    var bubbleOnRight by bool("bubble_right", true)
    var bubbleY by int("bubble_y", -1)

    var lastCommand by string("last_command", "")
    var onboarded by bool("onboarded", false)

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    private fun string(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }
}
