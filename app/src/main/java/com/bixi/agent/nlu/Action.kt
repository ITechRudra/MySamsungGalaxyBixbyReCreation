package com.bixi.agent.nlu

/** Direction the *finger* moves. "Scroll down" / "next reel" = finger moves [UP]. */
enum class Dir { UP, DOWN, LEFT, RIGHT }

enum class Distance { SMALL, NORMAL, LARGE }

enum class GlobalKind { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK, SCREENSHOT, POWER, SPLIT }

enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }

enum class Messenger { WHATSAPP, SMS, DEFAULT }

enum class InfoKind { TIME, DATE, BATTERY }

enum class Spot { CENTER, TOP, BOTTOM, LEFT, RIGHT }

enum class TextOp { SELECT_ALL, COPY, PASTE, CUT, CLEAR }

enum class Chat { GREET, THANKS, IDENTITY }

enum class Level { UP, DOWN, SET, MUTE, UNMUTE }

enum class Feature(val spoken: String, vararg val words: String) {
    WIFI("Wi-Fi", "wifi", "wi-fi", "wireless"),
    BLUETOOTH("Bluetooth", "bluetooth", "bt"),
    MOBILE_DATA("Mobile data", "mobile data", "data", "cellular data", "cellular", "internet", "net", "data connection"),
    AIRPLANE("Airplane mode", "airplane mode", "aeroplane mode", "flight mode", "airplane", "aeroplane", "flight"),
    FLASHLIGHT("Flashlight", "flashlight", "torch", "flash", "torchlight", "torch light"),
    DND("Do not disturb", "do not disturb", "dnd", "don't disturb", "dont disturb"),
    HOTSPOT("Hotspot", "hotspot", "mobile hotspot", "wifi hotspot", "tethering"),
    LOCATION("Location", "location", "gps", "location services"),
    AUTO_ROTATE("Auto rotate", "auto rotate", "autorotate", "auto-rotate", "rotation", "screen rotation", "auto rotation"),
    POWER_SAVING("Power saving", "power saving", "power saver", "battery saver", "battery saving", "power saving mode"),
    DARK_MODE("Dark mode", "dark mode", "night mode", "dark theme"),
    NFC("NFC", "nfc"),
    VIBRATE("Vibrate mode", "vibrate", "vibration", "vibrate mode"),
    SILENT("Silent mode", "silent", "silent mode", "mute mode"),
    EYE_COMFORT("Eye comfort shield", "eye comfort", "eye comfort shield", "blue light filter", "night light");

    companion object {
        fun lookup(raw: String): Feature? {
            val s = raw.lowercase().trim()
                .removePrefix("the ").removePrefix("my ")
                .removeSuffix(" mode").trim()
            if (s.isEmpty()) return null
            return entries.firstOrNull { f -> f.words.any { it == s || it.removeSuffix(" mode") == s } }
        }
    }
}

enum class SettingsPage {
    WIFI, BLUETOOTH, DISPLAY, SOUND, BATTERY, STORAGE, APPS, LOCATION, SECURITY,
    ACCESSIBILITY, DEVELOPER, DATE, LANGUAGE, NETWORK, DATA_USAGE, NFC, GENERAL;

    companion object {
        private val map = mapOf(
            "wifi" to WIFI, "wi-fi" to WIFI, "bluetooth" to BLUETOOTH, "display" to DISPLAY,
            "screen" to DISPLAY, "brightness" to DISPLAY, "sound" to SOUND, "sounds" to SOUND,
            "volume" to SOUND, "battery" to BATTERY, "storage" to STORAGE, "app" to APPS,
            "apps" to APPS, "application" to APPS, "applications" to APPS, "location" to LOCATION,
            "gps" to LOCATION, "security" to SECURITY, "privacy" to SECURITY, "lock screen" to SECURITY,
            "accessibility" to ACCESSIBILITY, "developer" to DEVELOPER, "developer options" to DEVELOPER,
            "date and time" to DATE, "date" to DATE, "time" to DATE, "language" to LANGUAGE,
            "keyboard" to LANGUAGE, "network" to NETWORK, "connections" to NETWORK,
            "connection" to NETWORK, "mobile network" to NETWORK, "sim" to NETWORK,
            "data usage" to DATA_USAGE, "data" to DATA_USAGE, "nfc" to NFC, "phone" to GENERAL,
            "system" to GENERAL, "general" to GENERAL, "device" to GENERAL,
        )

        fun lookup(raw: String): SettingsPage? = map[raw.lowercase().trim()]
    }
}

/** Everything Bixi knows how to do. Produced by [CommandParser], run by the Executor. */
sealed interface Action {
    /** Must act on the app *behind* Bixi's own UI (so Bixi has to get out of the way first). */
    val needsScreen: Boolean get() = false

    /** Talks to / pays / posts for another person — confirm first if the user asked for that. */
    val sensitive: Boolean get() = false

    /** Pure conversation: answered by Bixi itself, nothing on screen changes. */
    val conversational: Boolean get() = false

    // ---- navigation & apps
    data class Global(val kind: GlobalKind) : Action {
        override val needsScreen get() = kind == GlobalKind.BACK || kind == GlobalKind.SCREENSHOT || kind == GlobalKind.SPLIT
    }
    data class OpenApp(val name: String) : Action
    data class OpenSection(val app: String, val section: String) : Action
    data class CloseApp(val name: String?) : Action
    data object CloseAllApps : Action
    data class Uninstall(val name: String) : Action
    data class OpenSettings(val page: SettingsPage) : Action

    // ---- scrolling
    data class Swipe(val dir: Dir, val times: Int = 1, val distance: Distance = Distance.NORMAL) : Action {
        override val needsScreen get() = true
    }
    data class ScrollToEdge(val top: Boolean) : Action {
        override val needsScreen get() = true
    }
    data class AutoScroll(val seconds: Int?, val like: Boolean) : Action {
        override val needsScreen get() = true
    }
    data object Stop : Action

    // ---- social media
    data class Like(val undo: Boolean = false) : Action {
        override val needsScreen get() = true
    }
    data object Dislike : Action {
        override val needsScreen get() = true
    }
    data class Share(val to: String? = null, val via: String? = null) : Action {
        override val needsScreen get() = true
        override val sensitive get() = to != null
    }
    data class Comment(val text: String?) : Action {
        override val needsScreen get() = true
        override val sensitive get() = text != null
    }
    data class Follow(val undo: Boolean = false) : Action {
        override val needsScreen get() = true
    }
    data object Save : Action {
        override val needsScreen get() = true
    }

    // ---- generic screen control
    data class Tap(val target: String, val long: Boolean = false) : Action {
        override val needsScreen get() = true
    }
    data class TapPoint(val spot: Spot, val double: Boolean = false) : Action {
        override val needsScreen get() = true
    }
    /** "tap the first result" → 1, "the last one" → -1 */
    data class TapNth(val n: Int) : Action {
        override val needsScreen get() = true
    }
    data class Type(val text: String) : Action {
        override val needsScreen get() = true
    }
    data class Submit(val label: String?) : Action {
        override val needsScreen get() = true
    }
    data class EditText(val op: TextOp) : Action {
        override val needsScreen get() = true
    }
    data class FindOnScreen(val text: String) : Action {
        override val needsScreen get() = true
    }
    data object ReadScreen : Action {
        override val needsScreen get() = true
    }
    data object ReadNotifications : Action

    // ---- people
    data class Call(val who: String, val whatsapp: Boolean = false, val video: Boolean = false) : Action {
        override val sensitive get() = true
    }
    data class SendMessage(val who: String, val message: String, val app: Messenger = Messenger.DEFAULT) : Action {
        override val sensitive get() = true
    }
    data class OpenChat(val who: String, val app: Messenger = Messenger.DEFAULT) : Action
    data class Pay(val payee: String?, val amount: String?, val app: String?) : Action {
        override val sensitive get() = true
    }

    // ---- media & device
    data class Media(val key: MediaKey) : Action
    data class PlayMedia(val query: String, val app: String?) : Action
    data class Volume(val level: Level, val percent: Int? = null) : Action
    data class Brightness(val level: Level, val percent: Int? = null) : Action
    data class Toggle(val feature: Feature, val on: Boolean?) : Action
    data class Search(val query: String, val where: String?) : Action
    data class Navigate(val place: String, val mode: String?) : Action
    data class ShowMap(val place: String) : Action
    data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : Action
    data class SetTimer(val seconds: Int, val label: String?) : Action
    data class Camera(val selfie: Boolean, val video: Boolean) : Action

    // ---- Bixi itself
    data class Info(val kind: InfoKind) : Action {
        override val conversational get() = true
    }
    data class Talk(val kind: Chat) : Action {
        override val conversational get() = true
    }
    data object Help : Action
    data object Repeat : Action
    data class Teach(val phrase: String, val commands: String) : Action {
        override val conversational get() = true
    }
    data class Bubble(val show: Boolean) : Action
    data class Wait(val seconds: Int) : Action
    data class Unknown(val text: String) : Action
}

/** One-line human description, used for "are you sure?" prompts. */
fun Action.describe(): String = when (this) {
    is Action.SendMessage -> {
        val via = when (app) {
            Messenger.WHATSAPP -> " on WhatsApp"
            Messenger.SMS -> " by SMS"
            Messenger.DEFAULT -> ""
        }
        "Send \"$message\" to $who$via"
    }
    is Action.Call -> when {
        video -> "Video call $who on WhatsApp"
        whatsapp -> "WhatsApp call $who"
        else -> "Call $who"
    }
    is Action.Comment -> "Post the comment \"${text.orEmpty()}\""
    is Action.Share -> "Share this with ${to.orEmpty()}" + (via?.let { " on $it" } ?: "")
    is Action.Pay -> buildString {
        append("Open a payment")
        amount?.let { append(" of ₹$it") }
        payee?.let { append(" to $it") }
        app?.let { append(" in $it") }
        append(" (you'll enter the PIN yourself)")
    }
    else -> toString()
}
