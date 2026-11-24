package com.bixi.agent.service

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/** Handles tap / long-press / drag for the floating robot using raw screen coordinates. */
@SuppressLint("ViewConstructor")
class BubbleTouchLayer(context: Context, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onTap()
        fun onLongPress()
        fun onDragStart(rawX: Float, rawY: Float)
        fun onDrag(rawX: Float, rawY: Float)
        fun onDragEnd(rawX: Float, rawY: Float)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longFired = false

    private val longPress = Runnable {
        if (!dragging) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listener.onLongPress()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                longFired = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                    listener.onDragStart(downX, downY)
                }
                if (dragging) listener.onDrag(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    listener.onDragEnd(ev.rawX, ev.rawY)
                } else if (!longFired) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                    listener.onTap()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) listener.onDragEnd(ev.rawX, ev.rawY)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
