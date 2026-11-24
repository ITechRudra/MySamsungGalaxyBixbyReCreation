package com.bixi.agent.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun one(text: String): Action {
        val list = CommandParser.parse(text)
        assertEquals("expected exactly one action for \"$text\" but got $list", 1, list.size)
        return list[0]
    }

    private fun check(text: String, expected: Action) = assertEquals("\"$text\"", expected, one(text))

    @Test fun apps() {
        check("open whatsapp", Action.OpenApp("whatsapp"))
        check("Hey Bixi, open WhatsApp please", Action.OpenApp("whatsapp"))
        check("launch the instagram app", Action.OpenApp("instagram"))
        check("open what's app", Action.OpenApp("whatsapp"))
        check("go to youtube", Action.OpenApp("youtube"))
        check("open sbi yono", Action.OpenApp("sbi yono"))
        check("whatsapp kholo", Action.OpenApp("whatsapp"))
        check("insta khol do", Action.OpenApp("insta"))
        check("close whatsapp", Action.CloseApp("whatsapp"))
        check("close all apps", Action.CloseAllApps)
        check("uninstall candy crush", Action.Uninstall("candy crush"))
        check("open settings", Action.OpenApp("settings"))
        check("open wifi settings", Action.OpenSettings(SettingsPage.WIFI))
    }

    @Test fun reelsAndScrolling() {
        check("next", Action.Swipe(Dir.UP))
        check("next reel", Action.Swipe(Dir.UP))
        check("next real", Action.Swipe(Dir.UP))
        check("skip this video", Action.Swipe(Dir.UP))
        check("next 5 reels", Action.Swipe(Dir.UP, 5))
        check("previous", Action.Swipe(Dir.DOWN))
        check("go back to the previous reel", Action.Swipe(Dir.DOWN))
        check("scroll", Action.Swipe(Dir.UP))
        check("scroll down", Action.Swipe(Dir.UP))
        check("scroll up", Action.Swipe(Dir.DOWN))
        check("swipe left", Action.Swipe(Dir.LEFT))
        check("scroll down a little", Action.Swipe(Dir.UP, 1, Distance.SMALL))
        check("scroll down 3 times", Action.Swipe(Dir.UP, 3))
        check("scroll to the top", Action.ScrollToEdge(true))
        check("agli reel", Action.Swipe(Dir.UP))
        check("neeche scroll karo", Action.Swipe(Dir.UP))
        check("open reels", Action.OpenSection("instagram", "Reels"))
        check("open youtube shorts", Action.OpenSection("youtube", "Shorts"))
        check("show me reels on facebook", Action.OpenSection("facebook", "Reels"))
        check("reels chalao", Action.OpenSection("instagram", "Reels"))
        check("next story", Action.TapPoint(Spot.RIGHT))
    }

    @Test fun autoScroll() {
        check("keep scrolling", Action.AutoScroll(null, false))
        check("auto scroll every 5 seconds", Action.AutoScroll(5, false))
        check("keep scrolling and like every reel", Action.AutoScroll(null, true))
        check("stop", Action.Stop)
        check("stop scrolling", Action.Stop)
        check("ruko", Action.Stop)
    }

    @Test fun social() {
        check("like", Action.Like())
        check("like this reel", Action.Like())
        check("like karo", Action.Like())
        check("double tap to like", Action.Like())
        check("unlike", Action.Like(undo = true))
        check("dislike this video", Action.Dislike)
        check("share", Action.Share())
        check("share this reel", Action.Share())
        check("share this with mom on whatsapp", Action.Share("mom", "whatsapp"))
        check("send this reel to Rahul", Action.Share("Rahul", null))
        check("comment nice video bro", Action.Comment("nice video bro"))
        check("open comments", Action.Comment(null))
        check("follow", Action.Follow())
        check("subscribe to this channel", Action.Follow())
        check("unfollow", Action.Follow(undo = true))
        check("save this post", Action.Save)
    }

    @Test fun chained() {
        assertEquals(
            listOf(Action.Like(), Action.Swipe(Dir.UP)),
            CommandParser.parse("like this and next"),
        )
        assertEquals(
            listOf(Action.OpenApp("instagram"), Action.AutoScroll(null, false)),
            CommandParser.parse("open instagram then keep scrolling"),
        )
        assertEquals(
            listOf(Action.Like(), Action.Share()),
            CommandParser.parse("like and share"),
        )
        assertEquals(
            listOf(Action.Toggle(Feature.WIFI, true), Action.Toggle(Feature.BLUETOOTH, true)),
            CommandParser.parse("turn on wifi and bluetooth"),
        )
        assertEquals(
            listOf(Action.OpenApp("whatsapp"), Action.OpenSection("instagram", "Reels")),
            CommandParser.parse("whatsapp kholo aur reels chalao"),
        )
        // "and" inside a message must not split it
        assertEquals(
            listOf(Action.SendMessage("mom", "bread and butter", Messenger.WHATSAPP)),
            CommandParser.parse("send bread and butter to mom on whatsapp"),
        )
        assertEquals(
            listOf(Action.SendMessage("Mom", "see you then", Messenger.WHATSAPP)),
            CommandParser.parse("send see you then to Mom on WhatsApp"),
        )
    }

    @Test fun messages() {
        check("send hi to mom on whatsapp", Action.SendMessage("mom", "hi", Messenger.WHATSAPP))
        check("Send Good Morning to Papa on WhatsApp", Action.SendMessage("Papa", "Good Morning", Messenger.WHATSAPP))
        check("send a whatsapp message to Rahul saying I'll be late", Action.SendMessage("Rahul", "I'll be late", Messenger.WHATSAPP))
        check("message mom saying I reached", Action.SendMessage("mom", "I reached", Messenger.DEFAULT))
        check("whatsapp dad that dinner is ready", Action.SendMessage("dad", "dinner is ready", Messenger.WHATSAPP))
        check("tell Priya that I am coming", Action.SendMessage("Priya", "I am coming", Messenger.DEFAULT))
        check("send hello to Amit", Action.SendMessage("Amit", "hello", Messenger.DEFAULT))
        check("send an sms to mom saying call me", Action.SendMessage("mom", "call me", Messenger.SMS))
        check("mummy ko good night bhejo", Action.SendMessage("mummy", "good night", Messenger.DEFAULT))
        check("open chat with mom", Action.OpenChat("mom", Messenger.DEFAULT))
        check("open whatsapp chat with Rahul", Action.OpenChat("Rahul", Messenger.WHATSAPP))
        check("whatsapp mom", Action.OpenChat("mom", Messenger.WHATSAPP))
    }

    @Test fun calls() {
        check("call mom", Action.Call("mom"))
        check("call 9876543210", Action.Call("9876543210"))
        check("call papa on whatsapp", Action.Call("papa", whatsapp = true))
        check("video call Rahul", Action.Call("Rahul", whatsapp = true, video = true))
        check("whatsapp call didi", Action.Call("didi", whatsapp = true))
        check("mummy ko call karo", Action.Call("mummy"))
    }

    @Test fun payments() {
        check("pay 500 to rahul@okaxis", Action.Pay("rahul@okaxis", "500", null))
        check("pay 500 to rahul at the rate okaxis", Action.Pay("rahul@okaxis", "500", null))
        check("send 200 rupees to mom on gpay", Action.Pay("mom", "200", "gpay"))
        check("pay Rahul 150", Action.Pay("Rahul", "150", null))
        check("send money to Amit", Action.Pay("Amit", null, null))
        check("pay", Action.Pay(null, null, null))
        assertTrue(one("pay 500 to rahul@okaxis").sensitive)
    }

    @Test fun deviceControls() {
        check("turn on wifi", Action.Toggle(Feature.WIFI, true))
        check("switch off bluetooth", Action.Toggle(Feature.BLUETOOTH, false))
        check("turn the flashlight on", Action.Toggle(Feature.FLASHLIGHT, true))
        check("torch on", Action.Toggle(Feature.FLASHLIGHT, true))
        check("flashlight", Action.Toggle(Feature.FLASHLIGHT, null))
        check("enable dark mode", Action.Toggle(Feature.DARK_MODE, true))
        check("turn on do not disturb", Action.Toggle(Feature.DND, true))
        check("turn off mobile data", Action.Toggle(Feature.MOBILE_DATA, false))
        check("torch jalao", Action.Toggle(Feature.FLASHLIGHT, true))
        check("wifi band karo", Action.Toggle(Feature.WIFI, false))
        check("volume up", Action.Volume(Level.UP))
        check("turn up the volume", Action.Volume(Level.UP))
        check("set volume to 40", Action.Volume(Level.SET, 40))
        check("max volume", Action.Volume(Level.SET, 100))
        check("mute", Action.Volume(Level.MUTE))
        check("awaaz badhao", Action.Volume(Level.UP))
        check("brightness 70 percent", Action.Brightness(Level.SET, 70))
        check("make the screen brighter", Action.Brightness(Level.UP))
        check("go back", Action.Global(GlobalKind.BACK))
        check("go home", Action.Global(GlobalKind.HOME))
        check("show notifications", Action.Global(GlobalKind.NOTIFICATIONS))
        check("take a screenshot", Action.Global(GlobalKind.SCREENSHOT))
        check("screenshot lo", Action.Global(GlobalKind.SCREENSHOT))
        check("lock the phone", Action.Global(GlobalKind.LOCK))
        check("recent apps", Action.Global(GlobalKind.RECENTS))
        assertEquals(List(3) { Action.Global(GlobalKind.BACK) }, CommandParser.parse("go back 3 times"))
    }

    @Test fun mediaSearchMaps() {
        check("pause", Action.Media(MediaKey.PAUSE))
        check("play", Action.Media(MediaKey.PLAY))
        check("next song", Action.Media(MediaKey.NEXT))
        check("play arijit singh songs on spotify", Action.PlayMedia("arijit singh songs", "spotify"))
        check("play despacito", Action.PlayMedia("despacito", null))
        check("search cute cats on youtube", Action.Search("cute cats", "youtube"))
        check("google weather in delhi", Action.Search("weather in delhi", null))
        check("search iphone 16 on amazon", Action.Search("iphone 16", "amazon"))
        check("navigate to india gate", Action.Navigate("india gate", null))
        check("where is the nearest atm", Action.ShowMap("nearest atm"))
        check("pizza near me", Action.ShowMap("pizza near me"))
        check("find settings on the screen", Action.FindOnScreen("settings"))
    }

    @Test fun alarmsTimers() {
        check("set an alarm for 7 am", Action.SetAlarm(7, 0, null))
        check("set alarm for 6:30 pm for gym", Action.SetAlarm(18, 30, "gym"))
        check("wake me up at 5:45", Action.SetAlarm(5, 45, null))
        check("set a timer for 5 minutes", Action.SetTimer(300, null))
        check("set timer for 1 hour and 30 minutes", Action.SetTimer(5400, null))
        check("set a timer for 10 minutes for pasta", Action.SetTimer(600, "pasta"))
        check("remind me to drink water in 20 minutes", Action.SetTimer(1200, "drink water"))
        check("remind me to call mom at 8 pm", Action.SetAlarm(20, 0, "call mom"))
    }

    @Test fun screenControl() {
        check("tap on login", Action.Tap("login"))
        check("click the follow back button", Action.Tap("follow back"))
        check("press ok", Action.Tap("ok"))
        check("skip ad", Action.Tap("skip ad"))
        check("tap the first result", Action.TapNth(1))
        check("open the second chat", Action.TapNth(2))
        check("long press hello", Action.Tap("hello", long = true))
        check("double tap", Action.TapPoint(Spot.CENTER, double = true))
        check("type Hello World", Action.Type("Hello World"))
        check("send", Action.Submit("send"))
        check("press enter", Action.Submit(null))
        check("clear the text", Action.EditText(TextOp.CLEAR))
        check("read the screen", Action.ReadScreen)
        check("read my notifications", Action.ReadNotifications)
        check("take a selfie", Action.Camera(selfie = true, video = false))
        check("click a photo", Action.Camera(selfie = false, video = false))
    }

    @Test fun chatAndMeta() {
        check("hi", Action.Talk(Chat.GREET))
        check("hey bixi", Action.Talk(Chat.GREET))
        check("thank you", Action.Talk(Chat.THANKS))
        check("what time is it", Action.Info(InfoKind.TIME))
        check("kitne baje hai", Action.Info(InfoKind.TIME))
        check("how much battery is left", Action.Info(InfoKind.BATTERY))
        check("help", Action.Help)
        check("again", Action.Repeat)
        check(
            "when I say good night then turn on dnd and lock the screen",
            Action.Teach("good night", "turn on dnd and lock the screen"),
        )
        assertTrue(one("blah blah something") is Action.Unknown)
    }

    @Test fun yesNo() {
        assertEquals(true, Normalizer.yesNo("yes"))
        assertEquals(true, Normalizer.yesNo("haan bhejo"))
        assertEquals(true, Normalizer.yesNo("Yes please."))
        assertEquals(false, Normalizer.yesNo("no"))
        assertEquals(false, Normalizer.yesNo("cancel"))
        assertNull(Normalizer.yesNo("banana"))
    }

    @Test fun fuzzyAndTime() {
        assertEquals(1.0, Fuzzy.score("WhatsApp", "whatsapp"), 0.0001)
        assertTrue(Fuzzy.score("what's app", "WhatsApp") > 0.9)
        assertTrue(Fuzzy.score("insta", "Instagram") >= 0.8)
        assertTrue(Fuzzy.score("yono", "YONO SBI") >= 0.8)
        assertTrue(Fuzzy.score("calculator", "WhatsApp") < 0.5)
        assertTrue("mummy" in Fuzzy.nameVariants("Mom"))
        assertEquals(TimeParser.Clock(19, 45), TimeParser.findClock("at 7:45 pm"))
        assertEquals(TimeParser.Clock(6, 30), TimeParser.findClock("half past 6 in the morning"))
        assertNull(TimeParser.findClock("in 5 minutes"))
        assertEquals(90, TimeParser.findDuration("90 seconds"))
        assertEquals(1800, TimeParser.findDuration("half an hour"))
    }
}
