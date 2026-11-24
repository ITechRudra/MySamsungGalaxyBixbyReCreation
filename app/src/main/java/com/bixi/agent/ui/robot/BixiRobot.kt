package com.bixi.agent.ui.robot

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.bixi.agent.core.RobotMood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val HeadTop = Color(0xFF5B8CFF)
private val HeadMid = Color(0xFF6A6CF6)
private val HeadEnd = Color(0xFF9B5CF6)
private val Deep = Color(0xFF2A2F6B)
private val VisorTop = Color(0xFF0A0F2C)
private val VisorBottom = Color(0xFF161D4A)
val BixiCyan = Color(0xFF3CF2E3)

enum class EyeStyle { OPEN, HAPPY, DOTS, CLOSED, CONFUSED }

/** Everything that moves, in a 200×200 design space. */
data class RobotPose(
    val bob: Float = 0f,
    val tilt: Float = 0f,
    val scale: Float = 1f,
    val eyes: EyeStyle = EyeStyle.OPEN,
    val blink: Float = 1f,
    val eyeScale: Float = 1f,
    val squint: Float = 1f,
    val eyeShiftX: Float = 0f,
    val eyeShiftY: Float = 0f,
    val emblemAlpha: Float = 0.35f,
    val thruster: Float = 0.55f,
    val dotsPhase: Float = 0f,
    val leftHandY: Float = 0f,
    val rightHandY: Float = 0f,
    val leftWave: Float = 0f,
    val micRing: Float = 0f,
    val mouth: Float = 0f,
    val zzz: Boolean = false,
)

/**
 * The Bixi mascot. Each part (head, visor, eyes, emblem, hands, hover thruster) is its own
 * layer so they animate independently:
 *  idle      – gentle ±6px bob every 2.4s, blink every ~4s, occasional glance
 *  moving    – tilts up to ±12° toward the motion, thruster brightens, eyes squint
 *  listening – leans in (scale 1.05), emblem pulses 0.2→0.55, eyes widen, mic ring
 *  thinking  – slow ±4° rock, eyes become orbiting cyan dots
 *  speaking  – energetic micro-bounces, happy arched eyes, talking mouth
 */
@Composable
fun BixiRobot(
    mood: RobotMood,
    modifier: Modifier = Modifier,
    micLevel: Float = 0f,
    gaze: Pair<Float, Float> = 0f to 0f,
) {
    if (mood == RobotMood.SLEEPING) {
        // Static frame: no animation clock running, so a sleeping bubble costs no battery.
        Canvas(modifier) {
            drawBixi(RobotPose(eyes = EyeStyle.CLOSED, emblemAlpha = 0.2f, thruster = 0.25f, zzz = true))
        }
        return
    }

    val clock = rememberInfiniteTransition(label = "bixi")
    val p2400 by clock.phase(2400, "bob")
    val p4200 by clock.phase(4200, "blink")
    val p7000 by clock.phase(7000, "glance")
    val p1600 by clock.phase(1600, "rock")
    val p1000 by clock.phase(1000, "pulse")
    val p900 by clock.phase(900, "dots")
    val p520 by clock.phase(520, "bounce")

    val scale by animateFloatAsState(if (mood == RobotMood.LISTENING) 1.05f else 1f, spring(), label = "scale")
    val eyeScale by animateFloatAsState(if (mood == RobotMood.LISTENING) 1.15f else 1f, spring(), label = "eyeScale")
    val thruster by animateFloatAsState(
        when (mood) {
            RobotMood.MOVING -> 1f
            RobotMood.LISTENING -> 0.8f
            else -> 0.55f
        },
        tween(300),
        label = "thruster",
    )
    val moveTilt by animateFloatAsState(
        when (mood) {
            RobotMood.MOVING -> gaze.first * 12f
            RobotMood.CONFUSED -> 9f
            else -> 0f
        },
        spring(),
        label = "tilt",
    )
    val gazeX by animateFloatAsState(if (mood == RobotMood.MOVING) gaze.first else 0f, label = "gx")
    val gazeY by animateFloatAsState(if (mood == RobotMood.MOVING) gaze.second else 0f, label = "gy")
    val handsUp by animateFloatAsState(if (mood == RobotMood.LISTENING) -8f else 0f, spring(), label = "hands")

    Canvas(modifier) {
        val tau = (2 * PI).toFloat()
        val bob = when (mood) {
            RobotMood.SPEAKING, RobotMood.HAPPY -> -3f * abs(sin(tau * p520))
            else -> -6f * cos(tau * p2400)
        }
        val blink = if (p4200 in 0.93f..0.963f) {
            val k = (p4200 - 0.93f) / 0.033f
            1f - 0.9f * sin(PI.toFloat() * k)
        } else {
            1f
        }
        val glance = -4f * bump(p7000, 0.22f, 0.36f) + 4f * bump(p7000, 0.6f, 0.74f)
        val tilt = moveTilt + if (mood == RobotMood.THINKING) 4f * sin(tau * p1600) else 0f
        val emblem = when (mood) {
            RobotMood.LISTENING -> (0.2f + 0.35f * (0.5f + 0.5f * sin(tau * p1000)) + 0.2f * micLevel).coerceAtMost(0.8f)
            RobotMood.THINKING -> 0.25f
            RobotMood.SPEAKING, RobotMood.HAPPY -> 0.45f
            else -> 0.35f
        }
        val eyes = when (mood) {
            RobotMood.THINKING -> EyeStyle.DOTS
            RobotMood.SPEAKING, RobotMood.HAPPY -> EyeStyle.HAPPY
            RobotMood.CONFUSED -> EyeStyle.CONFUSED
            else -> EyeStyle.OPEN
        }
        drawBixi(
            RobotPose(
                bob = bob,
                tilt = tilt,
                scale = scale,
                eyes = eyes,
                blink = if (mood == RobotMood.MOVING) 1f else blink,
                eyeScale = eyeScale,
                squint = if (mood == RobotMood.MOVING) 0.7f else 1f,
                eyeShiftX = gazeX * 6f + if (mood == RobotMood.IDLE) glance else 0f,
                eyeShiftY = gazeY * 4f,
                emblemAlpha = emblem,
                thruster = thruster + if (mood == RobotMood.MOVING) 0.15f * sin(tau * p520) else 0f,
                dotsPhase = p900,
                leftHandY = 3f * sin(tau * p2400 + 0.8f) + handsUp,
                rightHandY = 3f * sin(tau * p2400 + 2.2f) + handsUp,
                leftWave = if (mood == RobotMood.HAPPY) 20f * sin(tau * p520) else 0f,
                micRing = if (mood == RobotMood.LISTENING) micLevel.coerceIn(0f, 1f) else -1f,
                mouth = if (mood == RobotMood.SPEAKING) 0.5f + 0.5f * abs(sin(tau * p520 * 2)) else 0f,
            ),
        )
    }
}

/** A 0→1 sawtooth that restarts every [ms] milliseconds. */
@Composable
private fun InfiniteTransition.phase(ms: Int, label: String): State<Float> = animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart),
    label = label,
)

/** 0 outside [a,b], smooth 0→1→0 inside. */
private fun bump(t: Float, a: Float, b: Float): Float {
    if (t < a || t > b) return 0f
    return sin(PI.toFloat() * (t - a) / (b - a))
}

fun DrawScope.drawBixi(p: RobotPose) {
    val s = size.minDimension / 200f
    val ox = (size.width - 200f * s) / 2f
    val oy = (size.height - 200f * s) / 2f
    fun o(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
    fun sz(w: Float, h: Float) = Size(w * s, h * s)
    fun cr(r: Float) = CornerRadius(r * s, r * s)

    // Ground glow (stays on the floor while the robot bobs).
    val glowW = 70f * (0.85f + 0.35f * p.thruster)
    drawOval(
        brush = Brush.radialGradient(
            listOf(BixiCyan.copy(alpha = 0.42f * p.thruster.coerceIn(0f, 1f)), Color.Transparent),
            center = o(100f, 188f),
            radius = glowW / 2f * s,
        ),
        topLeft = o(100f - glowW / 2f, 182f),
        size = sz(glowW, 12f),
    )

    withTransform({
        translate(top = p.bob * s)
        rotate(p.tilt, pivot = o(100f, 100f))
        scale(p.scale, p.scale, pivot = o(100f, 100f))
    }) {
        // Mic ring while listening.
        if (p.micRing >= 0f) {
            drawCircle(
                color = BixiCyan.copy(alpha = 0.18f + 0.4f * p.micRing),
                radius = (74f + 10f * p.micRing) * s,
                center = o(100f, 90f),
                style = Stroke(width = (2f + 2f * p.micRing) * s),
            )
        }

        // Thruster beam.
        val t = p.thruster.coerceIn(0f, 1.2f)
        val beam = Path().apply {
            moveTo(ox + 88f * s, oy + 166f * s)
            lineTo(ox + 112f * s, oy + 166f * s)
            lineTo(ox + (118f + 4f * t) * s, oy + 184f * s)
            lineTo(ox + (82f - 4f * t) * s, oy + 184f * s)
            close()
        }
        drawPath(
            beam,
            Brush.verticalGradient(
                listOf(BixiCyan.copy(alpha = 0.75f * t.coerceAtMost(1f)), Color.Transparent),
                startY = oy + 166f * s,
                endY = oy + 184f * s,
            ),
        )

        // Hover base.
        drawOval(Brush.linearGradient(listOf(HeadTop, HeadEnd), o(72f, 150f), o(128f, 170f)), o(72f, 150f), sz(56f, 18f))
        drawOval(Color.White.copy(alpha = 0.18f), o(78f, 151f), sz(44f, 7f))
        drawOval(
            BixiCyan.copy(alpha = 0.35f + 0.55f * t.coerceAtMost(1f)),
            o(76f, 160f),
            sz(48f, 8f),
            style = Stroke(width = (1.6f + 1.4f * t) * s),
        )

        // Neck.
        drawRoundRect(Deep, o(90f, 142f), sz(20f, 12f), cr(4f))

        // Ear pods.
        for (x in listOf(37f, 163f)) {
            drawCircle(Deep, 10f * s, o(x, 92f))
            drawCircle(HeadMid, 7f * s, o(x, 92f))
            drawCircle(BixiCyan.copy(alpha = 0.7f), 3f * s, o(x, 92f))
        }

        // Head squircle.
        drawRoundRect(
            Brush.linearGradient(listOf(HeadTop, HeadMid, HeadEnd), o(40f, 28f), o(160f, 148f)),
            o(40f, 28f),
            sz(120f, 120f),
            cr(48f),
        )
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f)), oy + 90f * s, oy + 148f * s),
            o(40f, 28f),
            sz(120f, 120f),
            cr(48f),
        )
        drawRoundRect(Color.White.copy(alpha = 0.2f), o(56f, 33f), sz(56f, 9f), cr(4.5f))
        // Chin panel + indicator light.
        drawRoundRect(Color.Black.copy(alpha = 0.12f), o(72f, 124f), sz(56f, 16f), cr(8f))
        drawCircle(BixiCyan, 2.2f * s, o(132f, 118f))

        // Visor.
        drawRoundRect(Brush.verticalGradient(listOf(VisorTop, VisorBottom), oy + 48f * s, oy + 120f * s), o(52f, 48f), sz(96f, 72f), cr(26f))
        drawRoundRect(Color(0xFF8FB2FF).copy(alpha = 0.35f), o(52f, 48f), sz(96f, 72f), cr(26f), style = Stroke(2f * s))
        drawRoundRect(Color.White.copy(alpha = 0.07f), o(62f, 52f), sz(42f, 8f), cr(4f))

        // Emblem: an original stylised "b" glowing inside the visor.
        val e = Color.White.copy(alpha = p.emblemAlpha.coerceIn(0f, 1f))
        drawRoundRect(e, o(89f, 60f), sz(10f, 40f), cr(5f))
        drawCircle(e, 12f * s, o(105f, 89f), style = Stroke(width = 8f * s))

        // Eyes.
        val lx = 78f + p.eyeShiftX
        val rx = 122f + p.eyeShiftX
        val ey = 86f + p.eyeShiftY
        for (x in listOf(lx, rx)) {
            drawCircle(
                Brush.radialGradient(listOf(BixiCyan.copy(alpha = 0.45f), Color.Transparent), o(x, ey), 17f * s),
                17f * s,
                o(x, ey),
            )
        }
        when (p.eyes) {
            EyeStyle.OPEN -> for (x in listOf(lx, rx)) {
                val w = 13f * p.eyeScale
                val h = (20f * p.eyeScale * p.blink * p.squint).coerceAtLeast(2f)
                drawRoundRect(BixiCyan, o(x - w / 2f, ey - h / 2f), sz(w, h), cr(minOf(w, h) / 2f))
            }
            EyeStyle.HAPPY -> for (x in listOf(lx, rx)) {
                drawArc(
                    BixiCyan, 180f, 180f, false, o(x - 9f, ey - 6f), sz(18f, 16f),
                    style = Stroke(width = 5f * s, cap = StrokeCap.Round),
                )
            }
            EyeStyle.CLOSED -> for (x in listOf(lx, rx)) {
                drawArc(
                    BixiCyan.copy(alpha = 0.8f), 0f, 180f, false, o(x - 8f, ey - 8f), sz(16f, 12f),
                    style = Stroke(width = 3.5f * s, cap = StrokeCap.Round),
                )
            }
            EyeStyle.CONFUSED -> {
                drawCircle(BixiCyan, 6f * s, o(lx, ey))
                drawLine(BixiCyan, o(rx - 8f, ey + 1f), o(rx + 8f, ey - 3f), 4f * s, StrokeCap.Round)
            }
            EyeStyle.DOTS -> {
                val tau = (2 * PI).toFloat()
                for (i in 0..2) {
                    val dy = -5f * sin(tau * (p.dotsPhase - i * 0.18f))
                    drawCircle(BixiCyan, 4.8f * s, o(86f + i * 14f, ey + dy))
                }
            }
        }

        // Talking mouth.
        if (p.mouth > 0f) {
            val w = 10f + 10f * p.mouth
            drawRoundRect(BixiCyan.copy(alpha = 0.85f), o(100f - w / 2f, 104f), sz(w, 3.5f + 2f * p.mouth), cr(2f))
        }

        // Floating hands.
        drawHand(this, o(20f, 106f + p.leftHandY), s, p.leftWave)
        drawHand(this, o(180f, 112f + p.rightHandY), s, 0f)

        // Zzz when sleeping.
        if (p.zzz) {
            for ((i, z) in listOf(Triple(150f, 34f, 8f), Triple(164f, 20f, 6f)).withIndex()) {
                val (x, y, zs) = z
                val c = Color.White.copy(alpha = 0.75f - i * 0.2f)
                val path = Path().apply {
                    moveTo(ox + x * s, oy + y * s)
                    lineTo(ox + (x + zs) * s, oy + y * s)
                    lineTo(ox + x * s, oy + (y + zs) * s)
                    lineTo(ox + (x + zs) * s, oy + (y + zs) * s)
                }
                drawPath(path, c, style = Stroke(width = 2f * s, cap = StrokeCap.Round))
            }
        }
    }
}

private fun drawHand(scope: DrawScope, center: Offset, s: Float, waveDeg: Float) = with(scope) {
    rotate(waveDeg, pivot = Offset(center.x, center.y + 14f * s)) {
        val brush = Brush.linearGradient(listOf(HeadTop, HeadEnd), center + Offset(-9f * s, -14f * s), center + Offset(9f * s, 14f * s))
        drawOval(Deep, center + Offset(-7f * s, 11f * s), Size(14f * s, 6f * s))
        drawRoundRect(brush, center + Offset(-9f * s, -13f * s), Size(18f * s, 26f * s), CornerRadius(9f * s))
        for (fx in listOf(-5.5f, 0f, 5.5f)) {
            drawCircle(brush, 3.2f * s, center + Offset(fx * s, -14f * s - if (fx == 0f) 2f * s else 0f))
        }
        drawCircle(brush, 3.4f * s, center + Offset(-10f * s, -2f * s))
        drawRoundRect(Color.White.copy(alpha = 0.22f), center + Offset(-5f * s, -10f * s), Size(4f * s, 12f * s), CornerRadius(2f * s))
    }
}
