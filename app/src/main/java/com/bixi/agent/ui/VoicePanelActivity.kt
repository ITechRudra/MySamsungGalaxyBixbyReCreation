package com.bixi.agent.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.bixi.agent.R
import com.bixi.agent.core.Agent
import com.bixi.agent.core.AgentBus
import com.bixi.agent.core.Bixi
import com.bixi.agent.core.Prepared
import com.bixi.agent.core.RobotMood
import com.bixi.agent.core.Source
import com.bixi.agent.core.Speaker
import com.bixi.agent.nlu.Normalizer
import com.bixi.agent.ui.robot.BixiRobot
import com.bixi.agent.ui.theme.BixiGradient
import com.bixi.agent.ui.theme.BixiTheme
import com.bixi.agent.ui.theme.Cyan
import com.bixi.agent.ui.theme.Soft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The bottom sheet that appears over any app: listens (or takes typing), then gets out of the way. */
class VoicePanelActivity : ComponentActivity(), VoiceInput.Listener {

    companion object {
        const val EXTRA_TYPING = "typing"
    }

    private enum class Mode { IDLE, LISTENING, TYPING, PROCESSING, CONFIRM, REPLY, ERROR }

    private var mode by mutableStateOf(Mode.IDLE)
    private var transcript by mutableStateOf("")
    private var status by mutableStateOf("")
    private var confirmText by mutableStateOf("")
    private var typed by mutableStateOf("")
    private var pending: Prepared? = null
    private var listeningForConfirm = false
    private var awaitingResult = false
    private var closing = false
    private lateinit var voice: VoiceInput

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingResult = false
        if (granted) {
            listen()
        } else {
            mode = Mode.TYPING
            status = "The microphone is off for Bixi, so type your command."
        }
    }

    private val systemSpeech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        awaitingResult = false
        val list = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
        if (list.isNotEmpty()) onResults(list) else idle("Tap the mic to talk.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        voice = VoiceInput(this, this)
        AgentBus.panelOpen.value = true
        setContent { BixiTheme { PanelScreen() } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closing = false
        AgentBus.panelOpen.value = true
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        pending = null
        transcript = ""
        typed = ""
        if (intent?.getBooleanExtra(EXTRA_TYPING, false) == true) {
            voice.cancel()
            mode = Mode.TYPING
            status = "Type a command"
        } else {
            listen()
        }
    }

    private fun idle(message: String) {
        mode = Mode.IDLE
        status = message
        AgentBus.mood.value = RobotMood.IDLE
    }

    private fun listen(forConfirm: Boolean = false) {
        listeningForConfirm = forConfirm
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitingResult = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        Speaker.stop()
        if (!voice.available) {
            try {
                awaitingResult = true
                systemSpeech.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Bixi.prefs.speechLanguage)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5),
                )
            } catch (_: ActivityNotFoundException) {
                awaitingResult = false
                mode = Mode.TYPING
                status = "This phone has no speech recogniser. Type instead."
            }
            return
        }
        if (!forConfirm) {
            mode = Mode.LISTENING
            transcript = ""
            status = "Listening…"
        }
        AgentBus.mood.value = RobotMood.LISTENING
        voice.start()
    }

    // ---- VoiceInput.Listener
    override fun onReady() {
        if (!listeningForConfirm && mode == Mode.LISTENING) status = "Listening…"
    }

    override fun onPartial(text: String) {
        if (!listeningForConfirm) transcript = text
    }

    override fun onLevel(level: Float) {
        AgentBus.micLevel.value = level
    }

    override fun onResults(hypotheses: List<String>) {
        AgentBus.micLevel.value = 0f
        if (listeningForConfirm) {
            listeningForConfirm = false
            when (hypotheses.firstNotNullOfOrNull { Normalizer.yesNo(it) }) {
                true -> confirm()
                false -> cancelConfirm()
                null -> {
                    status = "Tap Yes or No"
                    AgentBus.mood.value = RobotMood.IDLE
                }
            }
            return
        }
        val text = Agent.pickBest(this, hypotheses)
        transcript = text
        process(text)
    }

    override fun onError(message: String, retryable: Boolean) {
        AgentBus.micLevel.value = 0f
        if (listeningForConfirm) {
            listeningForConfirm = false
            status = "Tap Yes or No"
            AgentBus.mood.value = RobotMood.IDLE
            return
        }
        mode = Mode.ERROR
        status = message
        AgentBus.mood.value = RobotMood.CONFUSED
    }

    // ---- running commands
    private fun process(text: String) {
        if (text.isBlank()) return
        mode = Mode.PROCESSING
        status = "Got it"
        AgentBus.mood.value = RobotMood.THINKING
        val p = Agent.prepare(this, text)
        if (p.confirm != null) {
            pending = p
            confirmText = p.confirm
            mode = Mode.CONFIRM
            status = "Say yes or no"
            listen(forConfirm = true)
            return
        }
        execute(p)
    }

    private fun execute(p: Prepared) {
        voice.cancel()
        if (p.conversational) {
            mode = Mode.REPLY
            Agent.run(this, p, Source.PANEL) { outcome ->
                status = outcome.text
                lifecycleScope.launch {
                    delay(3800)
                    if (mode == Mode.REPLY) closePanel()
                }
            }
            return
        }
        Agent.run(this, p, Source.PANEL)
        closePanel()
    }

    private fun confirm() {
        val p = pending ?: return
        pending = null
        execute(p)
    }

    private fun cancelConfirm() {
        pending = null
        voice.cancel()
        AgentBus.bixiSaid("Okay, cancelled.")
        Speaker.speak("Okay, cancelled")
        closePanel()
    }

    private fun closePanel() {
        if (closing) return
        closing = true
        voice.cancel()
        AgentBus.panelOpen.value = false
        if (AgentBus.mood.value == RobotMood.LISTENING) AgentBus.mood.value = RobotMood.IDLE
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !awaitingResult) closePanel()
    }

    override fun onDestroy() {
        voice.destroy()
        AgentBus.panelOpen.value = false
        AgentBus.micLevel.value = 0f
        if (AgentBus.mood.value == RobotMood.LISTENING) AgentBus.mood.value = RobotMood.IDLE
        super.onDestroy()
    }

    // ---- UI
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun PanelScreen() {
        val mood by AgentBus.mood.collectAsState()
        val mic by AgentBus.micLevel.collectAsState()
        val none = remember { MutableInteractionSource() }

        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = none, indication = null) { closePanel() },
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF171C4C), Color(0xFF0B0F2A))))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
                Spacer(Modifier.height(8.dp))
                BixiRobot(mood = mood, micLevel = mic, modifier = Modifier.size(124.dp))
                Text(status, color = Cyan, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                if (transcript.isNotBlank() && mode != Mode.CONFIRM && mode != Mode.TYPING) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "“$transcript”",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(14.dp))
                when (mode) {
                    Mode.CONFIRM -> ConfirmCard()
                    Mode.TYPING -> TypingRow()
                    else -> {
                        Suggestions()
                        Spacer(Modifier.height(14.dp))
                        MicRow(mic)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Suggestions() {
        val ideas = listOf("Next reel", "Like", "Share", "Keep scrolling", "Open WhatsApp", "Go back")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
            ideas.forEach { idea ->
                AssistChip(
                    onClick = {
                        transcript = idea
                        process(idea)
                    },
                    label = { Text(idea) },
                    colors = AssistChipDefaults.assistChipColors(labelColor = Color.White),
                )
            }
        }
    }

    @Composable
    private fun MicRow(level: Float) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            IconButton(onClick = {
                voice.cancel()
                mode = Mode.TYPING
                status = "Type a command"
                AgentBus.mood.value = RobotMood.IDLE
            }) {
                Icon(painterResource(R.drawable.ic_keyboard), contentDescription = "Type instead", tint = Soft)
            }
            val listening = mode == Mode.LISTENING
            Box(
                Modifier
                    .size(76.dp)
                    .border((2 + 6 * level).dp, Cyan.copy(alpha = if (listening) 0.35f + 0.5f * level else 0f), CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(BixiGradient)
                    .clickable { if (listening) voice.stop() else listen() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Talk", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = { closePanel() }) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Soft)
            }
        }
    }

    @Composable
    private fun TypingRow() {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            delay(150)
            focus.requestFocus()
            keyboard?.show()
        }
        fun send() {
            val t = typed.trim()
            if (t.isEmpty()) return
            keyboard?.hide()
            transcript = t
            process(t)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("e.g. open YouTube and search cricket") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            IconButton(onClick = { send() }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Cyan)
            }
            IconButton(onClick = {
                keyboard?.hide()
                listen()
            }) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Talk instead", tint = Soft)
            }
        }
    }

    @Composable
    private fun ConfirmCard() {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Should I do this?", color = Soft, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Text(confirmText, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { cancelConfirm() }) { Text("No") }
                Button(
                    onClick = { confirm() },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF090C1F)),
                ) { Text("Yes, do it") }
            }
        }
    }
}
