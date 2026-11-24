package com.bixi.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.bixi.agent.core.AgentBus
import com.bixi.agent.core.RobotMood
import com.bixi.agent.nlu.Dir
import com.bixi.agent.nlu.Distance
import com.bixi.agent.nlu.Fuzzy
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

/** Reads the screen and performs taps / swipes / typing through the accessibility service. */
class Screen(private val svc: BixiAccessibilityService) {

    data class Size(val w: Int, val h: Int)

    fun size(): Size {
        val dm = DisplayMetrics()
        val display = svc.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(dm)
        if (dm.widthPixels <= 0) {
            val r = svc.resources.displayMetrics
            return Size(r.widthPixels, r.heightPixels)
        }
        return Size(dm.widthPixels, dm.heightPixels)
    }

    /** Package of the app you are looking at (never Bixi's own panel). */
    fun foregroundPackage(): String? {
        val active = try {
            svc.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }
        return active ?: svc.lastPackage
    }

    // ------------------------------------------------------------------ reading

    /** Roots of the visible windows: the active app first, then dialogs / system UI. */
    fun roots(includeSystem: Boolean = true): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val own = svc.packageName
        try {
            svc.rootInActiveWindow?.let { if (it.packageName?.toString() != own) out += it }
        } catch (_: Exception) {
        }
        val windows = try {
            svc.windows
        } catch (_: Exception) {
            emptyList()
        }
        for (w in windows.sortedByDescending { it.layer }) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            if (!includeSystem && w.type == AccessibilityWindowInfo.TYPE_SYSTEM) continue
            val root = try {
                w.root
            } catch (_: Exception) {
                null
            } ?: continue
            if (root.packageName?.toString() == own) continue
            if (out.none { it == root }) out += root
        }
        return out
    }

    fun systemRoots(): List<AccessibilityNodeInfo> = try {
        svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }.mapNotNull { it.root }
    } catch (_: Exception) {
        emptyList()
    }

    /** Breadth-first search over the visible UI tree. */
    fun collect(
        roots: List<AccessibilityNodeInfo> = roots(),
        limit: Int = 60,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        roots.forEach { queue.addLast(it) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 6000 && out.size < limit) {
            val n = queue.removeFirst()
            visited++
            try {
                if (predicate(n)) out += n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun bounds(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }

    fun isOnScreen(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser) return false
        val r = bounds(n)
        val s = size()
        return r.width() > 2 && r.height() > 2 && r.right > 0 && r.bottom > 0 && r.left < s.w && r.top < s.h
    }

    fun desc(n: AccessibilityNodeInfo): String = n.contentDescription?.toString()?.trim().orEmpty()
    fun text(n: AccessibilityNodeInfo): String = n.text?.toString()?.trim().orEmpty()
    fun labels(n: AccessibilityNodeInfo): List<String> = listOf(desc(n), text(n)).filter { it.isNotEmpty() }

    /** Visible nodes whose text or description satisfies [match] (given lower-case). */
    fun findByLabel(roots: List<AccessibilityNodeInfo> = roots(), match: (String) -> Boolean): List<AccessibilityNodeInfo> =
        collect(roots) { n -> labels(n).any { match(it.lowercase()) } && isOnScreen(n) }

    fun findByViewId(vararg ids: String): AccessibilityNodeInfo? = collect(limit = 1) { n ->
        val id = n.viewIdResourceName ?: return@collect false
        ids.any { id == it || id.endsWith(":id/$it") } && isOnScreen(n)
    }.firstOrNull()

    /** Best on-screen element for spoken text like "login" or "follow back". */
    fun findBestText(query: String, minScore: Double = 0.7, roots: List<AccessibilityNodeInfo> = roots()): AccessibilityNodeInfo? {
        val q = Fuzzy.norm(query)
        if (q.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0.0
        collect(roots, limit = 400) { n -> labels(n).isNotEmpty() && isOnScreen(n) }.forEach { n ->
            for (label in labels(n)) {
                if (label.length > 80) continue
                var s = Fuzzy.score(q, label)
                if (Fuzzy.norm(label) == q) s += 0.2
                if (clickableTarget(n) != null) s += 0.05
                if (s > bestScore) {
                    bestScore = s
                    best = n
                }
            }
        }
        return best?.takeIf { bestScore >= minScore }
    }

    fun clickableTarget(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    fun visibleText(roots: List<AccessibilityNodeInfo> = roots(), maxChars: Int = 700): String {
        val seen = LinkedHashSet<String>()
        collect(roots, limit = 300) { n -> isOnScreen(n) && (text(n).isNotEmpty() || desc(n).isNotEmpty()) }.forEach { n ->
            val t = text(n).ifEmpty { desc(n) }
            if (t.length > 1) seen += t.replace('\n', ' ')
        }
        val sb = StringBuilder()
        for (t in seen) {
            if (sb.length + t.length > maxChars) break
            if (sb.isNotEmpty()) sb.append(". ")
            sb.append(t)
        }
        return sb.toString()
    }

    fun focusedInput(): AccessibilityNodeInfo? {
        val f = try {
            svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        if (f != null && f.isEditable) return f
        return collect(roots(includeSystem = false), limit = 1) { it.isEditable && isOnScreen(it) }.firstOrNull()
    }

    fun editables(): List<AccessibilityNodeInfo> = collect(roots(includeSystem = false), limit = 10) { it.isEditable && isOnScreen(it) }

    /** The biggest scrolling list on screen (chat list, search results, feed). */
    fun mainList(): AccessibilityNodeInfo? =
        collect(roots(includeSystem = false), limit = 30) { it.isScrollable && isOnScreen(it) }
            .maxByOrNull { val r = bounds(it); r.width() * r.height() }

    // ------------------------------------------------------------------ acting

    suspend fun click(n: AccessibilityNodeInfo): Boolean {
        clickableTarget(n)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        val r = bounds(n)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    suspend fun longClick(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            cur = cur.parent
            depth++
        }
        val r = bounds(n)
        return longPress(r.exactCenterX(), r.exactCenterY())
    }

    fun setText(n: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun currentText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && n.isShowingHintText) return ""
        return n.text?.toString().orEmpty()
    }

    fun pressImeEnter(n: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

    fun global(action: Int): Boolean = svc.performGlobalAction(action)

    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        svc.setBubblePassThrough(true)
        try {
            return suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                if (!svc.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
            }
        } finally {
            svc.setBubblePassThrough(false)
        }
    }

    private fun stroke(path: Path, start: Long, duration: Long) = GestureDescription.StrokeDescription(path, start, duration)

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x.coerceAtLeast(1f), y.coerceAtLeast(1f)) }

    suspend fun tap(x: Float, y: Float): Boolean =
        dispatch(GestureDescription.Builder().addStroke(stroke(point(x, y), 0, 60)).build())

    suspend fun longPress(x: Float, y: Float): Boolean =
        dispatch(GestureDescription.Builder().addStroke(stroke(point(x, y), 0, 900)).build())

    suspend fun doubleTap(x: Float, y: Float): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(stroke(point(x, y), 0, 50))
            .addStroke(stroke(point(x, y), 140, 50))
            .build()
        if (dispatch(g)) return true
        tap(x, y)
        delay(60)
        return tap(x, y)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 260): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(GestureDescription.Builder().addStroke(stroke(path, 0, duration)).build())
    }

    /** Swipe the finger in [dir] across the middle of the screen. */
    suspend fun swipe(dir: Dir, distance: Distance = Distance.NORMAL): Boolean {
        val (w, h) = size().let { it.w.toFloat() to it.h.toFloat() }
        val span = when (distance) {
            Distance.SMALL -> 0.18f
            Distance.NORMAL -> 0.42f
            Distance.LARGE -> 0.66f
        }
        AgentBus.mood.value = RobotMood.MOVING
        AgentBus.gaze.value = when (dir) {
            Dir.UP -> 0f to -1f
            Dir.DOWN -> 0f to 1f
            Dir.LEFT -> -1f to 0f
            Dir.RIGHT -> 1f to 0f
        }
        val cx = w / 2f
        val cy = h * 0.52f
        val ok = when (dir) {
            Dir.UP -> swipe(cx, cy + h * span / 2, cx, cy - h * span / 2)
            Dir.DOWN -> swipe(cx, cy - h * span / 2, cx, cy + h * span / 2)
            Dir.LEFT -> swipe(w * (0.5f + span), h * 0.45f, w * (0.5f - span), h * 0.45f, 220)
            Dir.RIGHT -> swipe(w * (0.5f - span), h * 0.45f, w * (0.5f + span), h * 0.45f, 220)
        }
        AgentBus.gaze.value = 0f to 0f
        return ok
    }

    /** Polls the screen until [probe] returns non-null or the time runs out. */
    suspend fun <T> waitFor(timeoutMs: Long = 5000, intervalMs: Long = 200, probe: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            try {
                probe()?.let { return it }
            } catch (_: Exception) {
            }
            if (SystemClock.uptimeMillis() >= end) return null
            delay(intervalMs)
        }
    }

    /** Sort top-to-bottom, then left-to-right. */
    fun readingOrder(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> =
        nodes.sortedWith(compareBy({ bounds(it).top / 24 }, { bounds(it).left }))

    fun distanceFromCenterY(n: AccessibilityNodeInfo): Int = abs(bounds(n).centerY() - (size().h * 0.55f).toInt())
}
