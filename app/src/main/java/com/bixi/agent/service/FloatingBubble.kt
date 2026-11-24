package com.bixi.agent.service

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bixi.agent.core.Agent
import com.bixi.agent.core.AgentBus
import com.bixi.agent.core.Bixi
import com.bixi.agent.core.RobotMood
import com.bixi.agent.ui.robot.BixiRobot
import kotlinx.coroutines.delay

/** The animated robot that floats over every app. Tap = talk, long-press = type, drag = move. */
class FloatingBubble(private val svc: BixiAccessibilityService) {

    private val wm = svc.getSystemService(WindowManager::class.java)
    private var root: BubbleTouchLayer? = null
    private var owner: OverlayLifecycleOwner? = null
    private val dockRight = mutableStateOf(Bixi.prefs.bubbleOnRight)
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    fun show() {
        if (root != null) return
        val screen = Screen(svc).size()
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.gravity = Gravity.TOP or if (dockRight.value) Gravity.END else Gravity.START
        params.x = 0
        params.y = Bixi.prefs.bubbleY.takeIf { it >= 0 } ?: (screen.h * 0.55f).toInt()

        val lifecycle = OverlayLifecycleOwner().also { it.create() }
        val layer = BubbleTouchLayer(svc, touchListener)
        val compose = ComposeView(svc).apply {
            setContent { BubbleContent(dockRight.value) }
        }
        layer.addView(compose)
        lifecycle.attachTo(layer)
        try {
            wm.addView(layer, params)
        } catch (e: Exception) {
            lifecycle.destroy()
            return
        }
        lifecycle.resume()
        owner = lifecycle
        root = layer
    }

    fun remove() {
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        owner?.destroy()
        owner = null
        root = null
    }

    /** Hide (but keep) the robot while Bixi's own panel is open. */
    fun setHidden(hidden: Boolean) {
        val r = root ?: return
        r.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        setPassThrough(hidden)
    }

    /** Let touches fall through to the app underneath (used while Bixi swipes/taps). */
    fun setPassThrough(on: Boolean) {
        val r = root ?: return
        val flags = if (on) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (flags == params.flags) return
        params.flags = flags
        try {
            wm.updateViewLayout(r, params)
        } catch (_: Exception) {
        }
    }

    private val touchListener = object : BubbleTouchLayer.Listener {
        override fun onTap() {
            if (AgentBus.autoScrolling.value) {
                Agent.stopAll()
                return
            }
            svc.openPanel(typing = false)
        }

        override fun onLongPress() = svc.openPanel(typing = true)

        override fun onDragStart(rawX: Float, rawY: Float) {
            val r = root ?: return
            val screen = Screen(svc).size()
            val absX = if (dockRight.value) screen.w - r.width - params.x else params.x
            dragOffsetX = rawX - absX
            dragOffsetY = rawY - params.y
            params.gravity = Gravity.TOP or Gravity.START
            params.x = absX
            AgentBus.mood.value = RobotMood.MOVING
        }

        override fun onDrag(rawX: Float, rawY: Float) {
            val r = root ?: return
            val newX = (rawX - dragOffsetX).toInt()
            AgentBus.gaze.value = ((newX - params.x).coerceIn(-12, 12) / 12f) to 0f
            params.x = newX
            params.y = (rawY - dragOffsetY).toInt().coerceAtLeast(0)
            try {
                wm.updateViewLayout(r, params)
            } catch (_: Exception) {
            }
        }

        override fun onDragEnd(rawX: Float, rawY: Float) {
            val r = root ?: return
            val screen = Screen(svc).size()
            val right = rawX > screen.w / 2f
            dockRight.value = right
            params.gravity = Gravity.TOP or if (right) Gravity.END else Gravity.START
            params.x = 0
            params.y = params.y.coerceIn(0, (screen.h - r.height).coerceAtLeast(0))
            Bixi.prefs.bubbleOnRight = right
            Bixi.prefs.bubbleY = params.y
            AgentBus.gaze.value = 0f to 0f
            AgentBus.mood.value = RobotMood.IDLE
            try {
                wm.updateViewLayout(r, params)
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
private fun BubbleContent(dockRight: Boolean) {
    val mood by AgentBus.mood.collectAsState()
    val message by AgentBus.bubbleMessage.collectAsState()
    val auto by AgentBus.autoScrolling.collectAsState()
    val mic by AgentBus.micLevel.collectAsState()
    val gaze by AgentBus.gaze.collectAsState()

    var showText by remember { mutableStateOf<String?>(null) }
    var sleeping by remember { mutableStateOf(false) }

    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        showText = m.text
        delay(3800)
        showText = null
    }
    LaunchedEffect(mood, message?.id, auto) {
        sleeping = false
        if (mood == RobotMood.IDLE && !auto) {
            delay(25_000)
            sleeping = true
        }
    }

    val label = when {
        auto -> "Auto-scrolling · tap me to stop"
        else -> showText
    }
    val shownMood = if (sleeping) RobotMood.SLEEPING else mood

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        if (dockRight) SpeechChip(label)
        Box(Modifier.size(68.dp)) {
            BixiRobot(mood = shownMood, micLevel = mic, gaze = gaze, modifier = Modifier.size(68.dp))
        }
        if (!dockRight) SpeechChip(label)
    }
}

@Composable
private fun SpeechChip(text: String?) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
    ) {
        Text(
            text = text.orEmpty(),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .background(Color(0xE6151A45), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
