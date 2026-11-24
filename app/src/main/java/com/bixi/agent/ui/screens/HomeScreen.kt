package com.bixi.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bixi.agent.R
import com.bixi.agent.core.AgentBus
import com.bixi.agent.core.LogEntry
import com.bixi.agent.ui.robot.BixiRobot
import com.bixi.agent.ui.theme.BixiGradient
import com.bixi.agent.ui.theme.Cyan
import com.bixi.agent.ui.theme.Night
import com.bixi.agent.ui.theme.Soft
import com.bixi.agent.ui.theme.Surface1
import com.bixi.agent.ui.theme.Surface2

data class SetupItem(
    val title: String,
    val why: String,
    val done: Boolean,
    val required: Boolean = false,
    val fix: () -> Unit,
)

@Composable
fun HomeScreen(
    setup: List<SetupItem>,
    listening: Boolean,
    partial: String,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onAppInfo: () -> Unit,
) {
    val mood by AgentBus.mood.collectAsState()
    val mic by AgentBus.micLevel.collectAsState()
    val gaze by AgentBus.gaze.collectAsState()
    val log by AgentBus.log.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(false) }
    val missingRequired = setup.any { it.required && !it.done }
    val doneCount = setup.count { it.done }

    Column(Modifier.imePadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = false,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xFF1B2160), Night)))
                        .statusBarsPadding()
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BixiRobot(mood = mood, micLevel = mic, gaze = gaze, modifier = Modifier.size(190.dp))
                    Text("Hi, I'm Bixi", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (listening) partial.ifBlank { "Listening…" } else "Your offline voice assistant",
                        color = if (listening) Cyan else Soft,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            item {
                SetupCard(setup, missingRequired, doneCount, showSetup || missingRequired, { showSetup = !showSetup }, onAppInfo)
            }

            if (log.isEmpty()) {
                item { TipsCard() }
            } else {
                items(log.takeLast(30)) { LogBubble(it) }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Surface1)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type a command…") },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    onSend(text)
                    text = ""
                }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Cyan),
                modifier = Modifier.weight(1f),
            )
            if (text.isNotBlank()) {
                IconButton(onClick = {
                    onSend(text)
                    text = ""
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Cyan) }
            }
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(56.dp)
                    .border((2 + 5 * mic).dp, Cyan.copy(alpha = if (listening) 0.4f + 0.5f * mic else 0f), CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(BixiGradient)
                    .clickable(onClick = onMic),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_mic), "Talk to Bixi", tint = Color.White)
            }
        }
    }
}

@Composable
private fun SetupCard(
    setup: List<SetupItem>,
    missingRequired: Boolean,
    doneCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAppInfo: () -> Unit,
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onToggle)) {
            Icon(
                if (missingRequired) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                null,
                tint = if (missingRequired) Color(0xFFFFC857) else Cyan,
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (missingRequired) "Finish setting up Bixi" else "Bixi is ready",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text("$doneCount of ${setup.size} done", color = Soft, style = MaterialTheme.typography.bodySmall)
            }
            if (!missingRequired) Text(if (expanded) "Hide" else "Show", color = Cyan)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            setup.forEach { item ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title + if (item.required) "" else "  (optional)",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(item.why, color = Soft, style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.done) {
                        Icon(Icons.Filled.CheckCircle, "Done", tint = Cyan)
                    } else if (item.required) {
                        Button(onClick = item.fix, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night)) { Text("Allow") }
                    } else {
                        OutlinedButton(onClick = item.fix) { Text("Allow") }
                    }
                }
            }
            if (!setup.first().done) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Is the Bixi switch greyed out in Accessibility? Android blocks it for apps installed from a file until you allow it: " +
                        "open App info → ⋮ (top right) → \"Allow restricted settings\", then try again.",
                    color = Soft,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onAppInfo) { Text("Open App info") }
            }
        }
    }
}

@Composable
private fun TipsCard() {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface2)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("How to use me", style = MaterialTheme.typography.titleMedium, color = Color.White)
        listOf(
            "Tap the floating robot in any app and speak.",
            "Long-press the robot to type instead.",
            "Drag the robot to move it.",
            "Try: \"open Instagram reels\", \"keep scrolling and liking\", \"stop\".",
            "Try: \"send good morning to Mom on WhatsApp\".",
            "Hinglish works too: \"WhatsApp kholo\", \"agli reel\", \"like karo\".",
        ).forEach { Text("•  $it", color = Soft) }
    }
}

@Composable
private fun LogBubble(e: LogEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = if (e.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            e.text,
            color = if (e.fromUser) Night else Color.White,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    when {
                        e.fromUser -> Cyan
                        e.ok -> Surface2
                        else -> Color(0xFF4A1F3A)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}
