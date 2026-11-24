package com.bixi.agent.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The robot's moods, from the animation spec. */
enum class RobotMood { IDLE, LISTENING, THINKING, SPEAKING, MOVING, HAPPY, CONFUSED, SLEEPING }

data class LogEntry(val fromUser: Boolean, val text: String, val ok: Boolean = true, val time: Long = System.currentTimeMillis())

data class BubbleMessage(val text: String, val id: Long)

/** Shared live state between the agent, the floating robot and the app screens. */
object AgentBus {
    val mood = MutableStateFlow(RobotMood.IDLE)

    /** Where the robot is "looking" while it moves: x,y in -1..1. */
    val gaze = MutableStateFlow(0f to 0f)

    /** Microphone loudness 0..1 while listening. */
    val micLevel = MutableStateFlow(0f)

    val log = MutableStateFlow<List<LogEntry>>(emptyList())
    val bubbleMessage = MutableStateFlow<BubbleMessage?>(null)
    val bubbleVisible = MutableStateFlow(true)
    val panelOpen = MutableStateFlow(false)
    val autoScrolling = MutableStateFlow(false)

    private var seq = 0L

    fun userSaid(text: String) = append(LogEntry(fromUser = true, text = text))

    fun bixiSaid(text: String, ok: Boolean = true) {
        if (text.isBlank()) return
        append(LogEntry(fromUser = false, text = text, ok = ok))
        bubbleMessage.value = BubbleMessage(text, ++seq)
    }

    private fun append(e: LogEntry) = log.update { (it + e).takeLast(80) }
}
