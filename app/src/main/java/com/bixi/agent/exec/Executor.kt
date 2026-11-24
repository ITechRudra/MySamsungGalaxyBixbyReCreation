package com.bixi.agent.exec

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bixi.agent.core.Agent
import com.bixi.agent.core.AgentBus
import com.bixi.agent.core.Bixi
import com.bixi.agent.core.RobotMood
import com.bixi.agent.core.Torch
import com.bixi.agent.data.AppDirectory
import com.bixi.agent.data.ContactDirectory
import com.bixi.agent.data.Routines
import com.bixi.agent.nlu.Action
import com.bixi.agent.nlu.Chat
import com.bixi.agent.nlu.Dir
import com.bixi.agent.nlu.Distance
import com.bixi.agent.nlu.Feature
import com.bixi.agent.nlu.GlobalKind
import com.bixi.agent.nlu.InfoKind
import com.bixi.agent.nlu.Level
import com.bixi.agent.nlu.MediaKey
import com.bixi.agent.nlu.Messenger
import com.bixi.agent.nlu.SettingsPage
import com.bixi.agent.nlu.Spot
import com.bixi.agent.nlu.TextOp
import com.bixi.agent.nlu.TimeParser
import com.bixi.agent.service.BixiAccessibilityService
import com.bixi.agent.service.Screen
import com.bixi.agent.ui.MainActivity
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class Outcome(val ok: Boolean, val text: String, val speak: String = text, val mood: RobotMood? = null) {
    companion object {
        fun ok(text: String, speak: String = text, mood: RobotMood? = null) = Outcome(true, text, speak, mood)

        /** Success that is shown but not spoken (e.g. every single scroll). */
        fun quiet(text: String) = Outcome(true, text, "")

        fun fail(text: String, speak: String = text) = Outcome(false, text, speak)
    }
}

/** Turns each [Action] into real taps, swipes and Android intents. */
class Executor(private val ctx: Context) {

    private val svc: BixiAccessibilityService? get() = BixiAccessibilityService.instance
    private val prefs get() = Bixi.prefs

    private fun needAccess() = Outcome.fail(
        "I need Accessibility access for that. Open the Bixi app and switch me on.",
        "Please turn on my accessibility service first.",
    )

    suspend fun run(a: Action): Outcome = when (a) {
        is Action.OpenApp -> openApp(a.name)
        is Action.OpenSection -> openSection(a.app, a.section)
        is Action.CloseApp -> global(GlobalKind.HOME, "Closed.")
        Action.CloseAllApps -> closeAllApps()
        is Action.Uninstall -> uninstall(a.name)
        is Action.OpenSettings -> openSettings(a.page)
        is Action.Global -> global(a.kind)
        is Action.Swipe -> swipe(a)
        is Action.ScrollToEdge -> scrollToEdge(a.top)
        is Action.AutoScroll -> autoScroll(a)
        Action.Stop -> {
            Agent.stopAll()
            Outcome.ok("Stopped.")
        }
        is Action.Like -> like(a.undo)
        Action.Dislike -> dislike()
        is Action.Share -> share(a.to, a.via)
        is Action.Comment -> comment(a.text)
        is Action.Follow -> follow(a.undo)
        Action.Save -> save()
        is Action.Tap -> tap(a.target, a.long)
        is Action.TapPoint -> tapPoint(a.spot, a.double)
        is Action.TapNth -> tapNth(a.n)
        is Action.Type -> type(a.text)
        is Action.Submit -> submit(a.label)
        is Action.EditText -> editText(a.op)
        is Action.FindOnScreen -> findOnScreen(a.text)
        Action.ReadScreen -> readScreen()
        Action.ReadNotifications -> readNotifications()
        is Action.Call -> call(a.who, a.whatsapp, a.video)
        is Action.SendMessage -> sendMessage(a.who, a.message, a.app)
        is Action.OpenChat -> openChat(a.who, a.app)
        is Action.Pay -> pay(a.payee, a.amount, a.app)
        is Action.Media -> media(a.key)
        is Action.PlayMedia -> playMedia(a.query, a.app)
        is Action.Volume -> volume(a.level, a.percent)
        is Action.Brightness -> brightness(a.level, a.percent)
        is Action.Toggle -> toggle(a.feature, a.on)
        is Action.Search -> search(a.query, a.where)
        is Action.Navigate -> navigate(a.place, a.mode)
        is Action.ShowMap -> showMap(a.place)
        is Action.SetAlarm -> setAlarm(a.hour, a.minute, a.label)
        is Action.SetTimer -> setTimer(a.seconds, a.label)
        is Action.Camera -> camera(a.selfie, a.video)
        is Action.Info -> info(a.kind)
        is Action.Talk -> talk(a.kind)
        Action.Help -> help()
        Action.Repeat -> Outcome.fail("There's nothing to repeat yet.")
        is Action.Teach -> teach(a.phrase, a.commands)
        is Action.Bubble -> bubble(a.show)
        is Action.Wait -> {
            delay(a.seconds * 1000L)
            Outcome.quiet("")
        }
        is Action.Unknown -> unknown(a.text)
    }

    // ================================================================== helpers

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = svc ?: ctx
        return try {
            c.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun launchPackage(pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(i)
    }

    private fun whatsappPackage(): String? =
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { AppDirectory.isInstalled(ctx, it) }

    private val homePackage: String? by lazy {
        ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }

    /** Apps where tapping the middle of a reel pauses it and double-tap likes it. */
    private val reelApps = setOf(
        "com.instagram.android", "com.facebook.katana", "com.facebook.lite", "com.snapchat.android",
        "in.mohalla.video", "com.eterno.shortvideos", "com.next.innovation.takatak", "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill", "in.mohalla.sharechat", "com.roposo.android", "com.instagram.barcelona",
        "com.google.android.youtube",
    )

    private fun capitalised(s: String) = s.replaceFirstChar { it.uppercase() }

    private fun Screen.social(descMatch: (String) -> Boolean, exactText: Set<String>): List<AccessibilityNodeInfo> =
        collect(roots(includeSystem = false), limit = 40) { n ->
            if (!isOnScreen(n)) return@collect false
            val d = desc(n).lowercase()
            val t = text(n).lowercase()
            (d.isNotEmpty() && d.length <= 90 && descMatch(d)) || t in exactText
        }

    private suspend fun waitForPackage(screen: Screen, pkg: String, timeout: Long = 6000): Boolean =
        screen.waitFor(timeout, 200) { screen.foregroundPackage()?.takeIf { it == pkg } } != null

    // ================================================================== apps

    private suspend fun openApp(name: String): Outcome {
        AppDirectory.find(ctx, name)?.let { m ->
            if (m.app.pkg == ctx.packageName) {
                start(Intent(ctx, MainActivity::class.java))
                return Outcome.ok("I'm right here!", mood = RobotMood.HAPPY)
            }
            return if (launchPackage(m.app.pkg)) Outcome.ok("Opening ${m.app.label}.") else Outcome.fail("I couldn't open ${m.app.label}.")
        }
        AppDirectory.splitAppAndSection(ctx, name)?.let { (m, section) -> return openSection(m, section) }
        svc?.let { s ->
            val screen = Screen(s)
            screen.findBestText(name, 0.82)?.let { node ->
                screen.click(node)
                return Outcome.ok("Tapped \"$name\".")
            }
        }
        return Outcome.fail("I couldn't find an app called \"$name\".")
    }

    private suspend fun openSection(appName: String, section: String): Outcome {
        val m = AppDirectory.find(ctx, appName) ?: return Outcome.fail("I couldn't find $appName on your phone.")
        return openSection(m, section)
    }

    private suspend fun openSection(m: AppDirectory.Match, section: String): Outcome {
        if (!launchPackage(m.app.pkg)) return Outcome.fail("I couldn't open ${m.app.label}.")
        val s = svc ?: return Outcome.ok("Opened ${m.app.label}. Turn on my accessibility service so I can go to $section.")
        val screen = Screen(s)
        val q = section.lowercase().trim()
        val node = screen.waitFor(8000, 250) {
            if (screen.foregroundPackage() != m.app.pkg) {
                null
            } else {
                screen.findByLabel(screen.roots(false)) { l -> l == q || l.startsWith("$q ") || l.startsWith("$q,") || l == "$q tab" }
                    .firstOrNull() ?: screen.findBestText(section, 0.86, screen.roots(false))
            }
        }
        return if (node != null && screen.click(node)) {
            Outcome.ok("Opening ${capitalised(section)} in ${m.app.label}.")
        } else {
            Outcome.ok("Opened ${m.app.label}, but I couldn't find \"$section\" there.")
        }
    }

    private suspend fun closeAllApps(): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        screen.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
        val btn = screen.waitFor(2500) {
            screen.findByLabel { it == "close all" || it == "clear all" || it.startsWith("close all") }.firstOrNull()
        } ?: return Outcome.ok("Opened recent apps. There was nothing to close.")
        screen.click(btn)
        return Outcome.ok("Closed all apps.")
    }

    private suspend fun uninstall(name: String): Outcome {
        val m = AppDirectory.find(ctx, name) ?: return Outcome.fail("I couldn't find an app called \"$name\".")
        if (m.app.pkg == ctx.packageName) return Outcome.fail("I'd rather not uninstall myself! Do it from Settings if you're sure.")
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${m.app.pkg}")))
        val s = svc ?: return Outcome.ok("Opened ${m.app.label}'s info. Tap Uninstall to remove it.")
        val screen = Screen(s)
        val btn = screen.waitFor(4000) { screen.findByLabel { it == "uninstall" }.firstOrNull() }
            ?: return Outcome.ok("Opened ${m.app.label}'s info. Tap Uninstall to remove it.")
        screen.click(btn)
        return Outcome.ok("Android will ask you to confirm uninstalling ${m.app.label}.")
    }

    private fun openSettings(page: SettingsPage): Outcome {
        val action = when (page) {
            SettingsPage.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsPage.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsPage.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsPage.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsPage.BATTERY -> Intent.ACTION_POWER_USAGE_SUMMARY
            SettingsPage.STORAGE -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            SettingsPage.APPS -> Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
            SettingsPage.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            SettingsPage.SECURITY -> Settings.ACTION_SECURITY_SETTINGS
            SettingsPage.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            SettingsPage.DEVELOPER -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            SettingsPage.DATE -> Settings.ACTION_DATE_SETTINGS
            SettingsPage.LANGUAGE -> Settings.ACTION_LOCALE_SETTINGS
            SettingsPage.NETWORK -> Settings.ACTION_WIRELESS_SETTINGS
            SettingsPage.DATA_USAGE -> Settings.ACTION_DATA_USAGE_SETTINGS
            SettingsPage.NFC -> Settings.ACTION_NFC_SETTINGS
            SettingsPage.GENERAL -> Settings.ACTION_SETTINGS
        }
        val name = page.name.lowercase().replace('_', ' ')
        return if (start(Intent(action)) || start(Intent(Settings.ACTION_SETTINGS))) {
            Outcome.ok("Opening ${name} settings.")
        } else {
            Outcome.fail("I couldn't open settings.")
        }
    }

    // ================================================================== system navigation

    private suspend fun global(kind: GlobalKind, okText: String? = null): Outcome {
        val s = svc
        if (s == null) {
            if (kind == GlobalKind.HOME) {
                start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                return Outcome.quiet(okText ?: "Home.")
            }
            return needAccess()
        }
        val (action, text) = when (kind) {
            GlobalKind.BACK -> AccessibilityService.GLOBAL_ACTION_BACK to "Back."
            GlobalKind.HOME -> AccessibilityService.GLOBAL_ACTION_HOME to "Home."
            GlobalKind.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS to "Recent apps."
            GlobalKind.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "Notifications."
            GlobalKind.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "Quick settings."
            GlobalKind.POWER -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG to "Power menu."
            GlobalKind.SPLIT -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN to "Split screen."
            GlobalKind.LOCK -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Outcome.fail("Locking needs Android 9 or newer.")
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN to "Locked."
            }
            GlobalKind.SCREENSHOT -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Outcome.fail("Screenshots need Android 9 or newer.")
                s.setBubbleHidden(true)
                delay(300)
                val ok = s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                delay(1500)
                s.setBubbleHidden(false)
                return if (ok) Outcome.ok("Screenshot saved.", "Got it") else Outcome.fail("I couldn't take a screenshot.")
            }
        }
        return if (s.performGlobalAction(action)) Outcome.quiet(okText ?: text) else Outcome.fail("That didn't work.")
    }

    // ================================================================== scrolling

    private suspend fun swipe(a: Action.Swipe): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        repeat(a.times) { i ->
            if (!screen.swipe(a.dir, a.distance)) return Outcome.fail("The swipe didn't go through.")
            if (i < a.times - 1) delay(if (a.times > 3) 900 else 650)
        }
        AgentBus.mood.value = RobotMood.IDLE
        val what = when (a.dir) {
            Dir.UP -> "Scrolled down"
            Dir.DOWN -> "Scrolled up"
            Dir.LEFT -> "Swiped left"
            Dir.RIGHT -> "Swiped right"
        }
        return Outcome.quiet(if (a.times > 1) "$what ${a.times} times." else "$what.")
    }

    private suspend fun scrollToEdge(top: Boolean): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val list = screen.mainList()
        val nodeAction = if (top) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var moved = 0
        if (list != null) {
            while (moved < 40 && list.performAction(nodeAction)) {
                moved++
                delay(120)
            }
        }
        if (moved == 0) {
            repeat(6) {
                screen.swipe(if (top) Dir.DOWN else Dir.UP, Distance.LARGE)
                delay(250)
            }
        }
        AgentBus.mood.value = RobotMood.IDLE
        return Outcome.quiet(if (top) "At the top." else "At the bottom.")
    }

    private fun autoScroll(a: Action.AutoScroll): Outcome {
        if (svc == null) return needAccess()
        val secs = a.seconds ?: prefs.autoScrollSeconds
        Agent.startAutoScroll(ctx, secs, a.like)
        val likeText = if (a.like) " and liking each one" else ""
        return Outcome.ok(
            "Auto-scrolling every $secs seconds$likeText. Tap me or say stop to end.",
            "Okay, auto scrolling. Tap me to stop.",
        )
    }

    // ================================================================== social media

    private fun isLikeLabel(l: String): Boolean {
        if ("dislike" in l || "unlike" in l || "liked by" in l || "comment" in l || l.startsWith("likes")) return false
        return l == "like" || l == "like button" || l.startsWith("like this") || l.startsWith("like,") ||
            l.startsWith("like.") || l.startsWith("like button") || l == "heart" || l == "love" ||
            (l.startsWith("like ") && l.length < 60) || l == "double tap to like"
    }

    private fun isLikedLabel(l: String): Boolean =
        l == "unlike" || l.startsWith("unlike") || l == "liked" || l.startsWith("liked,") || l.startsWith("liked.") ||
            l.startsWith("remove like") || l == "liked button"

    private fun isSelected(n: AccessibilityNodeInfo) = n.isSelected || (n.isCheckable && n.isChecked)

    suspend fun likeQuietly(): Boolean = like(false).ok

    private suspend fun like(undo: Boolean): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val liked = screen.social(::isLikedLabel, setOf("liked", "unlike"))
        val likes = screen.social(::isLikeLabel, setOf("like"))
        if (undo) {
            val target = liked.minByOrNull { screen.distanceFromCenterY(it) }
                ?: likes.firstOrNull { isSelected(it) }
                ?: return Outcome.fail("This doesn't look liked yet.")
            screen.click(target)
            return Outcome.ok("Removed your like.")
        }
        val btn = likes.minByOrNull { screen.distanceFromCenterY(it) }
        if (btn != null) {
            if (isSelected(btn)) return Outcome.ok("Already liked.")
            val closerLiked = liked.any { screen.distanceFromCenterY(it) < screen.distanceFromCenterY(btn) }
            if (closerLiked) return Outcome.ok("Already liked.")
            return if (screen.click(btn)) {
                Outcome.ok("Liked!", "Liked", RobotMood.HAPPY)
            } else {
                Outcome.fail("I couldn't press the like button.")
            }
        }
        if (liked.isNotEmpty()) return Outcome.ok("Already liked.")
        if (screen.foregroundPackage() in reelApps) {
            val size = screen.size()
            screen.doubleTap(size.w / 2f, size.h * 0.45f)
            return Outcome.ok("Liked with a double tap!", "Liked", RobotMood.HAPPY)
        }
        return Outcome.fail("I can't see a like button here.")
    }

    private suspend fun dislike(): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val btn = screen.social({ it == "dislike" || it.startsWith("dislike this") || it.startsWith("dislike ") }, setOf("dislike"))
            .minByOrNull { screen.distanceFromCenterY(it) } ?: return Outcome.fail("I can't see a dislike button here.")
        if (isSelected(btn)) return Outcome.ok("Already disliked.")
        screen.click(btn)
        return Outcome.ok("Disliked.")
    }

    private fun isShareLabel(l: String) =
        l == "share" || l == "share button" || l.startsWith("share this") || l == "send post" ||
            l == "share via" || l.startsWith("share,") || (l.startsWith("share ") && l.length < 30)

    private suspend fun share(to: String?, via: String?): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val btn = screen.social(::isShareLabel, setOf("share")).minByOrNull { screen.distanceFromCenterY(it) }
            ?: return Outcome.fail("I can't see a share button here.")
        screen.click(btn)
        if (to == null && via == null) return Outcome.ok("Opened share options.")
        delay(1300)
        if (via != null) {
            val appNode = screen.waitFor(3500) { screen.findBestText(via, 0.8) }
                ?: return Outcome.ok("Share options are open, but I couldn't find $via there.")
            screen.click(appNode)
            delay(1600)
        }
        if (to == null) return Outcome.ok("Sharing with ${capitalised(via ?: "")}.")
        return if (pickRecipientAndSend(screen, to)) {
            Outcome.ok("Shared with $to.", mood = RobotMood.HAPPY)
        } else {
            Outcome.ok("Share options are open, but I couldn't find $to. Please pick them.")
        }
    }

    /** In a share sheet / forward picker: pick [who] (searching if needed) and press Send. */
    private suspend fun pickRecipientAndSend(screen: Screen, who: String): Boolean {
        var person = screen.waitFor(2000) { screen.findBestText(who, 0.8)?.takeIf { !it.isEditable } }
        if (person == null) {
            var box = screen.editables().firstOrNull()
            if (box == null) {
                screen.findByLabel { it == "search" || it.startsWith("search") }.firstOrNull()?.let {
                    screen.click(it)
                    delay(700)
                }
                box = screen.focusedInput()
            }
            if (box == null) return false
            screen.setText(box, who)
            delay(1500)
            person = screen.waitFor(3000) { screen.findBestText(who, 0.75)?.takeIf { !it.isEditable } } ?: return false
        }
        screen.click(person)
        delay(900)
        val send = screen.waitFor(3000) {
            screen.findByLabel { it == "send" || it == "send separately" || (it.startsWith("send ") && it.length < 24) }.lastOrNull()
        } ?: return true
        screen.click(send)
        return true
    }

    private val commentCount = Regex("""(?:(?:view|read|see)(?: all)? )?[\d.,]+\s?[km]? comments?""")

    private suspend fun comment(text: String?): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val btn = screen.social(
            { it == "comment" || it == "comments" || (it.startsWith("comment") && it.length < 40) || commentCount.matches(it) },
            setOf("comment", "comments"),
        ).minByOrNull { screen.distanceFromCenterY(it) } ?: return Outcome.fail("I can't see a comment button here.")
        screen.click(btn)
        if (text == null) return Outcome.ok("Opened comments.")
        delay(1300)
        var box = screen.waitFor(2500) { screen.editables().firstOrNull() }
        if (box == null) {
            screen.findByLabel { it.startsWith("add a comment") || it.startsWith("add comment") || it.startsWith("write a comment") }
                .firstOrNull()?.let {
                    screen.click(it)
                    delay(800)
                }
            box = screen.waitFor(2500) { screen.editables().firstOrNull() }
        }
        if (box == null) return Outcome.ok("Opened comments, but I couldn't find the comment box.")
        screen.click(box)
        delay(300)
        if (!screen.setText(box, text)) return Outcome.fail("The comment box didn't accept text.")
        delay(500)
        val post = screen.waitFor(2500) {
            screen.findByLabel { it == "post" || it == "send" || it == "post comment" || it == "reply" }.firstOrNull { !it.isEditable }
        }
        if (post != null) screen.click(post) else screen.pressImeEnter(box)
        return Outcome.ok("Commented \"$text\".", "Comment posted", RobotMood.HAPPY)
    }

    private suspend fun follow(undo: Boolean): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        if (!undo) {
            val btn = screen.findByLabel { it == "follow" || it == "subscribe" || it == "follow back" || it.startsWith("subscribe to ") }
                .minByOrNull { screen.distanceFromCenterY(it) } ?: return Outcome.fail("I can't see a Follow or Subscribe button.")
            val subscribe = screen.labels(btn).any { it.lowercase().startsWith("subscribe") }
            screen.click(btn)
            return Outcome.ok(if (subscribe) "Subscribed!" else "Followed!", mood = RobotMood.HAPPY)
        }
        val btn = screen.findByLabel { it == "following" || it == "subscribed" || it.startsWith("unsubscribe") || it.startsWith("following ") }
            .firstOrNull() ?: return Outcome.fail("You don't seem to follow this account.")
        screen.click(btn)
        delay(900)
        screen.waitFor(2500) { screen.findByLabel { it == "unfollow" || it == "unsubscribe" }.lastOrNull() }?.let { screen.click(it) }
        return Outcome.ok("Unfollowed.")
    }

    private suspend fun save(): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val direct = screen.findByLabel {
            it == "save" || it == "add to saved" || it == "save post" || it == "save reel" || it == "save video" ||
                it == "bookmark" || it == "save to collection" || it.startsWith("add to saved")
        }.firstOrNull()
        if (direct != null) {
            if (isSelected(direct)) return Outcome.ok("Already saved.")
            screen.click(direct)
            return Outcome.ok("Saved.")
        }
        val more = screen.findByLabel { it == "more" || it == "more options" || it == "more actions" || it == "options" || it == "menu" }
            .minByOrNull { screen.distanceFromCenterY(it) } ?: return Outcome.fail("I can't see a save option here.")
        screen.click(more)
        delay(900)
        val item = screen.waitFor(2500) { screen.findByLabel { it == "save" || (it.startsWith("save") && it.length < 25) }.firstOrNull() }
            ?: return Outcome.ok("Opened the menu, but there's no Save option.")
        screen.click(item)
        return Outcome.ok("Saved.")
    }

    // ================================================================== generic screen control

    private suspend fun tap(target: String, long: Boolean): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val node = screen.findBestText(target, 0.72) ?: return Outcome.fail("I can't see \"$target\" on the screen.")
        val label = screen.labels(node).firstOrNull() ?: target
        val ok = if (long) screen.longClick(node) else screen.click(node)
        return if (ok) Outcome.quiet(if (long) "Long-pressed \"$label\"." else "Tapped \"$label\".") else Outcome.fail("I couldn't tap \"$label\".")
    }

    private suspend fun tapPoint(spot: Spot, double: Boolean): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val size = screen.size()
        val (x, y) = when (spot) {
            Spot.CENTER -> size.w * 0.5f to size.h * 0.45f
            Spot.TOP -> size.w * 0.5f to size.h * 0.18f
            Spot.BOTTOM -> size.w * 0.5f to size.h * 0.82f
            Spot.LEFT -> size.w * 0.12f to size.h * 0.5f
            Spot.RIGHT -> size.w * 0.88f to size.h * 0.5f
        }
        val ok = if (double) screen.doubleTap(x, y) else screen.tap(x, y)
        return if (ok) Outcome.quiet(if (double) "Double-tapped." else "Tapped.") else Outcome.fail("The tap didn't go through.")
    }

    private suspend fun tapNth(n: Int): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val list = screen.mainList()
        val items = if (list != null) {
            (0 until list.childCount).mapNotNull { list.getChild(it) }
                .filter { screen.isOnScreen(it) && screen.bounds(it).height() > 40 }
        } else {
            screen.collect(screen.roots(false), limit = 40) { it.isClickable && screen.isOnScreen(it) && screen.bounds(it).height() > 60 }
        }
        val ordered = screen.readingOrder(items)
        if (ordered.isEmpty()) return Outcome.fail("I can't see a list here.")
        val pick = if (n == -1) ordered.last() else ordered.getOrNull(n - 1)
            ?: return Outcome.fail("I only see ${ordered.size} items.")
        screen.click(pick)
        return Outcome.quiet(if (n == -1) "Opened the last one." else "Opened item $n.")
    }

    private suspend fun type(text: String): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val box = screen.focusedInput() ?: return Outcome.fail("I can't find a text box. Tap one first, or say \"tap search\".")
        if (box.isPassword) return Outcome.fail("For your safety, I never type into password or PIN boxes.")
        if (!box.isFocused) {
            screen.click(box)
            delay(300)
        }
        val existing = screen.currentText(box)
        val value = if (existing.isBlank()) text else "$existing $text"
        return if (screen.setText(box, value)) Outcome.quiet("Typed \"$text\".") else Outcome.fail("That text box didn't accept typing.")
    }

    private suspend fun submit(label: String?): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        if (label != null) {
            val btn = screen.findByLabel { it == label || it == "$label button" || (label == "send" && it == "send message") }.lastOrNull()
            if (btn != null) {
                screen.click(btn)
                return Outcome.quiet(if (label == "send") "Sent." else "Done.")
            }
        }
        val box = screen.focusedInput()
        if (box != null && screen.pressImeEnter(box)) return Outcome.quiet("Done.")
        val btn = screen.findByLabel { it in setOf("send", "search", "go", "done", "next", "submit", "ok", "post") }.lastOrNull()
            ?: return Outcome.fail("I couldn't find a button to press.")
        screen.click(btn)
        return Outcome.quiet("Done.")
    }

    private fun selectAll(screen: Screen, n: AccessibilityNodeInfo): Boolean {
        val len = screen.currentText(n).length
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private fun editText(op: TextOp): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val box = screen.focusedInput() ?: return Outcome.fail("No text box is selected.")
        if (box.isPassword && op != TextOp.CLEAR && op != TextOp.PASTE) return Outcome.fail("I don't copy from password boxes.")
        val ok = when (op) {
            TextOp.SELECT_ALL -> selectAll(screen, box)
            TextOp.COPY -> selectAll(screen, box) && box.performAction(AccessibilityNodeInfo.ACTION_COPY)
            TextOp.CUT -> selectAll(screen, box) && box.performAction(AccessibilityNodeInfo.ACTION_CUT)
            TextOp.PASTE -> box.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            TextOp.CLEAR -> screen.setText(box, "")
        }
        val done = when (op) {
            TextOp.SELECT_ALL -> "Selected all text."
            TextOp.COPY -> "Copied."
            TextOp.CUT -> "Cut."
            TextOp.PASTE -> "Pasted."
            TextOp.CLEAR -> "Cleared."
        }
        return if (ok) Outcome.quiet(done) else Outcome.fail("That text box didn't allow it.")
    }

    private suspend fun findOnScreen(text: String): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        repeat(10) {
            screen.findBestText(text, 0.78)?.let { node ->
                val label = screen.labels(node).firstOrNull() ?: text
                AgentBus.mood.value = RobotMood.IDLE
                return Outcome.ok("Found \"$label\". Say \"tap $text\" to open it.", "Found it")
            }
            screen.swipe(Dir.UP, Distance.NORMAL)
            delay(800)
        }
        return Outcome.fail("I scrolled but couldn't find \"$text\".")
    }

    private fun readScreen(): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        val text = screen.visibleText(screen.roots(includeSystem = false))
        if (text.isBlank()) return Outcome.fail("I can't read anything here. This app may hide its content.")
        return Outcome.ok(if (text.length > 220) text.take(220) + "…" else text, text, RobotMood.SPEAKING)
    }

    private suspend fun readNotifications(): Outcome {
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        delay(1200)
        val text = screen.visibleText(screen.systemRoots(), 900)
        if (text.isBlank()) return Outcome.ok("No notifications to read.")
        return Outcome.ok(if (text.length > 220) text.take(220) + "…" else text, text, RobotMood.SPEAKING)
    }

    // ================================================================== people

    private fun contactOrFail(who: String): Pair<ContactDirectory.Contact?, Outcome?> =
        when (val r = ContactDirectory.find(ctx, who)) {
            is ContactDirectory.Result.Found -> r.contact to null
            ContactDirectory.Result.NoPermission -> null to Outcome.fail("Allow Contacts access in the Bixi app so I can find $who.")
            ContactDirectory.Result.NotFound -> null to Outcome.fail("I couldn't find $who in your contacts.")
        }

    private suspend fun call(who: String, whatsapp: Boolean, video: Boolean): Outcome {
        val (contact, err) = contactOrFail(who)
        if (contact == null) return err!!
        if (whatsapp || video) return whatsappCall(contact, video)
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", contact.number, null))
        if (!start(intent)) return Outcome.fail("I couldn't start the call.")
        return if (canCall) Outcome.ok("Calling ${contact.name}.") else Outcome.ok("Dialing ${contact.name}. Tap the call button.")
    }

    private fun openWhatsAppChat(pkg: String, number: String, text: String?): Boolean {
        val digits = ContactDirectory.internationalDigits(number)
        val url = "https://api.whatsapp.com/send?phone=$digits" + (text?.let { "&text=" + Uri.encode(it) } ?: "")
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))
    }

    private suspend fun whatsappCall(contact: ContactDirectory.Contact, video: Boolean): Outcome {
        val pkg = whatsappPackage() ?: return Outcome.fail("WhatsApp isn't installed.")
        val s = svc ?: return needAccess()
        if (!openWhatsAppChat(pkg, contact.number, null)) return Outcome.fail("I couldn't open WhatsApp.")
        val screen = Screen(s)
        val label = if (video) "video call" else "voice call"
        val btn = screen.waitFor(9000, 250) {
            if (screen.foregroundPackage() != pkg) null
            else screen.findByLabel { it == label || it.startsWith(label) || (!video && it == "call") }.firstOrNull()
        } ?: return Outcome.ok("Opened ${contact.name}'s chat, but I couldn't find the call button.")
        screen.click(btn)
        delay(1200)
        // WhatsApp may ask "Start voice call?" — press its Call button (a text button, not the icon).
        screen.waitFor(2000) {
            screen.collect(limit = 3) { n -> screen.isOnScreen(n) && screen.text(n).lowercase() in setOf("call", "start call", "video call", "voice call") }
                .lastOrNull()
        }?.let { screen.click(it) }
        return Outcome.ok("${if (video) "Video" else "Voice"} calling ${contact.name} on WhatsApp.")
    }

    private suspend fun sendMessage(who: String, message: String, app: Messenger): Outcome {
        val useSms = app == Messenger.SMS || (app == Messenger.DEFAULT && prefs.defaultMessenger == "sms")
        return if (useSms) sendSms(who, message) else sendWhatsApp(who, message)
    }

    private fun whatsappSendButton(screen: Screen) =
        screen.findByViewId("send") ?: screen.findByLabel(screen.roots(false)) { it == "send" }.firstOrNull()

    private suspend fun sendWhatsApp(who: String, message: String): Outcome {
        val pkg = whatsappPackage() ?: return Outcome.fail("WhatsApp isn't installed.")
        val s = svc ?: return needAccess()
        val screen = Screen(s)
        when (val r = ContactDirectory.find(ctx, who)) {
            is ContactDirectory.Result.Found -> {
                if (!openWhatsAppChat(pkg, r.contact.number, message)) return Outcome.fail("I couldn't open WhatsApp.")
                val send = screen.waitFor(10_000, 250) {
                    if (screen.foregroundPackage() != pkg) null else whatsappSendButton(screen)
                } ?: return Outcome.fail("I opened WhatsApp for ${r.contact.name} but couldn't press Send. Are they on WhatsApp?")
                screen.click(send)
                return Outcome.ok("Sent \"$message\" to ${r.contact.name} on WhatsApp.", "Sent to ${r.contact.name}", RobotMood.HAPPY)
            }
            else -> {
                if (!launchPackage(pkg)) return Outcome.fail("I couldn't open WhatsApp.")
                if (!openWhatsAppChatBySearch(screen, pkg, who)) {
                    return Outcome.fail(
                        if (r == ContactDirectory.Result.NoPermission) "Allow Contacts access in Bixi so I can find $who."
                        else "I couldn't find $who in WhatsApp.",
                    )
                }
                val box = screen.waitFor(4000) { screen.findByViewId("entry") ?: screen.editables().lastOrNull() }
                    ?: return Outcome.fail("I opened the chat but couldn't find the message box.")
                screen.click(box)
                screen.setText(box, message)
                delay(400)
                val send = screen.waitFor(3000) { whatsappSendButton(screen) } ?: return Outcome.fail("I typed it but couldn't press Send.")
                screen.click(send)
                return Outcome.ok("Sent \"$message\" to $who on WhatsApp.", "Sent to $who", RobotMood.HAPPY)
            }
        }
    }

    private suspend fun openWhatsAppChatBySearch(screen: Screen, pkg: String, who: String): Boolean {
        if (!waitForPackage(screen, pkg)) return false
        delay(800)
        val search = screen.waitFor(4000) {
            screen.findByViewId("menuitem_search", "my_search_bar", "search_bar", "search_src_text")
                ?: screen.findByLabel { it == "search" || it.startsWith("search ") || it.startsWith("ask meta ai or search") }.firstOrNull()
        } ?: return false
        screen.click(search)
        delay(700)
        val box = screen.waitFor(2500) { screen.focusedInput() } ?: return false
        screen.setText(box, who)
        delay(1300)
        val row = screen.waitFor(3000) { screen.findBestText(who, 0.75)?.takeIf { !it.isEditable } } ?: return false
        screen.click(row)
        delay(1000)
        return true
    }

    private suspend fun sendSms(who: String, message: String): Outcome {
        val (contact, err) = contactOrFail(who)
        if (contact == null) return err!!
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(contact.number))).putExtra("sms_body", message)
        if (!start(intent)) return Outcome.fail("I couldn't open Messages.")
        val s = svc ?: return Outcome.ok("Your message to ${contact.name} is ready. Tap send.")
        val screen = Screen(s)
        val send = screen.waitFor(7000, 250) {
            screen.findByLabel(screen.roots(false)) { it == "send" || it.startsWith("send ") || it.startsWith("send,") || it == "send message" }
                .firstOrNull()
        } ?: return Outcome.ok("Your message to ${contact.name} is ready. Tap send.")
        screen.click(send)
        return Outcome.ok("Sent SMS to ${contact.name}.", mood = RobotMood.HAPPY)
    }

    private suspend fun openChat(who: String, app: Messenger): Outcome {
        val useSms = app == Messenger.SMS || (app == Messenger.DEFAULT && prefs.defaultMessenger == "sms")
        if (useSms) {
            val (contact, err) = contactOrFail(who)
            if (contact == null) return err!!
            return if (start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(contact.number))))) {
                Outcome.ok("Opened your messages with ${contact.name}.")
            } else {
                Outcome.fail("I couldn't open Messages.")
            }
        }
        val pkg = whatsappPackage() ?: return Outcome.fail("WhatsApp isn't installed.")
        val r = ContactDirectory.find(ctx, who)
        if (r is ContactDirectory.Result.Found) {
            return if (openWhatsAppChat(pkg, r.contact.number, null)) Outcome.ok("Opened chat with ${r.contact.name}.") else Outcome.fail("I couldn't open WhatsApp.")
        }
        val s = svc ?: return needAccess()
        if (!launchPackage(pkg)) return Outcome.fail("I couldn't open WhatsApp.")
        return if (openWhatsAppChatBySearch(Screen(s), pkg, who)) Outcome.ok("Opened chat with $who.") else Outcome.fail("I couldn't find $who in WhatsApp.")
    }

    /** Opens the payment screen. Bixi never types PINs or presses the final Pay button. */
    private fun pay(payee: String?, amount: String?, app: String?): Outcome {
        if (payee != null && payee.contains('@')) {
            val uri = Uri.Builder().scheme("upi").authority("pay")
                .appendQueryParameter("pa", payee.replace(" ", ""))
                .appendQueryParameter("pn", payee.substringBefore('@'))
                .apply { amount?.let { appendQueryParameter("am", it) } }
                .appendQueryParameter("cu", "INR")
                .build()
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (app != null) AppDirectory.paymentApp(ctx, app)?.let { intent.setPackage(it.app.pkg) }
            if (!start(intent)) return Outcome.fail("No UPI app could open that payment.")
            return Outcome.ok(
                "Payment${amount?.let { " of ₹$it" } ?: ""} to $payee is ready. Check it and enter your UPI PIN yourself.",
                "The payment screen is ready. Please check it and enter your PIN yourself.",
            )
        }
        val m = AppDirectory.paymentApp(ctx, app) ?: return Outcome.fail("I couldn't find a UPI app like Google Pay, PhonePe or Paytm.")
        if (!launchPackage(m.app.pkg)) return Outcome.fail("I couldn't open ${m.app.label}.")
        val steps = buildString {
            if (payee != null) append(" Pick $payee")
            if (amount != null) append(if (payee != null) " and enter ₹$amount" else " Enter ₹$amount")
            if (payee != null || amount != null) append(".")
        }
        return Outcome.ok(
            "Opened ${m.app.label}.$steps You finish the payment and PIN yourself: I never handle PINs.",
            "Opened ${m.app.label}. Please finish the payment yourself.",
        )
    }

    // ================================================================== media & device

    private suspend fun media(key: MediaKey): Outcome {
        val s = svc
        val pkg = s?.let { Screen(it).foregroundPackage() }
        if (s != null && (key == MediaKey.PLAY || key == MediaKey.PAUSE) && pkg in reelApps && pkg != "com.google.android.youtube") {
            val screen = Screen(s)
            val size = screen.size()
            screen.tap(size.w / 2f, size.h * 0.45f)
            return Outcome.quiet(if (key == MediaKey.PAUSE) "Paused." else "Playing.")
        }
        val code = when (key) {
            MediaKey.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val am = ctx.getSystemService(AudioManager::class.java) ?: return Outcome.fail("Audio isn't available.")
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return Outcome.quiet(
            when (key) {
                MediaKey.PLAY -> "Playing."
                MediaKey.PAUSE -> "Paused."
                MediaKey.NEXT -> "Next track."
                MediaKey.PREVIOUS -> "Previous track."
            },
        )
    }

    private suspend fun playMedia(query: String, app: String?): Outcome {
        val a = app?.lowercase().orEmpty()
        val spotify = "com.spotify.music"
        val ytMusic = "com.google.android.apps.youtube.music"
        fun playFromSearch(pkg: String) = start(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(pkg)
                .putExtra(SearchManager.QUERY, query)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"),
        )
        when {
            a == "spotify" -> {
                if (playFromSearch(spotify) || start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))) {
                    return Outcome.ok("Playing $query on Spotify.")
                }
                return Outcome.fail("Spotify isn't installed.")
            }
            a == "youtube music" || a == "yt music" -> {
                return if (playFromSearch(ytMusic)) Outcome.ok("Playing $query on YouTube Music.") else Outcome.fail("YouTube Music isn't installed.")
            }
            a.isNotEmpty() && a !in setOf("youtube", "yt", "music", "music app") -> {
                return search(query, a)
            }
        }
        // Default: YouTube, then tap the first video.
        val yt = "com.google.android.youtube"
        if (!start(Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra(SearchManager.QUERY, query))) {
            return if (playFromSearch(ytMusic)) Outcome.ok("Playing $query.") else Outcome.fail("I couldn't find YouTube to play that.")
        }
        val s = svc ?: return Outcome.ok("Here are YouTube results for $query.")
        val screen = Screen(s)
        val first = screen.waitFor(8000, 300) {
            if (screen.foregroundPackage() != yt) null
            else screen.collect(screen.roots(false), limit = 5) { n ->
                val d = screen.desc(n).lowercase()
                screen.isOnScreen(n) && (d.endsWith("play video") || d.contains(" - play video") || (d.contains(" views") && d.contains(" - ")))
            }.firstOrNull { !screen.desc(it).lowercase().contains("ad ") }
        } ?: return Outcome.ok("Here are YouTube results for $query.")
        screen.click(first)
        return Outcome.ok("Playing $query on YouTube.")
    }

    private fun volume(level: Level, percent: Int?): Outcome {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return Outcome.fail("Audio isn't available.")
        val stream = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        return try {
            when (level) {
                Level.UP -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
                Level.DOWN -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
                Level.MUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
                Level.UNMUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
                Level.SET -> {
                    val max = am.getStreamMaxVolume(stream)
                    am.setStreamVolume(stream, (max * (percent ?: 50) / 100f).roundToInt(), flags)
                }
            }
            Outcome.quiet(
                when (level) {
                    Level.UP -> "Volume up."
                    Level.DOWN -> "Volume down."
                    Level.MUTE -> "Muted."
                    Level.UNMUTE -> "Unmuted."
                    Level.SET -> "Volume ${percent ?: 50}%."
                },
            )
        } catch (_: SecurityException) {
            Outcome.fail("Android blocked the volume change (Do Not Disturb may be on).")
        }
    }

    private fun brightness(level: Level, percent: Int?): Outcome {
        if (!Settings.System.canWrite(ctx)) {
            start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            return Outcome.fail("Allow \"Modify system settings\" for Bixi, then ask again.")
        }
        val cr = ctx.contentResolver
        return try {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val target = when (level) {
                Level.UP -> cur + 45
                Level.DOWN -> cur - 45
                Level.SET -> (255 * (percent ?: 50) / 100f).roundToInt()
                else -> cur
            }.coerceIn(1, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
            Outcome.quiet("Brightness ${(target * 100f / 255).roundToInt()}%.")
        } catch (_: Exception) {
            Outcome.fail("I couldn't change the brightness.")
        }
    }

    private suspend fun toggle(feature: Feature, on: Boolean?): Outcome {
        fun onOff(b: Boolean?) = when (b) {
            true -> "on"
            false -> "off"
            null -> "toggled"
        }
        when (feature) {
            Feature.FLASHLIGHT -> {
                val want = on ?: !Torch.isOn
                return if (Torch.set(want)) Outcome.quiet("Flashlight ${onOff(want)}.") else Outcome.fail("I couldn't control the flashlight.")
            }
            Feature.DND, Feature.SILENT -> {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm != null && nm.isNotificationPolicyAccessGranted) {
                    if (feature == Feature.DND) {
                        val cur = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                        val want = on ?: !cur
                        nm.setInterruptionFilter(if (want) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                        return Outcome.quiet("Do not disturb ${onOff(want)}.")
                    }
                    val am = ctx.getSystemService(AudioManager::class.java)
                    val cur = am.ringerMode == AudioManager.RINGER_MODE_SILENT
                    val want = on ?: !cur
                    am.ringerMode = if (want) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                    return Outcome.quiet("Silent mode ${onOff(want)}.")
                }
                if (feature == Feature.SILENT) {
                    start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return Outcome.fail("Android needs \"Do not disturb access\" for Bixi to switch silent mode. Allow it on the screen I opened.")
                }
            }
            Feature.VIBRATE -> {
                val am = ctx.getSystemService(AudioManager::class.java)
                return try {
                    val want = on ?: (am.ringerMode != AudioManager.RINGER_MODE_VIBRATE)
                    am.ringerMode = if (want) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
                    Outcome.quiet("Vibrate mode ${onOff(want)}.")
                } catch (_: SecurityException) {
                    Outcome.fail("Android blocked that. Turn off Do Not Disturb first.")
                }
            }
            else -> Unit
        }
        return quickSettingsToggle(feature, on)
    }

    private fun tileWords(f: Feature): List<String> = when (f) {
        Feature.WIFI -> listOf("wi-fi", "wifi", "internet")
        Feature.BLUETOOTH -> listOf("bluetooth")
        Feature.MOBILE_DATA -> listOf("mobile data", "data", "mobile network")
        Feature.AIRPLANE -> listOf("airplane mode", "flight mode", "aeroplane mode", "airplane")
        Feature.DND -> listOf("do not disturb", "dnd")
        Feature.HOTSPOT -> listOf("mobile hotspot", "hotspot")
        Feature.LOCATION -> listOf("location")
        Feature.AUTO_ROTATE -> listOf("auto rotate", "auto-rotate", "portrait", "landscape", "rotation")
        Feature.POWER_SAVING -> listOf("power saving", "power saving mode", "battery saver")
        Feature.DARK_MODE -> listOf("dark mode", "dark theme")
        Feature.NFC -> listOf("nfc", "nfc and contactless payments")
        Feature.EYE_COMFORT -> listOf("eye comfort shield", "eye comfort", "night light")
        else -> listOf(f.spoken.lowercase())
    }

    private fun findTile(screen: Screen, words: List<String>): AccessibilityNodeInfo? {
        val hits = screen.collect(screen.systemRoots(), limit = 10) { n ->
            screen.isOnScreen(n) && screen.labels(n).any { raw ->
                val l = raw.lowercase()
                words.any { w -> l == w || l.startsWith("$w,") || l.startsWith("$w ") || l.startsWith("$w.") }
            }
        }
        return hits.firstNotNullOfOrNull { screen.clickableTarget(it) }
    }

    private fun tileState(screen: Screen, n: AccessibilityNodeInfo): Boolean? {
        if (n.isCheckable) return n.isChecked
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) n.stateDescription?.let { parts += it.toString() }
        parts += screen.labels(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { c ->
            parts += screen.labels(c)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.stateDescription?.let { parts += it.toString() }
        }
        val all = parts.joinToString(" ").lowercase()
        if (Regex("""\b(off|disconnected|disabled|not connected)\b""").containsMatchIn(all)) return false
        if (Regex("""\b(on|connected|enabled)\b""").containsMatchIn(all)) return true
        return null
    }

    private fun closeShade(screen: Screen) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            screen.global(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            screen.global(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** Flips a Quick Settings tile, reading its state first so "turn on" never turns it off. */
    private suspend fun quickSettingsToggle(feature: Feature, on: Boolean?): Outcome {
        val s = svc ?: return settingsFallback(feature, on)
        val screen = Screen(s)
        val words = tileWords(feature)
        screen.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        delay(900)
        var tile = findTile(screen, words)
        if (tile == null) {
            val size = screen.size()
            screen.swipe(size.w * 0.85f, size.h * 0.35f, size.w * 0.15f, size.h * 0.35f, 250)
            delay(700)
            tile = findTile(screen, words)
        }
        if (tile == null) {
            closeShade(screen)
            delay(400)
            return settingsFallback(feature, on)
        }
        val before = tileState(screen, tile)
        if (on != null && before == on) {
            closeShade(screen)
            return Outcome.quiet("${feature.spoken} is already ${if (on) "on" else "off"}.")
        }
        screen.click(tile)
        delay(800)
        val after = (findTile(screen, words) ?: tile).let { tileState(screen, it) }
        closeShade(screen)
        val now = after ?: on ?: before?.not()
        return Outcome.quiet("${feature.spoken} ${if (now == true) "on" else if (now == false) "off" else "toggled"}.")
    }

    private suspend fun settingsFallback(feature: Feature, on: Boolean?): Outcome {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val (intent, autoSwitch) = when (feature) {
            Feature.WIFI -> Intent(if (q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS) to true
            Feature.MOBILE_DATA -> Intent(if (q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS) to q
            Feature.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS) to true
            Feature.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS) to false
            Feature.NFC -> Intent(if (q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS) to true
            Feature.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) to true
            Feature.POWER_SAVING -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) to true
            Feature.DND -> Intent(Settings.ACTION_SOUND_SETTINGS) to false
            Feature.DARK_MODE, Feature.EYE_COMFORT, Feature.AUTO_ROTATE -> Intent(Settings.ACTION_DISPLAY_SETTINGS) to false
            else -> Intent(Settings.ACTION_WIRELESS_SETTINGS) to false
        }
        if (!start(intent)) start(Intent(Settings.ACTION_SETTINGS))
        val s = svc
        if (autoSwitch && s != null) {
            val screen = Screen(s)
            delay(1300)
            val sw = screen.collect(screen.roots(), limit = 1) {
                screen.isOnScreen(it) && it.isCheckable && (it.className?.contains("Switch") == true || it.className?.contains("Toggle") == true)
            }.firstOrNull()
            if (sw != null) {
                if (on == null || sw.isChecked != on) screen.click(sw)
                return Outcome.quiet("${feature.spoken} ${if ((on ?: !sw.isChecked)) "on" else "off"}.")
            }
        }
        return Outcome.ok("I opened ${feature.spoken} settings. Flip the switch there.")
    }

    // ================================================================== search & maps

    private fun webSearch(query: String): Outcome {
        val ok = start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
        return if (ok) Outcome.ok("Searching for $query.") else Outcome.fail("I couldn't open a browser.")
    }

    private suspend fun inAppSearch(screen: Screen, query: String): Boolean {
        var box = screen.editables().firstOrNull { n ->
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString().orEmpty() else ""
            (screen.text(n) + " " + screen.desc(n) + " " + hint + " " + n.viewIdResourceName.orEmpty()).lowercase().contains("search")
        }
        if (box == null) {
            val btn = screen.findByLabel(screen.roots(false)) {
                it == "search" || (it.startsWith("search") && it.length < 40) || it == "search and explore"
            }.firstOrNull() ?: return false
            screen.click(btn)
            delay(900)
            box = screen.waitFor(2500) { screen.focusedInput() ?: screen.editables().firstOrNull() } ?: return false
        }
        screen.click(box)
        delay(250)
        if (!screen.setText(box, query)) return false
        delay(500)
        if (!screen.pressImeEnter(box)) {
            screen.findBestText(query, 0.85)?.takeIf { !it.isEditable }?.let { screen.click(it) }
        }
        return true
    }

    private suspend fun search(query: String, where: String?): Outcome {
        val w = where?.lowercase()?.trim()
        when (w) {
            null, "this app", "here" -> {
                val s = svc
                val pkg = s?.let { Screen(it).foregroundPackage() }
                val insideApp = pkg != null && pkg != ctx.packageName && pkg != homePackage && pkg != "com.android.systemui"
                if (s != null && insideApp && inAppSearch(Screen(s), query)) return Outcome.ok("Searching for $query.")
                return webSearch(query)
            }
            "youtube", "yt" -> {
                val ok = start(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, query))
                return if (ok) Outcome.ok("Searching YouTube for $query.") else webSearch("$query youtube")
            }
            "google", "chrome", "browser", "internet", "bing", "duckduckgo" -> return webSearch(query)
            "play store", "playstore" -> {
                val ok = start(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(query))))
                return if (ok) Outcome.ok("Searching the Play Store for $query.") else Outcome.fail("I couldn't open the Play Store.")
            }
            "maps", "google maps" -> return showMap(query)
            "spotify" -> {
                val ok = start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
                return if (ok) Outcome.ok("Searching Spotify for $query.") else Outcome.fail("Spotify isn't installed.")
            }
        }
        val m = AppDirectory.find(ctx, w!!) ?: return webSearch("$query $w")
        if (!launchPackage(m.app.pkg)) return Outcome.fail("I couldn't open ${m.app.label}.")
        val s = svc ?: return Outcome.ok("Opened ${m.app.label}. Turn on my accessibility service so I can search in it.")
        val screen = Screen(s)
        waitForPackage(screen, m.app.pkg)
        delay(1300)
        return if (inAppSearch(screen, query)) {
            Outcome.ok("Searching ${m.app.label} for $query.")
        } else {
            Outcome.ok("Opened ${m.app.label}, but I couldn't find its search box.")
        }
    }

    private fun navigate(place: String, mode: String?): Outcome {
        val m = when (mode?.lowercase()) {
            "walk", "walking", "foot" -> "w"
            "bike", "two wheeler", "twowheeler", "scooter" -> "l"
            else -> "d"
        }
        val ok = start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}&mode=$m")).setPackage("com.google.android.apps.maps")) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return if (ok) Outcome.ok("Starting directions to $place.") else Outcome.fail("I couldn't open Maps.")
    }

    private fun showMap(place: String): Outcome =
        if (start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))) {
            Outcome.ok("Showing $place on the map.")
        } else {
            Outcome.fail("I couldn't open Maps.")
        }

    // ================================================================== clock & camera

    private fun setAlarm(hour: Int, minute: Int, label: String?): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val time = TimeParser.formatClock(TimeParser.Clock(hour, minute))
        return if (start(i)) Outcome.ok("Alarm set for $time" + (label?.let { " ($it)." } ?: ".")) else Outcome.fail("I couldn't set the alarm.")
    }

    private fun setTimer(seconds: Int, label: String?): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val dur = TimeParser.formatDuration(seconds)
        return if (start(i)) Outcome.ok("Timer set for $dur" + (label?.let { " to $it." } ?: ".")) else Outcome.fail("I couldn't set the timer.")
    }

    private suspend fun camera(selfie: Boolean, video: Boolean): Outcome {
        val s = svc
        val inCamera = s?.let { Screen(it).foregroundPackage()?.contains("camera") } == true
        if (!inCamera) {
            val intent = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            if (selfie) {
                intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                intent.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
            if (!start(intent)) return Outcome.fail("I couldn't open the camera.")
        }
        val screen = s?.let { Screen(it) } ?: return Outcome.ok("Camera is open.")
        delay(if (inCamera) 300 else 2000)
        if (selfie) {
            screen.findByLabel { it.contains("switch to front") || it == "front camera" }.firstOrNull()?.let {
                screen.click(it)
                delay(1300)
            }
        }
        val words = if (video) listOf("record", "start recording", "record video") else listOf("take picture", "take photo", "shutter", "capture", "take a picture")
        val btn = screen.waitFor(4000) { screen.findByLabel { l -> words.any { l == it || l.startsWith(it) } }.firstOrNull() }
            ?: return Outcome.ok("Camera is open. Tap the shutter, or say \"click photo\" again.")
        screen.click(btn)
        return Outcome.ok(if (video) "Recording started." else "Photo taken!", mood = RobotMood.HAPPY)
    }

    // ================================================================== Bixi itself

    private fun info(kind: InfoKind): Outcome = when (kind) {
        InfoKind.TIME -> {
            val t = DateFormat.getTimeFormat(ctx).format(Date())
            Outcome.ok("It's $t.")
        }
        InfoKind.DATE -> {
            val d = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
            Outcome.ok("Today is $d.")
        }
        InfoKind.BATTERY -> {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = bm?.isCharging == true
            if (pct < 0) Outcome.fail("I couldn't read the battery.") else Outcome.ok("Battery is at $pct%" + if (charging) " and charging." else ".")
        }
    }

    private fun talk(kind: Chat): Outcome = when (kind) {
        Chat.GREET -> Outcome.ok("Hi! I'm Bixi. Tell me what to do.", mood = RobotMood.HAPPY)
        Chat.THANKS -> Outcome.ok("Anytime!", mood = RobotMood.HAPPY)
        Chat.IDENTITY -> Outcome.ok(
            "I'm Bixi, your offline phone assistant. I open apps, scroll and like reels, message people and lots more. Just ask!",
            mood = RobotMood.HAPPY,
        )
    }

    private fun help(): Outcome {
        start(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1))
        return Outcome.ok("Here's what I can do.")
    }

    private fun teach(phrase: String, commands: String): Outcome {
        Routines.add(ctx, phrase, commands)
        return Outcome.ok("Got it! When you say \"$phrase\", I'll $commands.", mood = RobotMood.HAPPY)
    }

    private fun bubble(show: Boolean): Outcome {
        prefs.showBubble = show
        AgentBus.bubbleVisible.value = show
        return if (show) Outcome.ok("I'm back!", mood = RobotMood.HAPPY) else Outcome.ok("Hiding. Use the Bixi Talk icon or Quick Settings tile to call me.")
    }

    private suspend fun unknown(text: String): Outcome {
        AppDirectory.find(ctx, text, 0.86)?.let { return openApp(text) }
        svc?.let { s ->
            val screen = Screen(s)
            screen.findBestText(text, 0.9)?.let { node ->
                screen.click(node)
                return Outcome.quiet("Tapped \"$text\".")
            }
        }
        return Outcome.fail("Sorry, I didn't get \"$text\". Say \"help\" to see what I can do.", "Sorry, I didn't get that.")
    }
}
