package com.bixi.agent.data

import android.content.Context
import com.bixi.agent.nlu.Fuzzy
import com.bixi.agent.nlu.Normalizer
import org.json.JSONArray
import org.json.JSONObject

/** Your own shortcuts: say "good night" → Bixi runs several commands. */
object Routines {
    data class Routine(val trigger: String, val commands: String)

    private const val PREFS = "bixi_routines"
    private const val KEY = "list"

    fun all(context: Context): List<Routine> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Routine(o.getString("trigger"), o.getString("commands"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, list: List<Routine>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("trigger", it.trigger).put("commands", it.commands)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, trigger: String, commands: String) {
        val t = trigger.trim()
        save(context, all(context).filterNot { Fuzzy.norm(it.trigger) == Fuzzy.norm(t) } + Routine(t, commands.trim()))
    }

    fun remove(context: Context, trigger: String) {
        save(context, all(context).filterNot { it.trigger == trigger })
    }

    fun match(context: Context, text: String): Routine? {
        val said = Fuzzy.norm(Normalizer.stripFillers(Normalizer.stripWake(Normalizer.clean(text))))
        if (said.isEmpty()) return null
        return all(context).firstOrNull { Fuzzy.norm(it.trigger) == said }
            ?: all(context).firstOrNull { Fuzzy.score(said, it.trigger) >= 0.93 }
    }

    fun seedIfEmpty(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.contains(KEY)) return
        save(
            context,
            listOf(
                Routine("reels time", "open reels\nkeep scrolling"),
                Routine("good night", "turn on do not disturb\nset brightness to 10\nlock the screen"),
                Routine("music time", "play my playlist on spotify\nvolume 60"),
            ),
        )
    }
}
