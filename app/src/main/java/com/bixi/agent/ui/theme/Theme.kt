package com.bixi.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Night = Color(0xFF090C1F)
val Surface1 = Color(0xFF12163A)
val Surface2 = Color(0xFF1A1F4A)
val Blue = Color(0xFF5B8CFF)
val Violet = Color(0xFF6A6CF6)
val Purple = Color(0xFF9B5CF6)
val Cyan = Color(0xFF3CF2E3)
val Soft = Color(0xFFB4B9E8)

private val scheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF263070),
    onPrimaryContainer = Color.White,
    secondary = Cyan,
    onSecondary = Night,
    tertiary = Purple,
    background = Night,
    onBackground = Color(0xFFE8EAFF),
    surface = Surface1,
    onSurface = Color(0xFFE8EAFF),
    surfaceVariant = Surface2,
    onSurfaceVariant = Soft,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    outline = Color(0xFF3A4180),
    error = Color(0xFFFF6B8B),
)

val BixiGradient = Brush.linearGradient(listOf(Blue, Violet, Purple))

@Composable
fun BixiTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
