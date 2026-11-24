package com.bixi.agent.nlu

/**
 * Bixi's offline "brain": turns what you said or typed into [Action]s with plain rules.
 * No network, no AI model — every rule is below, so it is easy to add your own.
 */
object CommandParser {

    fun parse(raw: String): List<Action> {
        val full = Normalizer.clean(raw)
        if (full.isBlank()) return emptyList()
        val cleaned = Normalizer.stripWake(full)
        if (cleaned.isBlank()) return listOf(Action.Talk(Chat.GREET))

        // "when I say X then do Y" must not be split on its "then".
        TEACH.matchEntire(Normalizer.stripFillers(cleaned))?.let { m ->
            return listOf(Action.Teach(m.g(1), m.g(2)))
        }

        val out = mutableListOf<Action>()
        for (part in Splitter.split(cleaned)) {
            val prev = out.lastOrNull()
            for (a in parseOne(part)) {
                // "turn on wifi and bluetooth" → bluetooth inherits "on".
                out += if (a is Action.Toggle && a.on == null && prev is Action.Toggle && prev.on != null) a.copy(on = prev.on) else a
            }
        }
        return out
    }

    fun parseOne(input: String): List<Action> {
        val trimmed = Normalizer.stripFillers(input)
        if (trimmed.isBlank()) {
            // The user only said "hey Bixi".
            return if (input.isNotBlank()) listOf(Action.Talk(Chat.GREET)) else emptyList()
        }
        val s = Normalizer.stripFillers(Normalizer.hinglish(trimmed))
        val (count, rest) = Normalizer.extractCount(s)
        val body = rest.ifBlank { s }
        val action = single(body) ?: Action.Unknown(body)
        return expand(action, count)
    }

    private fun expand(a: Action, count: Int): List<Action> {
        if (count <= 1) return listOf(a)
        val n = count.coerceAtMost(20)
        return when {
            a is Action.Swipe -> listOf(a.copy(times = a.times * count))
            a is Action.Global || a is Action.Tap || a is Action.TapPoint || a is Action.Submit -> List(n) { a }
            a is Action.Volume && (a.level == Level.UP || a.level == Level.DOWN) -> List(n) { a }
            a is Action.Brightness && (a.level == Level.UP || a.level == Level.DOWN) -> List(n) { a }
            a is Action.Media && (a.key == MediaKey.NEXT || a.key == MediaKey.PREVIOUS) -> List(n) { a }
            else -> listOf(a)
        }
    }

    // ---------------------------------------------------------------- rules

    private val TEACH = rx("""when i say (.+?)(?:,| then| do| you should| please| bixi should)\s+(.+)""")

    // meta / chat
    private val HELP = rx("""help|help me|what can you do|what can i say|show (?:me )?(?:all )?(?:the )?commands|commands|list commands|madad|kya kya kar sakte ho""")
    private val STOP = rx("""stop|stop it|stop that|stop now|stop scrolling|stop swiping|stop auto ?scroll(?:ing)?|stop liking|stop everything|cancel|cancel it|enough|that's enough|halt|quit it|shut up|be quiet|silence|chup|chup raho""")
    private val REPEAT = rx("""repeat|repeat that|again|do it again|do that again|once more|one more time|same again|phir se|fir se|dobara|ek aur baar""")
    private val GREET = rx("""(?:hi|hello|hey|hey there|hello there|hii+|helo|namaste|namaskar|yo|sup|good (?:morning|afternoon|evening))(?: there)?""")
    private val THANKS = rx("""thanks?|thank you(?: so much| very much)?|thanks a lot|thx|shukriya|dhanyavaad|dhanyavad|great job|good job|well done|nice work|awesome|perfect""")
    private val IDENTITY = rx("""who are you|what(?:'s| is) your name|what are you|tum kaun ho|introduce yourself|tell me about yourself""")
    private val WAIT = rx("""(?:wait|hold on|pause for)(?: for)?(?: (\d+|a|one|two|three|five|ten))?(?: ?(?:s|sec|secs|seconds?|moment|bit))?""")
    private val BUBBLE_HIDE = rx("""(?:hide|remove|dismiss)(?: the)? (?:bubble|robot|bixi|yourself|floating (?:robot|bubble|icon|button))|go away|hide""")
    private val BUBBLE_SHOW = rx("""(?:show|bring back|unhide)(?: the)? (?:bubble|robot|bixi|yourself|floating (?:robot|bubble|icon|button))|come back""")

    // info
    private val TIME = rx("""(?:what(?:'s| is) the )?time(?: is it)?(?: now| right now)?|what time is it(?: now)?|tell me the time|current time|time please""")
    private val DATE = rx("""(?:what(?:'s| is) )?(?:the |today's )?date(?: today)?|what(?:'s| is) today(?:'s date)?|what day is (?:it|today)|which day is (?:it|today)|tell me the date|today's date""")
    private val BATTERY = rx("""(?:how much |what(?:'s| is) (?:my |the )?)?(?:battery|charge)(?: level| percentage| percent| left| status| remaining| life)?(?: is (?:left|there|remaining))?|how much battery(?: is)?(?: left| remaining)?(?: do i have)?|check (?:the |my )?battery|battery level""")

    // reading
    private val READ_NOTIFS = rx("""(?:read|check|tell)(?: me)?(?: out)?(?: my| the| all)? (?:new |latest |recent |unread )?(?:notifications?|messages)|what are my notifications|any (?:new )?(?:notifications|messages)""")
    private val READ_SCREEN = rx("""read(?: out| aloud| me| it| this)?(?: the| this| my)?(?: (?:screen|page|text|everything|message|article|post|it|this))?(?: (?:aloud|out loud|to me))?|what(?:'s| is) on (?:the |my |this )?screen|what do you see|describe (?:the |this )?screen|what(?:'s| is) (?:written|showing)(?: here| on (?:the )?screen)?""")

    // media keys
    private val NEXT_SONG = rx("""(?:play )?(?:the )?(?:next|skip)(?: the| this)? (?:song|track|music|gaana)|skip (?:this |the )?(?:song|track)""")
    private val PREV_SONG = rx("""(?:play )?(?:the )?(?:previous|prev|last)(?: the)? (?:song|track|gaana)""")
    private val PAUSE = rx("""pause(?: the| this)?(?: (?:music|song|video|reel|short|it|this|media|playback|gaana))?|stop (?:the )?(?:music|song|video|playback|media|gaana)""")

    // stories
    private val NEXT_STORY = rx("""(?:(?:go to|show(?: me)?) )?(?:the )?(?:next|skip)(?: the| this)? (?:story|stories|status)|skip (?:this |the )?(?:story|status)""")
    private val PREV_STORY = rx("""(?:(?:go back to|go to|show(?: me)?) )?(?:the )?(?:previous|prev|last)(?: the)? (?:story|status)""")

    // scrolling
    private val AUTO = rx("""(?:(?:start|begin|turn on|enable) )?(?:auto ?scroll(?:ing)?|auto ?swipe|auto ?play(?: reels| shorts)?|keep (?:on )?(?:scrolling|swiping|going|watching|playing)|scroll automatically|keep (?:showing|playing) (?:me )?(?:reels|shorts|videos))(.*)""")
    private val SCROLL_EDGE = rx("""(?:scroll|go|jump|take me|move)(?: all the way)?(?: back)?(?: up| down)? to (?:the )?(top|bottom|end|start|beginning|very top|very bottom)(?: of (?:the )?(?:page|list|screen|chat|feed))?""")
    private const val COUNT_WORDS = "\\d+|one|two|three|four|five|six|seven|eight|nine|ten"
    private const val MEDIA_NOUNS = "reel|reels|video|videos|short|shorts|post|posts|one|ones|clip|clips|item|items|photo|photos|pic|pics|tiktok"
    private val NEXT = rx("""(?:(?:go to|show(?: me)?|play|watch) )?(?:the )?(?:next|skip|agla|agli|aage|aagla)(?: ($COUNT_WORDS))?(?: (?:$MEDIA_NOUNS))?|skip (?:this|it|that)(?: (?:$MEDIA_NOUNS))?|swipe (?:to )?(?:the )?next(?: (?:$MEDIA_NOUNS))?""")
    private val PREV = rx("""(?:(?:go back to|go to|show(?: me)?|play|watch) )?(?:the )?(?:previous|prev|last|pichla|pichli)(?: ($COUNT_WORDS))?(?: (?:$MEDIA_NOUNS))?|back to (?:the )?(?:previous|last)(?: (?:$MEDIA_NOUNS))?""")
    private val SCROLL_A = rx("""(scroll|swipe|slide|flick)(?: the (?:screen|page|feed|list))?(?: (up|down|left|right|upwards?|downwards?|forward|backward|back))?(?: (?:a )?(little bit|little|bit|lot|more|slightly|fully|all the way|fast|slowly))?(?: (?:by )?(\d+))?""")
    private val SCROLL_B = rx("""(?:go|move)(?: the (?:screen|page))? (up|down|left|right|upwards?|downwards?)(?: (?:a )?(little bit|little|bit|lot|more))?""")

    // system navigation
    private val BACK = rx("""(?:(?:go|press|hit|tap|navigate) )?back(?: button)?|previous screen|go to (?:the )?previous screen""")
    private val HOME = rx("""(?:(?:go|press|take me|back|return) )?(?:to )?(?:the )?home(?: screen| page| button)?|minimi[sz]e(?: (?:the |this )?app)?|close (?:this |the |current |the current )?app|close (?:it|this)|exit(?: (?:the |this )?app)?|leave (?:this |the )?app""")
    private val RECENTS = rx("""(?:(?:show|open|go to) )?(?:the |my )?(?:recent apps|recents|recent|recent screens|multi ?tasking|app switcher|overview|open apps|running apps)""")
    private val CLOSE_ALL = rx("""(?:close|clear|kill|remove)(?: all| every)(?: the)?(?: recent| background| open| running)? apps|clear (?:all )?recents?|clear (?:the )?(?:ram|memory)""")
    private val NOTIFS = rx("""(?:(?:show|open|pull down|see|expand) )?(?:my |the )?notifications?(?: panel| shade| bar| center| centre)?""")
    private val QUICK = rx("""(?:(?:open|show|pull down) )?(?:the )?(?:quick (?:settings|panel|toggles|tiles)|control (?:centre|center|panel))""")
    private val LOCK = rx("""lock(?: the| my)?(?: screen| phone| device| mobile)?|screen off|(?:turn|switch) off (?:the )?(?:screen|display)|phone lock karo""")
    private val SCREENSHOT = rx("""(?:(?:take|capture|grab|get) )?(?:a )?screenshot|capture (?:the )?screen""")
    private val POWER = rx("""(?:(?:open|show) )?(?:the )?power (?:menu|options|dialog|button menu)""")
    private val SPLIT = rx("""(?:(?:toggle|enable|open|start) )?split ?screen(?: mode)?""")

    // social
    private val UNLIKE = rx("""(?:unlike|un like|remove (?:the |my )?like|take back (?:the |my )?like|undo (?:the )?like)(?: (?:this|it|that|the \w+|this \w+))?""")
    private val DISLIKE = rx("""dislike(?: (?:this|it|that|the video|this video|this short))?|thumbs? down""")
    private const val SOCIAL_NOUNS = "one|reel|reels|video|post|photo|pic|picture|short|story|status|comment|link"
    private val LIKE = rx("""(?:like|heart|love|lyk)(?: (?:this|it|that|the))?(?: (?:$SOCIAL_NOUNS))?(?: (?:karo|kar do|kardo|please))?|give (?:it |this )?a (?:like|heart)|(?:hit|smash) (?:the |that )?like(?: button)?|(?:press|tap|click|hit)(?: on)? (?:the )?(?:like|heart)(?: button| icon)?|double tap to like""")
    private const val SHARE_APPS = "whatsapp|instagram|insta|telegram|messenger|snapchat|facebook|sms|messages|gmail|email|dm"
    private val SHARE_TO = rx("""(?:send|forward|share)(?: (?:this|it|that|the))?(?: (?:$SOCIAL_NOUNS))? (?:to|with) (.+?)(?: (?:on|via|using|through|in) ($SHARE_APPS))?""")
    private val SHARE = rx("""share(?: (?:this|it|that|the))?(?: (?:$SOCIAL_NOUNS))?(?: (?:on|via|to) ($SHARE_APPS))?(?: (?:karo|kar do))?""")
    private val COMMENTS_OPEN = rx("""(?:(?:open|show|see|read|check|view)(?: the| all)? )?comments?(?: section)?""")
    private val COMMENT = rx("""(?:comment|write (?:a )?comment|post (?:a )?comment|add (?:a )?comment|reply)(?: (?:on|to|in) (?:this|it|the \w+|this \w+))?(?: (?:saying|that says|that|with|as))?:? (.+)""")
    private val UNFOLLOW = rx("""(?:unfollow|unsubscribe)(?: from)?(?: (?:this|him|her|them|it|the))?(?: (?:account|page|user|channel|person|creator|guy|girl|one))?""")
    private val FOLLOW = rx("""(?:follow|subscribe)(?: to)?(?: (?:this|him|her|them|it|the))?(?: (?:account|page|user|channel|person|creator|guy|girl|one))?(?: (?:karo|kar do))?""")
    private val SAVE = rx("""(?:save|bookmark)(?: (?:this|it|that|the))?(?: (?:$SOCIAL_NOUNS))?(?: (?:for later|to (?:my )?(?:collection|saved|favou?rites)))?|add (?:this |it )?to (?:saved|collection|watch later|favou?rites)""")

    // money
    private const val PAY_APPS = "google pay|gpay|phonepe|paytm|bhim|upi|amazon pay|cred|whatsapp|super money|mobikwik"
    private val PAY_AMT = rx("""(?:pay|send|transfer|give)(?: (?:rs\.?|inr|₹|rupees))? ?(\d+(?:[.,]\d+)?)(?: ?(?:rs|rupees?|inr|bucks|rupaye|rupay))? to (.+?)(?: (?:on|using|via|through|with|from|by) ($PAY_APPS))?""")
    private val PAY_NAME_AMT = rx("""pay (.+?) (?:rs\.? ?|₹ ?)?(\d+(?:[.,]\d+)?)(?: ?(?:rs|rupees?|inr|bucks))?(?: (?:on|using|via|through) ($PAY_APPS))?""")
    private val PAY_MONEY = rx("""(?:send|transfer|give) (?:some )?money to (.+?)(?: (?:on|using|via|through) ($PAY_APPS))?""")
    private val PAY_BARE = rx("""(?:pay|make (?:a )?payment to|pay to)(?: (.+?))?(?: (?:on|using|via|through) ($PAY_APPS))?""")

    // calls
    private val VIDEO_CALL = rx("""(?:(?:make|start) (?:a )?)?(?:whatsapp )?(?:video call|video chat|vc|video calling)(?: to| with)? (.+?)(?: (?:on|via|using) (?:whatsapp|wa))?""")
    private val WA_CALL = rx("""(?:(?:make|start) (?:a )?)?(?:whatsapp|wa) (?:voice )?call(?: to| with)? (.+)|voice call (.+?)(?: on whatsapp)?""")
    private val CALL = rx("""(?:call|dial|ring|call up|give a call to|make a call to)(?: to)? (.+?)(?: (?:on|via|using|from) (whatsapp|wa|phone|mobile|sim ?1|sim ?2|normal|regular|cellular))?""")

    // chats & messages
    private const val MSG_APPS = "whatsapp|wa|sms|text|messages|telegram"
    private val OPEN_CHAT_A = rx("""(?:(?:open|show|go to|start)(?: my| the| a)? )?(?:($MSG_APPS) )?(?:chat|conversation|messages?|chats) (?:with|of|from|for) (.+?)(?: on ($MSG_APPS))?""")
    private val OPEN_CHAT_B = rx("""open (.+?)(?:'s)? (?:whatsapp )?chat(?: on whatsapp)?|(?:open )?whatsapp (?:chat )?(?:of|for|with) (.+)""")
    private val MSG_A = rx("""(?:send|write)(?: an?)? (whatsapp|sms|text)(?: message| msg)? to (.+?) (?:saying|that says|which says|that|says|with|:) (.+)""")
    private val MSG_B = rx("""(?:send|write)(?: an?)? (?:message|msg|text) to (.+?)(?: on ($MSG_APPS))? (?:saying|that says|which says|that|says|with|:) (.+)""")
    private val MSG_C = rx("""(?:send|tell|message|text|write|whatsapp)(?: an?)? (.+?) to (.+?) (?:on|via|using|through|in) ($MSG_APPS)""")
    private val MSG_D = rx("""(whatsapp|message|msg|text|sms|ping)(?: to)? (.+?)(?: on ($MSG_APPS))? (?:saying|that says|which says|that|says|and say|and tell (?:him|her|them)|:) (.+)""")
    private val MSG_E = rx("""tell (.+?) (?:that|saying|to) (.+)""")
    private val MSG_F = rx("""(?:send|say)(?: an?)? (.+?) to (.+)""")
    private val MSG_OPEN = rx("""(whatsapp|message|msg|text|sms|chat with|ping) (.+?)(?: on ($MSG_APPS))?""")

    // sound & screen
    private val MUTE = rx("""mute(?: the)?(?: (?:phone|sound|volume|audio|media|video|reel|it|this|music|everything|all))?|sound off""")
    private val UNMUTE = rx("""unmute(?: the)?(?: (?:phone|sound|volume|audio|media|video|reel|it|this|music))?|sound on""")
    private val VOL_MAX = rx("""(?:max|maximum|full|highest)(?: the)? (?:volume|sound)|(?:(?:set|turn|make) )?(?:the )?(?:volume|sound) (?:to )?(?:max|maximum|full|highest|100 ?%?)""")
    private val VOL_MIN = rx("""(?:min|minimum|lowest)(?: the)? (?:volume|sound)|(?:(?:set|turn|make) )?(?:the )?(?:volume|sound) (?:to )?(?:min|minimum|lowest|zero)""")
    private val VOL_SET = rx("""(?:(?:set|change|make|put|turn|keep) (?:the )?)?(?:volume|sound)(?: level)? (?:to |at )?(\d{1,3})(?: ?%| percent)?""")
    private val VOL_UP = rx("""(?:(?:turn|crank|pump) )?(?:the )?(?:volume|sound|awaaz|awaz) (?:up|higher|louder|increase)|(?:increase|raise|boost|turn up|pump up|crank up)(?: the)? (?:volume|sound)|(?:a bit |little )?louder|make it louder|turn it up""")
    private val VOL_DOWN = rx("""(?:(?:turn) )?(?:the )?(?:volume|sound|awaaz|awaz) (?:down|lower|decrease|softer)|(?:decrease|lower|reduce|turn down)(?: the)? (?:volume|sound)|(?:a bit |little )?(?:softer|quieter)|make it (?:quieter|softer)|turn it down""")
    private val BR_MAX = rx("""(?:max|maximum|full|highest)(?: the)? (?:screen )?brightness|(?:(?:set|turn|make) )?(?:the )?(?:screen )?brightness (?:to )?(?:max|maximum|full|highest|100 ?%?)""")
    private val BR_MIN = rx("""(?:min|minimum|lowest)(?: the)? (?:screen )?brightness|(?:(?:set|turn|make) )?(?:the )?(?:screen )?brightness (?:to )?(?:min|minimum|lowest|zero)""")
    private val BR_SET = rx("""(?:(?:set|change|make|put|turn|keep) (?:the )?)?(?:screen )?brightness(?: level)? (?:to |at )?(\d{1,3})(?: ?%| percent)?""")
    private val BR_UP = rx("""(?:(?:turn) )?(?:the )?(?:screen )?brightness (?:up|higher|increase)|(?:increase|raise|boost|turn up)(?: the)? (?:screen )?brightness|brighter|make (?:it|the screen) brighter""")
    private val BR_DOWN = rx("""(?:(?:turn) )?(?:the )?(?:screen )?brightness (?:down|lower|decrease)|(?:decrease|lower|reduce|turn down|dim)(?: the)? (?:screen )?(?:brightness)?|darker|dimmer|make (?:it|the screen) (?:darker|dimmer)""")

    // alarms
    private val ALARM_HINT = rx(""".*\b(?:alarm|wake me)\b.*""")
    private val TIMER_HINT = rx(""".*\btimer\b.*""")
    private val REMIND_AT = rx("""remind me (?:to )?(.+?) (?:at|by) (.+)""")
    private val REMIND_IN = rx("""remind me (?:to )?(.+?) (?:in|after) (.+)""")
    private val REMIND_IN_PRE = rx("""remind me (?:in|after) (.+?) (?:to|that|about) (.+)""")
    private val REMIND_AT_PRE = rx("""remind me at (.+?) (?:to|that|about) (.+)""")
    private val LABEL = rx("""\b(?:called|named|labell?ed|for|to|saying|about)\s+([a-z][a-z ]{1,40})$""")
    private val NOT_LABEL = setOf("tomorrow", "today", "morning", "evening", "night", "tonight", "am", "pm", "afternoon")

    // camera
    private val SELFIE = rx("""(?:(?:take|click|capture|shoot|snap)(?: an?| my| a quick)? )?selfie|(?:open )?(?:the )?front camera""")
    private val VIDEO_REC = rx("""(?:record|shoot|start recording|capture|take)(?: an?)? video|start video recording""")
    private val PHOTO = rx("""(?:take|click|capture|shoot|snap)(?: an?| the| one)? (?:photo|picture|pic|snap|snapshot|image|shot)(?: (?:of|for) (?:this|me|it|us))?|(?:press|tap|click)(?: the)? (?:shutter|capture button)|say cheese|cheese""")

    // maps
    private val NAV = rx("""(?:navigate|navigation|directions?|get directions|drive|route|guide me|show (?:me )?(?:the )?(?:way|route|directions)|how (?:do i|to|can i) (?:get|go|reach))(?: me)? (?:to|for|till|towards) (.+?)(?: (?:by|via|using|on) (car|bike|walk|walking|foot|transit|bus|train|two ?wheeler|scooter|metro))?""")
    private val MAP = rx("""(?:show|find|search|open|locate)(?: me)? (.+?) on (?:the )?(?:google )?maps?|where is (?:the )?(.+)|(?:(?:find|show(?: me)?|search(?: for)?) )?(.+?) near(?:by| me)|(?:(?:find|show(?: me)?) )?nearby (.+)|open maps? (?:for|of|to) (.+)""")

    // toggles
    private val TOGGLE_1 = rx("""(?:turn|switch|put|set|make|keep)(?: the| my)? (on|off) (?:the |my )?(.+?)""")
    private val TOGGLE_2 = rx("""(?:turn|switch|put|set|make|keep)(?: the| my)? (.+?) (on|off)""")
    private val TOGGLE_3 = rx("""(enable|disable|activate|deactivate|start|stop|connect|disconnect|open|close)(?: the| my)? (.+?)""")
    private val TOGGLE_4 = rx("""(.+?) (on|off)""")
    private val TOGGLE_5 = rx("""toggle(?: the| my)? (.+?)""")
    private val SETTINGS_PAGE = rx("""(?:(?:open|go to|show|take me to)(?: the| my)? )?(.+?) settings?(?: page| menu)?""")

    // search
    private const val SEARCH_APPS = "youtube|yt|google|chrome|browser|internet|instagram|insta|play ?store|amazon|flipkart|spotify|maps|google maps|whatsapp|facebook|fb|twitter|x|myntra|meesho|zomato|swiggy|wikipedia|telegram|snapchat|linkedin|pinterest|reddit|netflix|hotstar|jiohotstar|prime video|settings|contacts|gallery|photos|files|my files|this app|here|jiosaavn|gaana|wynk|ajio|nykaa|blinkit|zepto|bigbasket|instamart|threads|sharechat|moj|josh|quora|bing|duckduckgo"
    private val SEARCH_IN = rx("""(?:search|look up|lookup|find|google|check|search for|look for)(?: for| about)? (.+?) (?:on|in|using|at|inside) (?:the )?($SEARCH_APPS)(?: app)?""")
    private val FIND_SCREEN = rx("""(?:find|look for|locate|spot|search for) (.+?) on (?:the |this )?(?:screen|page)|scroll (?:down )?(?:until|till|untill|to) (?:you (?:find|see|reach) )?(?:the )?(.+)|find (.+)""")
    private val SEARCH = rx("""(?:search|google|look up|lookup|search for|search about|search the web for|web search)(?: for| about)? (.+)""")

    // sections inside apps
    private const val SECTION_APPS = "instagram|insta|facebook|fb|youtube|yt|snapchat"
    private val OPEN_REELS = rx("""(?:(?:open|show|start|play|watch|go to|launch)(?: me)?(?: the| some)? )?(?:($SECTION_APPS) )?(reels?|shorts?|spotlight)(?: (?:on|in|of|from) ($SECTION_APPS))?""")
    private val STATUS = rx("""(?:(?:open|show|see|check|watch|go to)(?: my| the)? )?(?:whatsapp )?(?:status|statuses|updates)(?: on whatsapp| tab)?""")

    // play
    private val PLAY_BARE = rx("""(?:play|resume|continue|unpause)(?: the| this)?(?: (?:music|song|songs|video|reel|short|it|this|media|playback|gaana))?""")
    private const val MUSIC_APPS = "youtube|yt|youtube music|yt music|spotify|jiosaavn|jio saavn|saavn|gaana|wynk|amazon music|apple music|music app|music"
    private val PLAY_Q = rx("""(?:play|listen to|put on)(?: me)?(?: some| the)? (.+?)(?: (?:on|in|using|from|via) ($MUSIC_APPS))?""")

    // text editing & taps
    private val SELECT_ALL = rx("""select all(?: (?:the )?text)?|select everything""")
    private val COPY = rx("""copy(?: (?:this|it|that|the text|all|all text|everything|text))?(?: to clipboard)?""")
    private val PASTE = rx("""paste(?: (?:it|this|that|here|the text|text))?""")
    private val CUT = rx("""cut(?: (?:this|it|that|the text|all|text))?""")
    private val CLEAR = rx("""(?:clear|erase|delete|remove|wipe)(?: all)?(?: the| this| my)? (?:text|field|input|search(?: box| bar)?|typing|everything(?: typed)?|box)|clear it|clear all text""")
    private val TYPE = rx("""(?:type|write|enter|input|type in|fill in)(?: (?:in|the text|text|out|:))? (.+)""")
    private val SEND_BARE = rx("""send|send it|send now|send this|send message|send the message|hit send|press send|(?:tap|click)(?: on)? send(?: button)?""")
    private val ENTER = rx("""(?:(?:press|hit|tap) )?(?:enter|submit|return)(?: key| button)?|search it|go|go ahead""")
    private val SEARCH_BARE = rx("""(?:(?:press|hit|tap|open) )?(?:the )?search(?: button| bar| icon| box)?""")
    private val BUTTON_WORDS = rx("""(?:(?:press|tap|click) )?(ok|okay|done|yes|allow|accept|continue|confirm|agree|got it|not now|later|close|dismiss|deny|no thanks|skip ads?|skip ad|retry|try again|install|update|sign in|log in|login|reply|answer|decline)(?: button)?""")
    private val DOUBLE_TAP = rx("""double (?:tap|click)(?: (?:on )?(?:the )?(?:screen|center|centre|middle|it|this|video|reel|photo))?""")
    private val TAP_SPOT = rx("""(?:tap|click|touch|press)(?: on)?(?: the)? (center|centre|middle|top|bottom|left side|right side|left|right)(?: (?:side|part))?(?: of (?:the )?screen)?""")
    private val TAP_NTH = rx("""(?:tap|click|open|select|play|choose|press|watch)(?: on)? (?:the )?(first|second|third|fourth|fifth|sixth|1st|2nd|3rd|4th|5th|6th|last|top)(?: (?:one|result|video|item|post|chat|link|option|song|reel|message|photo|contact|button|conversation|email|mail|notification|product|search result))?""")
    private val LONG_PRESS = rx("""(?:long press|long tap|long click|press and hold|hold)(?: on)?(?: the)? (.+?)(?: button| icon)?""")
    private val TAP = rx("""(?:tap|click|press|select|hit|touch|choose|push)(?: on)?(?: the)? (.+?)(?: button| icon| tab| option| link)?""")

    // apps
    private val UNINSTALL = rx("""uninstall(?: the)? (.+?)(?: app)?""")
    private val CLOSE_APP = rx("""(?:close|exit|quit|kill|shut)(?: the)? (.+?)(?: app)?""")
    private val OPEN = rx("""(?:open|launch|start|run|show|go to|switch to|take me to|load|bring up|open up|use)(?: up)?(?: me)?(?: the| my)?(?: app)? (.+?)(?: app| application)?""")

    private val ordinals = mapOf(
        "first" to 1, "1st" to 1, "top" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3,
        "fourth" to 4, "4th" to 4, "fifth" to 5, "5th" to 5, "sixth" to 6, "6th" to 6, "last" to -1,
    )

    private fun messenger(word: String?): Messenger = when (word?.lowercase()) {
        "whatsapp", "wa" -> Messenger.WHATSAPP
        "sms", "text", "messages" -> Messenger.SMS
        else -> Messenger.DEFAULT
    }

    private fun scrollDir(verb: String, word: String): Dir {
        val w = word.lowercase().removeSuffix("s").removeSuffix("ward")
        val fingerVerb = verb.lowercase() in setOf("swipe", "slide", "flick")
        return if (fingerVerb) when (w) {
            "down", "back" -> Dir.DOWN
            "left" -> Dir.LEFT
            "right" -> Dir.RIGHT
            else -> Dir.UP
        } else when (w) {
            "up", "back" -> Dir.DOWN
            "left" -> Dir.RIGHT
            "right" -> Dir.LEFT
            else -> Dir.UP
        }
    }

    private fun distance(word: String): Distance = when {
        word.isEmpty() -> Distance.NORMAL
        word.contains("little") || word.contains("bit") || word.contains("slight") || word.contains("slow") -> Distance.SMALL
        word.contains("lot") || word.contains("full") || word.contains("all") || word.contains("fast") -> Distance.LARGE
        else -> Distance.NORMAL
    }

    private fun percent(raw: String): Int? = raw.toIntOrNull()?.coerceIn(0, 100)

    private fun label(text: String): String? {
        val m = LABEL.find(text.lowercase()) ?: return null
        val l = m.g(1)
        return l.takeUnless { it in NOT_LABEL || it.split(' ').any { w -> w in NOT_LABEL } }
    }

    private fun reelsSection(app: String, kind: String): Action {
        val k = kind.lowercase()
        val section = when {
            k.startsWith("short") -> "Shorts"
            k.startsWith("spot") -> "Spotlight"
            else -> "Reels"
        }
        val target = app.ifBlank {
            when (section) {
                "Shorts" -> "youtube"
                "Spotlight" -> "snapchat"
                else -> "instagram"
            }
        }
        return Action.OpenSection(target, section)
    }

    /** One command → one action, or null if nothing matched. */
    fun single(s: String): Action? {
        fun Regex.on(block: (MatchResult) -> Action?): Action? = matchEntire(s)?.let(block)

        // ---- meta
        if (HELP.matches(s)) return Action.Help
        if (STOP.matches(s)) return Action.Stop
        if (REPEAT.matches(s)) return Action.Repeat
        if (IDENTITY.matches(s)) return Action.Talk(Chat.IDENTITY)
        if (THANKS.matches(s)) return Action.Talk(Chat.THANKS)
        if (GREET.matches(s)) return Action.Talk(Chat.GREET)
        WAIT.on { m -> Action.Wait((TimeParser.number(m.g(1)) ?: 2).coerceIn(1, 60)) }?.let { return it }
        if (BUBBLE_HIDE.matches(s)) return Action.Bubble(false)
        if (BUBBLE_SHOW.matches(s)) return Action.Bubble(true)

        // ---- info & reading
        if (TIME.matches(s)) return Action.Info(InfoKind.TIME)
        if (DATE.matches(s)) return Action.Info(InfoKind.DATE)
        if (BATTERY.matches(s)) return Action.Info(InfoKind.BATTERY)
        if (READ_NOTIFS.matches(s)) return Action.ReadNotifications
        if (READ_SCREEN.matches(s)) return Action.ReadScreen

        // ---- media keys & stories
        if (NEXT_SONG.matches(s)) return Action.Media(MediaKey.NEXT)
        if (PREV_SONG.matches(s)) return Action.Media(MediaKey.PREVIOUS)
        if (PAUSE.matches(s)) return Action.Media(MediaKey.PAUSE)
        if (NEXT_STORY.matches(s)) return Action.TapPoint(Spot.RIGHT)
        if (PREV_STORY.matches(s)) return Action.TapPoint(Spot.LEFT)

        // ---- scrolling
        AUTO.on { m ->
            val rest = m.g(1).lowercase()
            val secs = Regex("""(\d+)\s*(?:s\b|sec|second)""").find(rest)?.groupValues?.get(1)?.toIntOrNull()
            Action.AutoScroll(secs?.coerceIn(2, 120), rest.contains("lik") || rest.contains("heart"))
        }?.let { return it }
        SCROLL_EDGE.on { m -> Action.ScrollToEdge(m.g(1).lowercase() in setOf("top", "start", "beginning", "very top")) }?.let { return it }
        NEXT.on { m -> Action.Swipe(Dir.UP, (TimeParser.number(m.g(1)) ?: 1).coerceIn(1, 50)) }?.let { return it }
        PREV.on { m -> Action.Swipe(Dir.DOWN, (TimeParser.number(m.g(1)) ?: 1).coerceIn(1, 50)) }?.let { return it }
        SCROLL_A.on { m ->
            Action.Swipe(scrollDir(m.g(1), m.g(2)), m.g(4).toIntOrNull()?.coerceIn(1, 50) ?: 1, distance(m.g(3).lowercase()))
        }?.let { return it }
        SCROLL_B.on { m -> Action.Swipe(scrollDir("scroll", m.g(1)), 1, distance(m.g(2).lowercase())) }?.let { return it }

        // ---- system navigation
        if (BACK.matches(s)) return Action.Global(GlobalKind.BACK)
        if (HOME.matches(s)) return Action.Global(GlobalKind.HOME)
        if (CLOSE_ALL.matches(s)) return Action.CloseAllApps
        if (RECENTS.matches(s)) return Action.Global(GlobalKind.RECENTS)
        if (NOTIFS.matches(s)) return Action.Global(GlobalKind.NOTIFICATIONS)
        if (QUICK.matches(s)) return Action.Global(GlobalKind.QUICK_SETTINGS)
        if (LOCK.matches(s)) return Action.Global(GlobalKind.LOCK)
        if (SCREENSHOT.matches(s)) return Action.Global(GlobalKind.SCREENSHOT)
        if (POWER.matches(s)) return Action.Global(GlobalKind.POWER)
        if (SPLIT.matches(s)) return Action.Global(GlobalKind.SPLIT)

        // ---- social media
        if (UNLIKE.matches(s)) return Action.Like(undo = true)
        if (DISLIKE.matches(s)) return Action.Dislike
        if (LIKE.matches(s)) return Action.Like()
        SHARE_TO.on { m -> Action.Share(m.g(1), m.g(2).ifEmpty { null }) }?.let { return it }
        SHARE.on { m -> Action.Share(null, m.g(1).ifEmpty { null }) }?.let { return it }
        if (COMMENTS_OPEN.matches(s)) return Action.Comment(null)
        COMMENT.on { m -> Action.Comment(m.g(1)) }?.let { return it }
        if (UNFOLLOW.matches(s)) return Action.Follow(undo = true)
        if (FOLLOW.matches(s)) return Action.Follow()
        if (SAVE.matches(s)) return Action.Save

        // "open the second chat", "play the first video"
        TAP_NTH.on { m -> ordinals[m.g(1).lowercase()]?.let { Action.TapNth(it) } }?.let { return it }

        // ---- money (before messages: "send 500 to Rahul")
        PAY_AMT.on { m -> Action.Pay(m.g(2), m.g(1).replace(',', '.'), m.g(3).ifEmpty { null }) }?.let { return it }
        PAY_NAME_AMT.on { m -> Action.Pay(m.g(1), m.g(2).replace(',', '.'), m.g(3).ifEmpty { null }) }?.let { return it }
        PAY_MONEY.on { m -> Action.Pay(m.g(1), null, m.g(2).ifEmpty { null }) }?.let { return it }
        PAY_BARE.on { m -> Action.Pay(m.g(1).ifEmpty { null }, null, m.g(2).ifEmpty { null }) }?.let { return it }

        // ---- calls
        VIDEO_CALL.on { m -> Action.Call(m.g(1), whatsapp = true, video = true) }?.let { return it }
        WA_CALL.on { m -> Action.Call(m.g(1).ifEmpty { m.g(2) }, whatsapp = true) }?.let { return it }
        CALL.on { m -> Action.Call(m.g(1), whatsapp = m.g(2).lowercase().let { it == "whatsapp" || it == "wa" }) }?.let { return it }

        // ---- chats & messages
        OPEN_CHAT_A.on { m -> Action.OpenChat(m.g(2), messenger(m.g(1).ifEmpty { m.g(3) })) }?.let { return it }
        OPEN_CHAT_B.on { m -> Action.OpenChat(m.g(1).ifEmpty { m.g(2) }, Messenger.WHATSAPP) }?.let { return it }
        MSG_A.on { m -> Action.SendMessage(m.g(2), m.g(3), messenger(m.g(1))) }?.let { return it }
        MSG_B.on { m -> Action.SendMessage(m.g(1), m.g(3), messenger(m.g(2))) }?.let { return it }
        MSG_C.on { m -> Action.SendMessage(m.g(2), m.g(1), messenger(m.g(3))) }?.let { return it }
        MSG_D.on { m ->
            val app = m.g(3).ifEmpty { m.g(1) }
            Action.SendMessage(m.g(2), m.g(4), messenger(app))
        }?.let { return it }
        MSG_E.on { m -> if (m.g(1).lowercase() in setOf("me", "myself", "us")) null else Action.SendMessage(m.g(1), m.g(2)) }?.let { return it }

        // ---- sound & brightness
        if (MUTE.matches(s)) return Action.Volume(Level.MUTE)
        if (UNMUTE.matches(s)) return Action.Volume(Level.UNMUTE)
        if (VOL_MAX.matches(s)) return Action.Volume(Level.SET, 100)
        if (VOL_MIN.matches(s)) return Action.Volume(Level.SET, 0)
        VOL_SET.on { m -> percent(m.g(1))?.let { Action.Volume(Level.SET, it) } }?.let { return it }
        if (VOL_UP.matches(s)) return Action.Volume(Level.UP)
        if (VOL_DOWN.matches(s)) return Action.Volume(Level.DOWN)
        if (BR_MAX.matches(s)) return Action.Brightness(Level.SET, 100)
        if (BR_MIN.matches(s)) return Action.Brightness(Level.SET, 0)
        BR_SET.on { m -> percent(m.g(1))?.let { Action.Brightness(Level.SET, it) } }?.let { return it }
        if (BR_UP.matches(s)) return Action.Brightness(Level.UP)
        if (BR_DOWN.matches(s)) return Action.Brightness(Level.DOWN)

        // ---- reminders, alarms, timers
        REMIND_IN_PRE.on { m -> TimeParser.findDuration(m.g(1))?.let { Action.SetTimer(it, m.g(2)) } }?.let { return it }
        REMIND_AT_PRE.on { m -> TimeParser.findClock(m.g(1))?.let { Action.SetAlarm(it.hour, it.minute, m.g(2)) } }?.let { return it }
        REMIND_IN.on { m -> TimeParser.findDuration(m.g(2))?.let { Action.SetTimer(it, m.g(1)) } }?.let { return it }
        REMIND_AT.on { m -> TimeParser.findClock(m.g(2))?.let { Action.SetAlarm(it.hour, it.minute, m.g(1)) } }?.let { return it }
        if (TIMER_HINT.matches(s)) {
            TimeParser.findDuration(s)?.let { return Action.SetTimer(it, label(s.substringAfterLast("minute").substringAfterLast("second").substringAfterLast("hour"))) }
        }
        if (ALARM_HINT.matches(s)) {
            TimeParser.findClock(s)?.let { return Action.SetAlarm(it.hour, it.minute, label(s)) }
        }

        // ---- camera
        if (SELFIE.matches(s)) return Action.Camera(selfie = true, video = false)
        if (VIDEO_REC.matches(s)) return Action.Camera(selfie = false, video = true)
        if (PHOTO.matches(s)) return Action.Camera(selfie = false, video = false)

        // ---- maps
        NAV.on { m -> Action.Navigate(m.g(1), m.g(2).ifEmpty { null }) }?.let { return it }
        MAP.on { m ->
            val near = m.g(3).ifEmpty { m.g(4) }
            when {
                m.g(1).isNotEmpty() -> Action.ShowMap(m.g(1))
                m.g(2).isNotEmpty() -> Action.ShowMap(m.g(2))
                near.isNotEmpty() -> Action.ShowMap("$near near me")
                m.g(5).isNotEmpty() -> Action.ShowMap(m.g(5))
                else -> null
            }
        }?.let { return it }

        // ---- device toggles (only for things that really are toggles)
        TOGGLE_1.on { m -> Feature.lookup(m.g(2))?.let { Action.Toggle(it, m.g(1).equals("on", true)) } }?.let { return it }
        TOGGLE_2.on { m -> Feature.lookup(m.g(1))?.let { Action.Toggle(it, m.g(2).equals("on", true)) } }?.let { return it }
        TOGGLE_3.on { m ->
            val on = m.g(1).lowercase() in setOf("enable", "activate", "start", "connect", "open")
            Feature.lookup(m.g(2))?.let { Action.Toggle(it, on) }
        }?.let { return it }
        TOGGLE_4.on { m -> Feature.lookup(m.g(1))?.let { Action.Toggle(it, m.g(2).equals("on", true)) } }?.let { return it }
        TOGGLE_5.on { m -> Feature.lookup(m.g(1))?.let { Action.Toggle(it, null) } }?.let { return it }
        SETTINGS_PAGE.on { m -> SettingsPage.lookup(m.g(1))?.let { Action.OpenSettings(it) } }?.let { return it }

        // ---- search
        SEARCH_IN.on { m -> Action.Search(m.g(1), m.g(2).lowercase()) }?.let { return it }
        FIND_SCREEN.on { m -> Action.FindOnScreen(m.g(1).ifEmpty { m.g(2) }.ifEmpty { m.g(3) }) }?.let { return it }
        SEARCH.on { m -> Action.Search(m.g(1), null) }?.let { return it }

        // ---- sections, ordinals, play
        OPEN_REELS.on { m -> reelsSection(m.g(1).ifEmpty { m.g(3) }, m.g(2)) }?.let { return it }
        if (STATUS.matches(s)) return Action.OpenSection("whatsapp", "Updates")
        if (PLAY_BARE.matches(s)) return Action.Media(MediaKey.PLAY)
        PLAY_Q.on { m -> Action.PlayMedia(m.g(1), m.g(2).ifEmpty { null }) }?.let { return it }

        // ---- message fall-backs that are greedier
        MSG_F.on { m -> Action.SendMessage(m.g(2), m.g(1)) }?.let { return it }
        MSG_OPEN.on { m -> Action.OpenChat(m.g(2), messenger(m.g(3).ifEmpty { m.g(1) })) }?.let { return it }

        // ---- text editing
        if (SELECT_ALL.matches(s)) return Action.EditText(TextOp.SELECT_ALL)
        if (COPY.matches(s)) return Action.EditText(TextOp.COPY)
        if (PASTE.matches(s)) return Action.EditText(TextOp.PASTE)
        if (CUT.matches(s)) return Action.EditText(TextOp.CUT)
        if (CLEAR.matches(s)) return Action.EditText(TextOp.CLEAR)
        TYPE.on { m -> Action.Type(m.g(1)) }?.let { return it }
        if (SEND_BARE.matches(s)) return Action.Submit("send")
        if (ENTER.matches(s)) return Action.Submit(null)
        if (SEARCH_BARE.matches(s)) return Action.Submit("search")
        BUTTON_WORDS.on { m -> Action.Tap(m.g(1)) }?.let { return it }

        // ---- taps
        if (DOUBLE_TAP.matches(s)) return Action.TapPoint(Spot.CENTER, double = true)
        TAP_SPOT.on { m ->
            val w = m.g(1).lowercase()
            val spot = when {
                w.startsWith("top") -> Spot.TOP
                w.startsWith("bottom") -> Spot.BOTTOM
                w.startsWith("left") -> Spot.LEFT
                w.startsWith("right") -> Spot.RIGHT
                else -> Spot.CENTER
            }
            Action.TapPoint(spot)
        }?.let { return it }
        LONG_PRESS.on { m -> Action.Tap(m.g(1), long = true) }?.let { return it }
        TAP.on { m -> Action.Tap(m.g(1)) }?.let { return it }

        // ---- apps
        UNINSTALL.on { m -> Action.Uninstall(m.g(1)) }?.let { return it }
        CLOSE_APP.on { m -> Action.CloseApp(m.g(1)) }?.let { return it }
        OPEN.on { m -> Action.OpenApp(m.g(1)) }?.let { return it }

        // A bare feature name: "flashlight", "wifi".
        Feature.lookup(s)?.let { return Action.Toggle(it, null) }
        return null
    }
}
