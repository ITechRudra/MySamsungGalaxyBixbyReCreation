package com.bixi.agent.core

import android.content.Context
import com.bixi.agent.data.Routines
import com.bixi.agent.exec.Executor
import com.bixi.agent.exec.Outcome
import com.bixi.agent.nlu.Action
import com.bixi.agent.nlu.CommandParser
import com.bixi.agent.nlu.Dir
import com.bixi.agent.nlu.describe
import com.bixi.agent.service.BixiAccessibilityService
import com.bixi.agent.service.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Source { PANEL, MAIN }

/** A parsed command, plus the "are you sure?" text if it needs confirming. */
data class Prepared(val text: String, val actions: List<Action>, val confirm: String?) {
    val conversational get() = actions.isNotEmpty() && actions.all { it.conversational }
    val needsScreen get() = actions.firstOrNull()?.needsScreen == true
}

/**
 * Runs your commands. Bixi never acts by itself: every action here starts from something you
 * said or typed, and anything that contacts a person or moves money is confirmed first.
 */
object Agent {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var autoJob: Job? = null
    private var moodJob: Job? = null

    fun prepare(context: Context, text: String): Prepared {
        val routine = Routines.match(context, text)
        var actions = CommandParser.parse(routine?.commands ?: text)
        if (actions.any { it is Action.Repeat }) {
            val last = Bixi.prefs.lastCommand
            actions = actions.flatMap { a ->
                if (a is Action.Repeat) (if (last.isNotBlank()) CommandParser.parse(last) else emptyList()) else listOf(a)
            }
        }
        val sensitive = actions.filter { it.sensitive }
        val confirm = if (Bixi.prefs.confirmSensitive && sensitive.isNotEmpty()) {
            sensitive.joinToString("\n") { it.describe() } + "?"
        } else {
            null
        }
        return Prepared(text, actions, confirm)
    }

    /** Speech recognisers return several guesses; take the first one Bixi understands. */
    fun pickBest(context: Context, hypotheses: List<String>): String {
        val list = hypotheses.filter { it.isNotBlank() }
        if (list.isEmpty()) return ""
        return list.firstOrNull { Routines.match(context, it) != null }
            ?: list.firstOrNull { h -> CommandParser.parse(h).let { it.isNotEmpty() && it.none { a -> a is Action.Unknown } } }
            ?: list.first()
    }

    fun run(context: Context, prepared: Prepared, source: Source, onDone: ((Outcome) -> Unit)? = null) {
        val app = context.applicationContext
        AgentBus.userSaid(prepared.text)

        if (prepared.actions.isEmpty()) {
            finish(Outcome.fail("I didn't catch a command there.", "Sorry, I didn't catch that."), onDone)
            return
        }
        if (prepared.actions.any { it is Action.Stop }) {
            stopAll()
            finish(Outcome.ok("Stopped.", "Okay, stopped."), onDone)
            return
        }
        if (prepared.actions.none { it is Action.Help || it.conversational }) Bixi.prefs.lastCommand = prepared.text

        Speaker.stop()
        job?.cancel()
        job = scope.launch {
            AgentBus.mood.value = RobotMood.THINKING
            // Let Bixi's own panel / app get out of the way so taps land on the right app.
            if ((source == Source.PANEL && !prepared.conversational) || (source == Source.MAIN && prepared.needsScreen)) {
                awaitOtherAppInFront()
            }
            val exec = Executor(app)
            var outcome = Outcome.ok("Done.")
            for ((i, action) in prepared.actions.withIndex()) {
                outcome = try {
                    exec.run(action)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Outcome.fail("Something went wrong: ${e.message ?: e.javaClass.simpleName}", "Sorry, something went wrong.")
                }
                if (!outcome.ok) break
                if (i < prepared.actions.lastIndex) delay(450)
            }
            finish(outcome, onDone)
        }
    }

    private suspend fun awaitOtherAppInFront() {
        val svc = BixiAccessibilityService.instance
        if (svc == null) {
            delay(400)
            return
        }
        val screen = Screen(svc)
        screen.waitFor(2500, 100) {
            val active = try {
                svc.rootInActiveWindow?.packageName?.toString()
            } catch (_: Exception) {
                null
            }
            active?.takeIf { it != svc.packageName }
        }
        delay(250)
    }

    private fun finish(o: Outcome, onDone: ((Outcome) -> Unit)?) {
        AgentBus.bixiSaid(o.text, o.ok)
        AgentBus.gaze.value = 0f to 0f
        AgentBus.mood.value = when {
            !o.ok -> RobotMood.CONFUSED
            o.mood != null -> o.mood
            else -> RobotMood.HAPPY
        }
        Speaker.speak(o.speak)
        onDone?.invoke(o)
        moodJob?.cancel()
        moodJob = scope.launch {
            delay(if (o.ok) 1600 else 2400)
            if (AgentBus.mood.value in setOf(RobotMood.HAPPY, RobotMood.CONFUSED, RobotMood.THINKING, RobotMood.MOVING)) {
                AgentBus.mood.value = RobotMood.IDLE
            }
        }
    }

    fun stopAll() {
        autoJob?.cancel()
        autoJob = null
        job?.cancel()
        Speaker.stop()
        AgentBus.autoScrolling.value = false
        AgentBus.gaze.value = 0f to 0f
        AgentBus.mood.value = RobotMood.IDLE
    }

    /** "Keep scrolling (and liking)": swipe to the next reel every [seconds] until you say stop. */
    fun startAutoScroll(context: Context, seconds: Int, like: Boolean) {
        autoJob?.cancel()
        val app = context.applicationContext
        autoJob = scope.launch {
            val svc = BixiAccessibilityService.instance ?: return@launch
            val screen = Screen(svc)
            val startPkg = screen.foregroundPackage()
            val exec = Executor(app)
            AgentBus.autoScrolling.value = true
            try {
                var swipes = 0
                while (isActive && swipes < 300) {
                    if (like) {
                        delay(1500)
                        exec.likeQuietly()
                    }
                    delay((seconds * 1000L - if (like) 1500L else 0L).coerceAtLeast(500L))
                    while (AgentBus.panelOpen.value) delay(300)
                    val now = screen.foregroundPackage()
                    if (startPkg != null && now != null && now != startPkg && now != svc.packageName) {
                        AgentBus.bixiSaid("You left the app, so I stopped auto-scrolling.")
                        break
                    }
                    screen.swipe(Dir.UP)
                    swipes++
                    if (AgentBus.mood.value == RobotMood.MOVING) AgentBus.mood.value = RobotMood.IDLE
                }
            } finally {
                AgentBus.autoScrolling.value = false
            }
        }
    }
}
