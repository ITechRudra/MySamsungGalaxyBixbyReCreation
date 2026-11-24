package com.bixi.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bixi.agent.ui.theme.Cyan
import com.bixi.agent.ui.theme.Soft
import com.bixi.agent.ui.theme.Surface1

private val groups = listOf(
    "Reels & videos" to listOf(
        "open reels  ·  open YouTube shorts",
        "next  ·  next reel  ·  skip 5 reels  ·  previous",
        "like  ·  unlike  ·  dislike  ·  double tap",
        "share  ·  share this with Mom on WhatsApp",
        "comment nice video  ·  open comments",
        "follow  ·  subscribe  ·  save this reel",
        "keep scrolling  ·  auto scroll every 5 seconds",
        "keep scrolling and liking  ·  stop",
        "pause  ·  play  ·  mute",
    ),
    "Apps" to listOf(
        "open WhatsApp  ·  launch Instagram  ·  open YONO SBI",
        "open WhatsApp status  ·  open Instagram reels",
        "go back  ·  go home  ·  recent apps  ·  close all apps",
        "show notifications  ·  quick settings",
        "uninstall Candy Crush",
    ),
    "People" to listOf(
        "call Mom  ·  call 98765 43210",
        "video call Rahul  ·  WhatsApp call Papa",
        "send good morning to Mom on WhatsApp",
        "message Priya saying I'm on my way",
        "send an SMS to Dad saying call me",
        "open chat with Amit",
    ),
    "Money (you always enter the PIN)" to listOf(
        "open Google Pay  ·  open PhonePe",
        "pay 500 to rahul@okaxis",
        "send 200 rupees to Mom on GPay",
    ),
    "Phone controls" to listOf(
        "turn on WiFi  ·  Bluetooth off  ·  flashlight",
        "turn on do not disturb  ·  dark mode on",
        "volume up  ·  set volume to 40  ·  max volume",
        "brightness 70 percent  ·  make the screen brighter",
        "take a screenshot  ·  lock the phone",
        "battery level  ·  what time is it",
    ),
    "Anything on screen" to listOf(
        "tap Login  ·  click Follow back  ·  press OK",
        "tap the first result  ·  open the second chat",
        "type Hello there  ·  send  ·  press enter",
        "scroll down  ·  scroll to the top  ·  find Settings",
        "read the screen  ·  read my notifications",
        "copy  ·  paste  ·  clear the text",
    ),
    "Search, maps, media, clock" to listOf(
        "search cute cats on YouTube  ·  google weather in Delhi",
        "search iPhone 16 on Amazon",
        "play Arijit Singh on Spotify  ·  next song",
        "navigate to India Gate  ·  pizza near me",
        "set an alarm for 6:30 am  ·  set a timer for 10 minutes",
        "remind me to drink water in 20 minutes",
        "take a selfie  ·  click a photo",
    ),
    "Chains, routines & Hinglish" to listOf(
        "open Instagram then keep scrolling",
        "like and share  ·  turn on WiFi and Bluetooth",
        "when I say good night then turn on DND and lock the screen",
        "WhatsApp kholo  ·  agli reel  ·  like karo",
        "Mummy ko call karo  ·  awaaz badhao  ·  screenshot lo",
        "again (repeats your last command)",
    ),
)

@Composable
fun CommandsScreen() {
    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("What you can say", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "Bixi understands these offline. Say \"please\", \"Hey Bixi\" or \"for me\" freely, and chain commands with \"then\" or \"and\".",
                color = Soft,
            )
        }
        items(groups) { (title, lines) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Surface1)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, color = Cyan, style = MaterialTheme.typography.titleMedium)
                lines.forEach { Text("“$it”", color = Color.White, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
