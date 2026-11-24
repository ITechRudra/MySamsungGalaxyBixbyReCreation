package com.bixi.agent.nlu

internal fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

internal fun MatchResult.g(i: Int): String = groupValues.getOrNull(i)?.trim().orEmpty()

/** Cleans raw speech-to-text output into something the rule parser can read. */
object Normalizer {

    /** Common speech-recognition spellings → the word we expect. */
    private val quirks: List<Pair<Regex, String>> = listOf(
        rx("""\bwhat'?s ?app\b|\bwhat sapp\b|\bwatsapp\b|\bwhatsap\b|\bwatts app\b|\bwhatsapp's\b""") to "whatsapp",
        rx("""\binsta ?gram\b""") to "instagram",
        rx("""\byou ?tube\b""") to "youtube",
        rx("""\bface ?book\b""") to "facebook",
        rx("""\bwi[- ]?fi\b|\bwify\b|\bwhy fi\b""") to "wifi",
        rx("""\bblue ?tooth\b""") to "bluetooth",
        rx("""\bflash ?light\b""") to "flashlight",
        rx("""\bscreen ?shot\b""") to "screenshot",
        rx("""\bg[- ]?pay\b|\bgoogle pe\b""") to "gpay",
        rx("""\bphone ?pe\b|\bphone ?pay\b""") to "phonepe",
        rx("""\bpay ?tm\b""") to "paytm",
        rx("""\s*\bat the rate(?: of)?\s*""") to "@",
        rx("""\breal\b|\briel\b""") to "reel",
        rx("""\breals\b|\brils\b|\breel's\b""") to "reels",
        rx("""\bpls\b|\bplz\b""") to "please",
        rx("""\bdo not disturb\b|\bdon't disturb\b""") to "do not disturb",
    )

    private val wakePrefix =
        rx("""^(?:(?:hey|hi|hello|ok|okay|yo|oye|o)\s+)?(?:bixi|bixie|bixy|biksi|bixby|pixie|pixi|bigsy)\b[\s,!.:]*""")
    private val politePrefix =
        rx("""^(?:please|kindly|can you|could you|would you|will you|can u|could u|i want you to|i want to|i would like to|i'd like to|i wanna|i need you to|i need to|you should|just|now|quickly|go ahead and|let's|lets|ok|okay|so|and|then|also|bixi)\s+""")
    private val politeSuffix =
        rx("""\s+(?:please|for me|now|right now|quickly|bixi|na|yaar|bhai|karo na|thanks|thank you|asap)$""")
    private val edgePunct = Regex("""^[\s.!?,;:]+|[\s.!?,;:]+$""")
    private val spaces = Regex("""\s+""")

    /** Whole-utterance cleanup: speech quirks + whitespace. Keeps the user's casing. */
    fun clean(raw: String): String {
        var s = raw.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        for ((r, rep) in quirks) s = s.replace(r, rep)
        return s.replace(spaces, " ").trim()
    }

    /** Removes a leading "hey Bixi," so it can't be mistaken for a separate command. */
    fun stripWake(s: String): String = s.trim().replace(wakePrefix, "").trim()

    /** Removes "hey bixi", "please", "can you", "...for me" and edge punctuation. */
    fun stripFillers(input: String): String {
        var s = input.trim()
        while (true) {
            val before = s
            s = s.replace(edgePunct, "")
            s = s.replace(wakePrefix, "")
            s = s.replace(politePrefix, "")
            s = s.replace(politeSuffix, "")
            s = s.trim()
            if (s == before) return s
        }
    }

    private val countRx =
        rx("""\s*\b(\d+|once|twice|thrice|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|ek|do|teen|char|paanch|panch)\s*(?:times?|baar|bar)\b|\s+x\s?(\d+)$""")

    private val countWords = mapOf(
        "once" to 1, "twice" to 2, "thrice" to 3, "ek" to 1, "do" to 2, "teen" to 3,
        "char" to 4, "paanch" to 5, "panch" to 5,
    )

    /** "scroll 5 times" → (5, "scroll"). */
    fun extractCount(s: String): Pair<Int, String> {
        val m = countRx.find(s) ?: return 1 to s
        val word = m.groupValues[1].ifEmpty { m.groupValues[2] }.lowercase()
        val n = countWords[word] ?: TimeParser.number(word) ?: 1
        return n.coerceIn(1, 50) to s.removeRange(m.range).replace(spaces, " ").trim()
    }

    private const val OPEN_HI =
        "kholo|khol do|kholiye|khol|open karo|open kar do|open kr do|start karo|launch karo|chalao|chala do"

    private val hinglish: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        rx("""ruko|ruk jao|bas|bas karo|band karo|rehne do|chhodo""") to { _ -> "stop" },
        rx("""(.+?) ko (?:video call|vc) (?:karo|kar do|lagao|laga do|milao)""") to { m -> "video call ${m.g(1)}" },
        rx("""(.+?) ko (?:call|phone|fone|kol) (?:karo|kar do|lagao|laga do|milao|karna)""") to { m -> "call ${m.g(1)}" },
        rx("""(.+?) ko (?:message|msg|whatsapp|text|sms) (?:karo|kar do|bhejo|bhej do)(?: ki| ke)? (.+)""") to { m ->
            "message ${m.g(1)} saying ${m.g(2)}"
        },
        rx("""(.+?) ko (.+?) (?:bhejo|bhej do|send karo|send kar do|bol do|bolo|keh do)(?: whatsapp (?:pe|par))?""") to { m ->
            "send ${m.g(2)} to ${m.g(1)}"
        },
        rx("""(?:(?:reel|reels|video|post|isko|ise|ye|yeh) )?like (?:karo|kar do|kardo|kr do)""") to { _ -> "like" },
        rx("""(?:(?:isko|ise|ye|yeh) )?share (?:karo|kar do|kardo|kr do)""") to { _ -> "share" },
        rx("""(?:(?:isko|ise|ye|yeh) )?save (?:karo|kar do|kardo|kr do)""") to { _ -> "save" },
        rx("""(?:agla|agli|aagla|aagli|next)(?: (?:reel|video|wala|wali))? (?:dikhao|chalao|karo|lagao)""") to { _ -> "next" },
        rx("""(?:agla|agli|aagla|aagli)(?: (?:reel|video|wala|wali))?""") to { _ -> "next" },
        rx("""(?:pichla|pichli|pichhla|pichhli|peeche wala|peeche wali|previous)(?: (?:reel|video|wala|wali))?(?: (?:dikhao|chalao|karo))?""") to { _ ->
            "previous"
        },
        rx("""(?:neeche|niche|nichay)(?: scroll)? (?:karo|kar do|jao|chalo)""") to { _ -> "scroll down" },
        rx("""(?:upar|oopar|uper)(?: scroll)? (?:karo|kar do|jao|chalo)""") to { _ -> "scroll up" },
        rx("""(?:scroll|swipe) (?:karo|kar do|kardo)""") to { _ -> "scroll down" },
        rx("""(?:screenshot|ss) (?:lo|le lo|lelo|khicho|kheecho|lena)""") to { _ -> "take screenshot" },
        rx("""selfie (?:lo|le lo|lelo|khicho|kheecho|click karo|lena)""") to { _ -> "take a selfie" },
        rx("""(?:photo|pic|picture|foto) (?:lo|le lo|lelo|khicho|kheecho|click karo|lena)""") to { _ -> "take a photo" },
        rx("""(?:awaaz|awaz|aawaz|aavaj|volume|sound) (?:badhao|badha do|tez karo|tez kar do|jyada karo|zyada karo|badha)""") to { _ ->
            "volume up"
        },
        rx("""(?:awaaz|awaz|aawaz|aavaj|volume|sound) (?:kam karo|kam kar do|dheere karo|ghatao|ghata do|kam)""") to { _ ->
            "volume down"
        },
        rx("""(?:brightness|roshni) (?:badhao|badha do|tez karo|jyada karo|zyada karo)""") to { _ -> "brightness up" },
        rx("""(?:brightness|roshni) (?:kam karo|kam kar do|ghatao|ghata do|kam)""") to { _ -> "brightness down" },
        rx("""(?:wapas|wapis|peeche|back) (?:jao|chalo|aao|karo)""") to { _ -> "go back" },
        rx("""(?:home|ghar|home screen) (?:pe |par )?(?:jao|chalo|le chalo)""") to { _ -> "go home" },
        rx("""(?:time|samay|tym) (?:kya hua|kya hai|kya ho gaya|batao)|kitne baje(?: hai| hain| hue)?|kya time hua(?: hai)?""") to { _ ->
            "what time is it"
        },
        rx("""(?:aaj )?(?:date|tarikh|tareekh) (?:kya hai|batao)|aaj kya (?:date|tarikh) hai""") to { _ -> "what is the date" },
        rx("""battery (?:kitni|kitna|batao)(?: (?:hai|bachi hai|bacha hai))?""") to { _ -> "battery level" },
        rx("""(?:phone )?(silent|vibrate|vibration)(?: mode)? (?:pe |par |mein |me )?(?:karo|kar do|daalo|dalo|rakho|lagao)""") to { m ->
            "turn on ${m.g(1)}"
        },
        rx("""(.+?) (?:search karo|search kar do|dhundo|dhoondo|khojo|dhundho)""") to { m -> "search ${m.g(1)}" },
        rx("""(.+?) (?:play karo|play kar do|bajao|baja do|sunao|suna do)""") to { m -> "play ${m.g(1)}" },
        rx("""(.+?) (?:likho|type karo|type kar do)""") to { m -> "type ${m.g(1)}" },
        rx("""(.+?) (?:on karo|on kar do|chalu karo|chalu kar do|chalu|jalao|jala do)""") to { m ->
            val t = m.g(1)
            if (Feature.lookup(t) != null) "turn on $t" else "open $t"
        },
        rx("""(.+?) (?:off karo|off kar do|band karo|band kar do|bandh karo|band|bujhao|bujha do)""") to { m ->
            val t = m.g(1)
            if (Feature.lookup(t) != null) "turn off $t" else "close $t"
        },
        rx("""(.+?) (?:$OPEN_HI)""") to { m ->
            val t = m.g(1)
            if (Feature.lookup(t) != null) "turn on $t" else "open $t"
        },
        rx("""(.+?) (?:dikhao|dikha do|dikhaao)""") to { m -> "show ${m.g(1)}" },
    )

    /** Rewrites common Hinglish word order ("WhatsApp kholo") into English ("open WhatsApp"). */
    fun hinglish(s: String): String {
        for ((r, f) in hinglish) {
            val m = r.matchEntire(s) ?: continue
            return f(m)
        }
        return s
    }

    private val strongYes = setOf(
        "yes", "yeah", "yep", "yup", "ya", "haan", "han", "ha", "haa", "ji", "ok", "okay", "sure",
        "confirm", "confirmed", "send", "call", "pay", "correct", "bhejo", "karo", "go", "chalo",
        "absolutely", "definitely", "do",
    )
    private val softYes = setOf("it", "ahead", "please", "now", "right", "of", "course", "kar", "de", "do", "that")
    private val strongNo = setOf(
        "no", "nope", "nah", "cancel", "don't", "dont", "stop", "nahi", "nahin", "mat", "ruko", "never",
        "wait", "rehne",
    )

    /** For "are you sure?" answers. true = yes, false = no, null = unclear. */
    fun yesNo(text: String): Boolean? {
        val words = stripFillers(clean(text)).lowercase().split(Regex("""[\s,.!?]+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (words.any { it in strongNo }) return false
        if (words.any { it in strongYes } && words.all { it in strongYes || it in softYes }) return true
        return null
    }
}

/** Splits "open instagram then go to reels and like" into separate commands. */
object Splitter {
    private val verbs = setOf(
        "open", "launch", "start", "run", "close", "exit", "quit", "go", "scroll", "swipe", "slide",
        "next", "previous", "prev", "skip", "like", "unlike", "dislike", "share", "comment", "follow",
        "unfollow", "subscribe", "unsubscribe", "save", "bookmark", "tap", "click", "press", "select",
        "long", "double", "type", "write", "enter", "search", "find", "google", "look", "play", "pause",
        "resume", "stop", "mute", "unmute", "call", "dial", "video", "send", "message", "text", "whatsapp",
        "turn", "switch", "enable", "disable", "set", "increase", "decrease", "raise", "lower", "reduce",
        "volume", "brightness", "take", "capture", "record", "read", "show", "navigate", "wait", "lock",
        "screenshot", "back", "home", "copy", "paste", "clear", "pay", "transfer", "remind", "wake",
        "keep", "auto", "repeat", "hide", "toggle", "minimize", "uninstall", "check", "reels", "shorts",
        "flashlight", "torch", "wifi", "bluetooth", "hotspot", "make", "put", "give", "hit", "max",
        "maximum", "dim", "watch", "forward", "reply", "return", "what", "how", "where",
    )

    private val hindiVerbEnd = Regex(
        """.*\b(?:kholo|karo|kar do|kardo|chalao|chala do|bhejo|bhej do|lo|le lo|jao|dikhao|badhao|ghatao|lagao|laga do|bajao|sunao|likho|dhundo|dhoondo|khojo|band karo)$""",
        RegexOption.IGNORE_CASE,
    )

    private val separator = rx(
        """\s*(?:,\s*(?:and then|then|and|after that)?|\band then\b|\bthen\b|\bafter that\b|\bafterwards\b|\bfollowed by\b|\band\b|\baur phir\b|\baur fir\b|\bphir\b|\bfir\b|\baur\b|\buske baad\b)\s*"""
    )
    private val autoWords = rx("""\b(?:keep|auto ?scroll|auto ?swipe|automatically)\b""")

    fun split(text: String): List<String> =
        text.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { splitLine(it) }

    private fun firstWord(s: String) = s.trimStart().substringBefore(' ').lowercase().trim(',', '.', '!', '?')

    private fun splitLine(line: String): List<String> {
        val out = mutableListOf<String>()
        var segStart = 0
        for (m in separator.findAll(line)) {
            if (m.range.first <= segStart) continue
            val left = line.substring(segStart, m.range.first)
            val after = line.substring(m.range.last + 1)
            val sep = m.value.trim().lowercase()
            val hindiSep = sep.startsWith("aur") || sep == "phir" || sep == "fir" || sep.startsWith("uske")
            val ok = when {
                after.isBlank() -> false
                autoWords.containsMatchIn(left) && firstWord(after).startsWith("lik") -> false
                firstWord(after) in verbs -> true
                hindiSep && hindiVerbEnd.matches(left.trim()) -> true
                else -> false
            }
            if (ok) {
                out += left
                segStart = m.range.last + 1
            }
        }
        out += line.substring(segStart)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
