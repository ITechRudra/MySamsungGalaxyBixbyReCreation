package com.bixi.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.bixi.agent.core.AgentBus
import com.bixi.agent.core.Bixi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Bixi's hands. Android only lets an Accessibility Service read other apps' screens and tap /
 * swipe in them. Bixi does nothing on its own: it acts only when the Agent runs your command.
 */
class BixiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BixiAccessibilityService? = null
            private set

        val connected = MutableStateFlow(false)

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = "${context.packageName}/${BixiAccessibilityService::class.java.name}"
            val short = "${context.packageName}/.service.BixiAccessibilityService"
            return enabled.split(':').any { it.equals(me, true) || it.equals(short, true) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubble: FloatingBubble? = null

    /** Last app (not Bixi, not the status bar) that was in front. */
    @Volatile
    var lastPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected.value = true
        bubble = FloatingBubble(this)
        scope.launch {
            combine(AgentBus.bubbleVisible, AgentBus.panelOpen) { visible, panel -> visible to panel }
                .collect { (visible, panel) ->
                    val b = bubble ?: return@collect
                    if (visible) b.show() else b.remove()
                    b.setHidden(panel)
                }
        }
        AgentBus.bubbleVisible.value = Bixi.prefs.showBubble
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != packageName && pkg != "com.android.systemui") lastPackage = pkg
    }

    override fun onInterrupt() = Unit

    fun setBubblePassThrough(on: Boolean) {
        bubble?.setPassThrough(on)
    }

    /** Briefly hide the robot (e.g. so it isn't in a screenshot). */
    fun setBubbleHidden(hidden: Boolean) {
        bubble?.setHidden(hidden || AgentBus.panelOpen.value)
    }

    fun openPanel(typing: Boolean) {
        val intent = Intent(this, com.bixi.agent.ui.VoicePanelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(com.bixi.agent.ui.VoicePanelActivity.EXTRA_TYPING, typing)
        startActivity(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        bubble?.remove()
        bubble = null
        if (instance === this) instance = null
        connected.value = false
        scope.cancel()
    }
}
