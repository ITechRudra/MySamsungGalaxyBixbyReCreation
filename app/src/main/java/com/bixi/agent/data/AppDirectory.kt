package com.bixi.agent.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import com.bixi.agent.nlu.Fuzzy

/** Every launchable app on the phone, searchable by spoken name. */
object AppDirectory {
    data class App(val label: String, val pkg: String)
    data class Match(val app: App, val score: Double)

    @Volatile private var cache: List<App> = emptyList()
    @Volatile private var loadedAt = 0L

    /** Spoken nicknames → canonical name. */
    private val aliases = mapOf(
        "insta" to "instagram", "ig" to "instagram", "yt" to "youtube", "fb" to "facebook",
        "wa" to "whatsapp", "whatsapp business" to "whatsapp business", "gpay" to "google pay",
        "g pay" to "google pay", "phone pe" to "phonepe", "sms" to "messages", "message" to "messages",
        "text messages" to "messages", "dialer" to "phone", "dialler" to "phone", "calls" to "phone",
        "photos" to "gallery", "pictures" to "gallery", "browser" to "internet", "samsung internet" to "internet",
        "playstore" to "play store", "store" to "play store", "google play" to "play store",
        "notes" to "samsung notes", "files" to "my files", "file manager" to "my files",
        "twitter" to "x", "snap" to "snapchat", "tg" to "telegram", "hotstar" to "jiohotstar",
        "disney hotstar" to "jiohotstar", "prime" to "prime video", "amazon prime" to "prime video",
        "yt music" to "youtube music", "maps" to "maps", "google maps" to "maps", "mail" to "gmail",
        "email" to "gmail", "calc" to "calculator", "alarm" to "clock", "alarms" to "clock",
        "sbi" to "yono sbi", "yono" to "yono sbi", "galaxy store" to "galaxy store", "bixi" to "bixi",
        "assistant" to "bixi",
    )

    /** Canonical name → package(s), tried in order (works even if the label is unusual). */
    private val packages = mapOf(
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "whatsapp business" to listOf("com.whatsapp.w4b"),
        "instagram" to listOf("com.instagram.android"),
        "youtube" to listOf("com.google.android.youtube"),
        "youtube music" to listOf("com.google.android.apps.youtube.music"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "messenger" to listOf("com.facebook.orca"),
        "snapchat" to listOf("com.snapchat.android"),
        "telegram" to listOf("org.telegram.messenger"),
        "x" to listOf("com.twitter.android"),
        "threads" to listOf("com.instagram.barcelona"),
        "spotify" to listOf("com.spotify.music"),
        "google pay" to listOf("com.google.android.apps.nbu.paisa.user"),
        "phonepe" to listOf("com.phonepe.app"),
        "paytm" to listOf("net.one97.paytm"),
        "yono sbi" to listOf("com.sbi.lotusintouch"),
        "camera" to listOf("com.sec.android.app.camera", "com.google.android.GoogleCamera"),
        "gallery" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
        "settings" to listOf("com.android.settings"),
        "phone" to listOf("com.samsung.android.dialer", "com.google.android.dialer"),
        "messages" to listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"),
        "contacts" to listOf("com.samsung.android.app.contacts", "com.google.android.contacts"),
        "clock" to listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"),
        "calculator" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
        "calendar" to listOf("com.samsung.android.calendar", "com.google.android.calendar"),
        "my files" to listOf("com.sec.android.app.myfiles", "com.google.android.apps.nbu.files"),
        "samsung notes" to listOf("com.samsung.android.app.notes"),
        "play store" to listOf("com.android.vending"),
        "galaxy store" to listOf("com.sec.android.app.samsungapps"),
        "chrome" to listOf("com.android.chrome"),
        "internet" to listOf("com.sec.android.app.sbrowser", "com.android.chrome"),
        "maps" to listOf("com.google.android.apps.maps"),
        "gmail" to listOf("com.google.android.gm"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "prime video" to listOf("com.amazon.avod.thirdpartyclient"),
        "amazon" to listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"),
        "flipkart" to listOf("com.flipkart.android"),
        "zomato" to listOf("com.application.zomato"),
        "swiggy" to listOf("in.swiggy.android"),
        "bixi" to listOf("com.bixi.agent"),
    )

    fun all(context: Context): List<App> {
        val now = SystemClock.elapsedRealtime()
        if (cache.isNotEmpty() && now - loadedAt < 60_000) return cache
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            App(ri.loadLabel(pm).toString(), pkg)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        cache = list
        loadedAt = now
        return list
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun labelFor(context: Context, pkg: String): String =
        all(context).firstOrNull { it.pkg == pkg }?.label ?: pkg.substringAfterLast('.')

    /** Best app for a spoken name, or null if nothing is a confident match. */
    fun find(context: Context, spoken: String, minScore: Double = 0.72): Match? {
        val q = Fuzzy.norm(spoken).removeSuffix(" app").removePrefix("the ").trim()
        if (q.isEmpty()) return null
        val canonical = aliases[q] ?: q
        packages[canonical]?.firstOrNull { isInstalled(context, it) }?.let { pkg ->
            return Match(App(labelFor(context, pkg), pkg), 1.0)
        }
        val apps = all(context)
        val best = apps.map { Match(it, maxOf(Fuzzy.score(canonical, it.label), Fuzzy.score(q, it.label))) }
            .maxByOrNull { it.score } ?: return null
        return best.takeIf { it.score >= minScore }
    }

    /** "instagram reels" → (Instagram, "reels"); "whatsapp settings" → (WhatsApp, "settings"). */
    fun splitAppAndSection(context: Context, spoken: String): Pair<Match, String>? {
        val words = Fuzzy.norm(spoken).split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return null
        // "settings in whatsapp" / "reels on instagram"
        val inIdx = words.indexOfFirst { it == "in" || it == "on" }
        if (inIdx in 1 until words.size - 1) {
            val app = find(context, words.drop(inIdx + 1).joinToString(" "), 0.8)
            if (app != null) return app to words.take(inIdx).joinToString(" ")
        }
        for (i in words.size - 1 downTo 1) {
            val app = find(context, words.take(i).joinToString(" "), 0.8) ?: continue
            return app to words.drop(i).joinToString(" ")
        }
        return null
    }

    /** Preferred UPI app: the one named, else the first installed of GPay / PhonePe / Paytm / BHIM. */
    fun paymentApp(context: Context, named: String?): Match? {
        if (named != null && named !in setOf("upi")) find(context, named)?.let { return it }
        for (name in listOf("google pay", "phonepe", "paytm")) {
            find(context, name)?.let { return it }
        }
        return find(context, "bhim")
    }
}
