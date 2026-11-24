package com.bixi.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bixi.agent.data.Routines
import com.bixi.agent.ui.theme.Cyan
import com.bixi.agent.ui.theme.Night
import com.bixi.agent.ui.theme.Soft
import com.bixi.agent.ui.theme.Surface1

@Composable
fun RoutinesScreen(onRun: (String) -> Unit) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(Routines.all(context)) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Routines", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "Your own phrases. Say the phrase and Bixi runs every line, in order. You can also say: " +
                    "\"when I say movie time then turn on DND and max brightness\".",
                color = Soft,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { adding = true }, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night)) {
                Icon(Icons.Filled.Add, null)
                Text("  New routine")
            }
        }
        items(list, key = { it.trigger }) { r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Surface1)
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("“${r.trigger}”", color = Cyan, style = MaterialTheme.typography.titleMedium)
                    r.commands.lines().filter { it.isNotBlank() }.forEach { Text("→ $it", color = Color.White) }
                }
                IconButton(onClick = { onRun(r.trigger) }) { Icon(Icons.Filled.PlayArrow, "Run", tint = Cyan) }
                IconButton(onClick = {
                    Routines.remove(context, r.trigger)
                    list = Routines.all(context)
                }) { Icon(Icons.Filled.Delete, "Delete", tint = Soft) }
            }
        }
    }

    if (adding) {
        var trigger by remember { mutableStateOf("") }
        var commands by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New routine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(trigger, { trigger = it }, label = { Text("When I say…") }, placeholder = { Text("gym time") }, singleLine = true)
                    OutlinedTextField(
                        commands,
                        { commands = it },
                        label = { Text("Bixi does (one per line)") },
                        placeholder = { Text("play workout songs on spotify\nvolume 80\nset a timer for 45 minutes") },
                        minLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = trigger.isNotBlank() && commands.isNotBlank(),
                    onClick = {
                        Routines.add(context, trigger, commands)
                        list = Routines.all(context)
                        adding = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
