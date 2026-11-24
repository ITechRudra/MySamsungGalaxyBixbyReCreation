package com.bixi.agent.nlu

/** Understands "7 am", "7:30 pm", "19:45", "half past 6", "5 minutes", "1 hour 30 minutes"... */
object TimeParser {
    data class Clock(val hour: Int, val minute: Int)

    private val wordNumbers = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
        "twenty five" to 25, "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50,
        "sixty" to 60, "ninety" to 90,
    )

    fun number(word: String): Int? = word.trim().toIntOrNull() ?: wordNumbers[word.trim().lowercase()]

    private val numberWordAlt = wordNumbers.keys.sortedByDescending { it.length }.joinToString("|")

    private val meridiem =
        """(a\.?\s?m\.?|p\.?\s?m\.?|in the morning|morning|in the afternoon|afternoon|in the evening|evening|at night|night|tonight|o'?clock|baje|subah|shaam|raat)"""

    private val clockRx = Regex(
        """(?<![\d:])(\d{1,2})(?!\d)(?:[:.](\d{2})|\s(\d{2})(?=\s?(?:a\.?\s?m|p\.?\s?m)))?\s*$meridiem?""",
        RegexOption.IGNORE_CASE,
    )
    private val halfPast = Regex("""(half|quarter) past (\d{1,2}|$numberWordAlt)\s*$meridiem?""", RegexOption.IGNORE_CASE)
    private val quarterTo = Regex("""quarter to (\d{1,2}|$numberWordAlt)\s*$meridiem?""", RegexOption.IGNORE_CASE)

    /** Finds the first time-of-day in [text]. */
    fun findClock(text: String): Clock? {
        val t = text.lowercase()
        if (Regex("""\bnoon\b|\bmidday\b""").containsMatchIn(t)) return Clock(12, 0)
        if (Regex("""\bmidnight\b""").containsMatchIn(t)) return Clock(0, 0)
        halfPast.find(t)?.let { m ->
            val h = number(m.groupValues[2]) ?: return null
            val min = if (m.groupValues[1] == "half") 30 else 15
            return adjust(h, min, m.groupValues[3])
        }
        quarterTo.find(t)?.let { m ->
            val h = number(m.groupValues[1]) ?: return null
            return adjust((h + 23) % 24, 45, m.groupValues[2])
        }
        for (m in clockRx.findAll(t)) {
            val h = m.groupValues[1].toIntOrNull() ?: continue
            val min = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: 0
            val mer = m.groupValues[4]
            // A bare number with no minutes and no am/pm is only a time if it's the whole thing,
            // e.g. "alarm for 6" — never "5 minutes".
            val after = t.substring(m.range.last + 1).trimStart()
            if (mer.isEmpty() && m.groupValues[2].isEmpty() &&
                Regex("""^(min|minute|minutes|hour|hours|hr|hrs|sec|second|seconds|percent|%|times)\b""").containsMatchIn(after)
            ) continue
            if (h > 23 || min > 59) continue
            return adjust(h, min, mer)
        }
        return null
    }

    private fun adjust(h: Int, m: Int, mer: String): Clock {
        val x = mer.lowercase().replace(" ", "").replace(".", "")
        var hour = h
        val pm = x.startsWith("pm") || x.contains("afternoon") || x.contains("evening") ||
            x.contains("night") || x.contains("tonight") || x == "shaam" || x == "raat"
        val am = x.startsWith("am") || x.contains("morning") || x == "subah"
        if (pm && hour in 1..11) hour += 12
        if (x.contains("night") && hour == 12) hour = 0
        if (am && hour == 12) hour = 0
        return Clock(hour % 24, m)
    }

    private val durationRx = Regex(
        """\b(\d+(?:\.\d+)?|$numberWordAlt|half an?|half)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s|ghante|ghanta|minute|second)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Total seconds in "5 minutes", "1 hour and 30 minutes", "half an hour", "90 seconds". */
    fun findDuration(text: String): Int? {
        var total = 0.0
        var found = false
        for (m in durationRx.findAll(text.lowercase())) {
            val qty = m.groupValues[1]
            val n: Double = when {
                qty.startsWith("half") -> 0.5
                else -> qty.toDoubleOrNull() ?: number(qty)?.toDouble() ?: continue
            }
            val unit = m.groupValues[2]
            val mult = when {
                unit.startsWith("h") || unit.startsWith("ghant") -> 3600
                unit.startsWith("m") -> 60
                else -> 1
            }
            total += n * mult
            found = true
        }
        return if (found && total > 0) total.toInt() else null
    }

    fun formatClock(c: Clock): String {
        val h12 = when {
            c.hour == 0 -> 12
            c.hour > 12 -> c.hour - 12
            else -> c.hour
        }
        val ampm = if (c.hour < 12) "AM" else "PM"
        return "%d:%02d %s".format(h12, c.minute, ampm)
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour" + if (it > 1) "s" else "" },
            m.takeIf { it > 0 }?.let { "$it minute" + if (it > 1) "s" else "" },
            s.takeIf { it > 0 }?.let { "$it second" + if (it > 1) "s" else "" },
        ).joinToString(" ")
    }
}
