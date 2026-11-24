package com.bixi.agent.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bixi.agent.core.AgentBus
import com.bixi.agent.core.Bixi
import com.bixi.agent.ui.theme.Cyan
import com.bixi.agent.ui.theme.Night
import com.bixi.agent.ui.theme.Soft
import com.bixi.agent.ui.theme.Surface1

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpen: (Intent) -> Unit, tick: Int) {
    val prefs = Bixi.prefs
    var speak by remember { mutableStateOf(prefs.speakReplies) }
    var confirm by remember { mutableStateOf(prefs.confirmSensitive) }
    var bubble by remember { mutableStateOf(prefs.showBubble) }
    var offline by remember { mutableStateOf(prefs.preferOffline) }
    var lang by remember { mutableStateOf(prefs.speechLanguage) }
    var seconds by remember { mutableFloatStateOf(prefs.autoScrollSeconds.toFloat()) }
    var messenger by remember { mutableStateOf(prefs.defaultMessenger) }
    var cc by remember { mutableStateOf(prefs.countryCode) }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold) }

        item {
            Card {
                Toggle("Speak replies", "Bixi answers out loud.", speak) {
                    speak = it
                    prefs.speakReplies = it
                }
                Toggle("Ask before risky actions", "Confirm before messaging, calling, commenting, sharing to someone or paying.", confirm) {
                    confirm = it
                    prefs.confirmSensitive = it
                }
                Toggle("Floating robot", "Show Bixi over other apps (needs the Accessibility switch).", bubble) {
                    bubble = it
                    prefs.showBubble = it
                    AgentBus.bubbleVisible.value = it
                }
            }
        }

        item {
            Card {
                Title("Speech")
                Toggle("Prefer offline speech", "Recognise speech on the phone. If the offline pack is missing, Bixi falls back to the normal recogniser.", offline) {
                    offline = it
                    prefs.preferOffline = it
                }
                Text("Language", color = Color.White)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en-IN" to "English (India)", "en-US" to "English (US)", "en-GB" to "English (UK)", "hi-IN" to "हिन्दी").forEach { (code, name) ->
                        FilterChip(
                            selected = lang == code,
                            onClick = {
                                lang = code
                                prefs.speechLanguage = code
                            },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan, selectedLabelColor = Night),
                        )
                    }
                }
                Text(
                    "For fully offline speech, download the language pack: Settings → General management → Keyboard list and default → " +
                        "Google voice typing → Offline speech recognition → English (India).",
                    color = Soft,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Card {
                Title("Reels")
                Text("Auto-scroll every ${seconds.toInt()} seconds", color = Color.White)
                Slider(
                    value = seconds,
                    onValueChange = { seconds = it },
                    onValueChangeFinished = { prefs.autoScrollSeconds = seconds.toInt() },
                    valueRange = 3f..30f,
                    steps = 26,
                    colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan),
                )
            }
        }

        item {
            Card {
                Title("Messages")
                Text("When you just say \"message Mom saying hi\", use:", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("whatsapp" to "WhatsApp", "sms" to "SMS").forEach { (key, name) ->
                        FilterChip(
                            selected = messenger == key,
                            onClick = {
                                messenger = key
                                prefs.defaultMessenger = key
                            },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cyan, selectedLabelColor = Night),
                        )
                    }
                }
                OutlinedTextField(
                    value = cc,
                    onValueChange = { v ->
                        cc = v.filter { it.isDigit() }.take(4)
                        if (cc.isNotEmpty()) prefs.countryCode = cc
                    },
                    label = { Text("Country code for WhatsApp") },
                    prefix = { Text("+") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(220.dp),
                )
            }
        }

        item {
            Card {
                Title("Call Bixi faster")
                Bullet("Floating robot: tap to talk, long-press to type, drag to move.")
                Bullet("Quick Settings: swipe down twice, tap ✎ (edit) and add the \"Bixi\" tile.")
                Bullet("Side key: Settings → Advanced features → Side button → Double press → Open app → \"Bixi Talk\".")
                Bullet("Assistant gesture: make Bixi your digital assistant, then swipe up from a bottom corner (or long-press Home).")
                OutlinedButton(onClick = { onOpen(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }) { Text("Choose digital assistant app") }
            }
        }

        item {
            Card {
                Title("Privacy")
                Bullet("Bixi has no internet permission. Your commands never leave the phone.")
                Bullet("It only acts when you tap it, talk to it or type to it.")
                Bullet("It never types into password or PIN boxes and never completes a payment. You enter UPI PINs yourself.")
                Bullet("Some banking apps hide their screens from all assistants. Bixi can still open them for you.")
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Surface1)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun Title(text: String) = Text(text, color = Cyan, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Bullet(text: String) = Text("•  $text", color = Soft)

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White)
            Text(subtitle, color = Soft, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Night))
    }
}
