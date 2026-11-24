package com.bixi.agent.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.bixi.agent.core.Agent
import com.bixi.agent.core.AgentBus
import com.bixi.agent.core.Prepared
import com.bixi.agent.core.RobotMood
import com.bixi.agent.core.Source
import com.bixi.agent.core.Speaker
import com.bixi.agent.service.BixiAccessibilityService
import com.bixi.agent.ui.screens.CommandsScreen
import com.bixi.agent.ui.screens.HomeScreen
import com.bixi.agent.ui.screens.RoutinesScreen
import com.bixi.agent.ui.screens.SettingsScreen
import com.bixi.agent.ui.screens.SetupItem
import com.bixi.agent.ui.theme.BixiTheme
import com.bixi.agent.ui.theme.Cyan
import com.bixi.agent.ui.theme.Night
import com.bixi.agent.ui.theme.Surface1

class MainActivity : ComponentActivity(), VoiceInput.Listener {

    companion object {
        const val EXTRA_TAB = "tab"
    }

    private var tab by mutableIntStateOf(0)
    private var tick by mutableIntStateOf(0)
    private var listening by mutableStateOf(false)
    private var partial by mutableStateOf("")
    private var confirm by mutableStateOf<Prepared?>(null)
    private var disclosure by mutableStateOf(false)
    private lateinit var voice: VoiceInput

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    private val micThenListen = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (ok) startListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voice = VoiceInput(this, this)
        tab = intent.getIntExtra(EXTRA_TAB, 0)
        setContent { BixiTheme { Main() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab = intent.getIntExtra(EXTRA_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    override fun onPause() {
        super.onPause()
        if (listening) {
            voice.cancel()
            listening = false
            AgentBus.micLevel.value = 0f
            if (AgentBus.mood.value == RobotMood.LISTENING) AgentBus.mood.value = RobotMood.IDLE
        }
    }

    override fun onDestroy() {
        voice.destroy()
        super.onDestroy()
    }

    // ---- commands
    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val p = Agent.prepare(this, t)
        if (p.confirm != null) confirm = p else runPrepared(p)
    }

    private fun runPrepared(p: Prepared) {
        // Screen actions (scroll, like, tap...) should act on the app you were using, not on Bixi.
        if (p.needsScreen && BixiAccessibilityService.instance != null) moveTaskToBack(true)
        Agent.run(this, p, Source.MAIN)
    }

    fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micThenListen.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!voice.available) {
            AgentBus.bixiSaid("This phone has no speech recogniser. Type your command instead.", false)
            return
        }
        Speaker.stop()
        listening = true
        partial = ""
        AgentBus.mood.value = RobotMood.LISTENING
        voice.start()
    }

    fun stopListening() {
        voice.stop()
    }

    override fun onReady() = Unit
    override fun onPartial(text: String) {
        partial = text
    }

    override fun onLevel(level: Float) {
        AgentBus.micLevel.value = level
    }

    override fun onResults(hypotheses: List<String>) {
        listening = false
        AgentBus.micLevel.value = 0f
        partial = ""
        submit(Agent.pickBest(this, hypotheses))
    }

    override fun onError(message: String, retryable: Boolean) {
        listening = false
        partial = ""
        AgentBus.micLevel.value = 0f
        AgentBus.mood.value = RobotMood.CONFUSED
        AgentBus.bixiSaid(message, false)
    }

    // ---- setup checklist
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("BatteryLife")
    private fun setupItems(): List<SetupItem> {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        return listOf(
            SetupItem(
                "Accessibility (Bixi's hands)",
                "Lets Bixi tap, scroll, like and type in other apps when you ask.",
                BixiAccessibilityService.isEnabled(this),
                required = true,
            ) { disclosure = true },
            SetupItem("Microphone", "So you can talk to Bixi.", granted(Manifest.permission.RECORD_AUDIO), required = true) {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            },
            SetupItem("Contacts", "Call or message people by name (\"call Mom\").", granted(Manifest.permission.READ_CONTACTS)) {
                permission.launch(Manifest.permission.READ_CONTACTS)
            },
            SetupItem("Phone calls", "Start calls directly instead of opening the dialer.", granted(Manifest.permission.CALL_PHONE)) {
                permission.launch(Manifest.permission.CALL_PHONE)
            },
            SetupItem("Modify system settings", "Change screen brightness.", Settings.System.canWrite(this)) {
                open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            },
            SetupItem("Do not disturb access", "Switch Do Not Disturb and silent mode.", nm?.isNotificationPolicyAccessGranted == true) {
                open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            },
            SetupItem(
                "Battery: unrestricted",
                "Stops One UI from putting Bixi to sleep.",
                pm?.isIgnoringBatteryOptimizations(packageName) == true,
            ) {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            },
        )
    }

    fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    fun openAppInfo() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    // ---- UI
    @Composable
    private fun Main() {
        @Suppress("UNUSED_VARIABLE")
        val t = tick // re-read setup state on every resume
        val items = setupItems()
        Scaffold(
            containerColor = Night,
            bottomBar = {
                NavigationBar(containerColor = Surface1) {
                    val colors = NavigationBarItemDefaults.colors(selectedIconColor = Night, indicatorColor = Cyan)
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Home") }, colors = colors)
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Commands") }, colors = colors)
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Star, null) }, label = { Text("Routines") }, colors = colors)
                    NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") }, colors = colors)
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> HomeScreen(
                        setup = items,
                        listening = listening,
                        partial = partial,
                        onMic = { if (listening) stopListening() else startListening() },
                        onSend = ::submit,
                        onAppInfo = ::openAppInfo,
                    )
                    1 -> CommandsScreen()
                    2 -> RoutinesScreen(onRun = ::submit)
                    else -> SettingsScreen(onOpen = ::open, tick = tick)
                }
            }
        }

        confirm?.let { p ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Should I do this?") },
                text = { Text(p.confirm.orEmpty()) },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = null
                        runPrepared(p)
                    }) { Text("Yes, do it") }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("No") } },
            )
        }

        if (disclosure) {
            AlertDialog(
                onDismissRequest = { disclosure = false },
                title = { Text("Turn on Bixi's hands") },
                text = {
                    Text(
                        "Bixi uses Android's Accessibility service to read what's on your screen and to tap, " +
                            "scroll, like and type for you.\n\n• It only acts when you give a command.\n" +
                            "• Nothing leaves your phone. Bixi has no internet permission.\n" +
                            "• It never types into password or PIN boxes.\n\n" +
                            "On the next screen open \"Installed apps\" (or \"Downloaded apps\"), choose Bixi and switch it on.\n\n" +
                            "Is the switch greyed out? Go back here, tap \"Allow restricted settings\" on the Home tab, then try again.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        disclosure = false
                        open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Open settings") }
                },
                dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Later") } },
            )
        }
    }
}
