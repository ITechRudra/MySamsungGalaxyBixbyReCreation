package com.bixi.agent.nlu

import kotlin.math.max
import kotlin.math.min

/** Small, dependency-free fuzzy matching used for app names, contacts and on-screen text. */
object Fuzzy {
    private val nonAlnum = Regex("[^a-z0-9]+")

    fun norm(s: String): String = s.lowercase().replace(nonAlnum, " ").trim()

    fun compact(s: String): String = norm(s).replace(" ", "")

    /** 0.0 (nothing alike) .. 1.0 (same). */
    fun score(query: String, target: String): Double {
        val a = compact(query)
        val b = compact(target)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.length >= 3 && b.startsWith(a)) return max(0.8, 0.92 - (b.length - a.length) * 0.01)
        if (b.length >= 3 && a.startsWith(b)) return 0.8
        val qw = norm(query).split(' ').filter { it.isNotEmpty() }
        val tw = norm(target).split(' ').filter { it.isNotEmpty() }
        if (qw.isNotEmpty() && qw.all { w -> tw.any { it == w } }) return 0.84
        if (qw.isNotEmpty() && qw.all { w -> tw.any { it.startsWith(w) } }) return 0.8
        if (a.length >= 4 && b.contains(a)) return 0.76
        val lev = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
        return lev * 0.95
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Family words people use for contacts ("call mom" should find "Mummy"). */
    private val relationGroups = listOf(
        listOf("mom", "mummy", "mumma", "mommy", "mum", "maa", "ma", "mother", "ammi", "amma", "mamma"),
        listOf("dad", "papa", "daddy", "pappa", "father", "pa", "abbu", "pitaji", "baba"),
        listOf("brother", "bro", "bhai", "bhaiya", "bhaiyya"),
        listOf("sister", "sis", "didi", "behen", "dii"),
        listOf("wife", "wifey", "biwi"),
        listOf("husband", "hubby", "pati"),
        listOf("grandma", "granny", "dadi", "nani"),
        listOf("grandpa", "dada", "nana"),
    )

    /** "mom" → [mom, mummy, mumma, ...]; anything else → [itself]. */
    fun nameVariants(name: String): List<String> {
        val n = norm(name)
        val group = relationGroups.firstOrNull { n in it }
        return if (group != null) listOf(n) + (group - n) else listOf(n)
    }
}
