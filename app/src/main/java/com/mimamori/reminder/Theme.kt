package com.mimamori.reminder

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF2E7D32)
val GreenDark = Color(0xFF1B5E20)
val Ink = Color(0xFF15202B)
val Muted = Color(0xFF5B6770)
val Line = Color(0xFFDDE3E8)
val Paper = Color(0xFFFFFDF5)
val Amber = Color(0xFFB26A00)
val Red = Color(0xFFC62828)

@Composable
fun MimamoriTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green,
            onPrimary = Color.White,
            secondary = GreenDark,
            background = Paper,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            error = Red,
        ),
        content = content,
    )
}
