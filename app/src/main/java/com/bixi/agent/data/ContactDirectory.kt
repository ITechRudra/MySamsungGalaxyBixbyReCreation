package com.bixi.agent.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.bixi.agent.core.Bixi
import com.bixi.agent.nlu.Fuzzy

/** Finds people in your contacts by spoken name ("Mom" also finds "Mummy"). */
object ContactDirectory {
    data class Contact(val name: String, val number: String)

    sealed interface Result {
        data class Found(val contact: Contact) : Result
        data object NoPermission : Result
        data object NotFound : Result
    }

    private val phoneLike = Regex("""^[+\d][\d\s-]{6,}$""")

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(context: Context, spoken: String): Result {
        val who = spoken.trim()
        if (phoneLike.matches(who)) return Result.Found(Contact(who, who.filter { it.isDigit() || it == '+' }))
        if (!hasPermission(context)) return Result.NoPermission

        val all = mutableListOf<Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null, null,
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val num = c.getString(numIdx) ?: continue
                    all += Contact(name, num)
                }
            }
        } catch (_: SecurityException) {
            return Result.NoPermission
        }
        if (all.isEmpty()) return Result.NotFound

        var best: Contact? = null
        var bestScore = 0.0
        for (variant in Fuzzy.nameVariants(who)) {
            for (c in all) {
                val score = maxOf(
                    Fuzzy.score(variant, c.name),
                    // "Rahul" should match "Rahul Sharma"
                    if (Fuzzy.norm(c.name).split(' ').firstOrNull() == variant) 0.9 else 0.0,
                )
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            if (bestScore >= 0.95) break
        }
        return if (best != null && bestScore >= 0.72) Result.Found(best) else Result.NotFound
    }

    /** Digits with country code, as WhatsApp links need ("98765 43210" → "919876543210"). */
    fun internationalDigits(number: String): String {
        val plus = number.trim().startsWith("+")
        var d = number.filter { it.isDigit() }
        val cc = Bixi.prefs.countryCode.filter { it.isDigit() }
        if (plus) return d
        if (d.startsWith("00")) return d.drop(2)
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        return if (d.length == 10) cc + d else d
    }
}
