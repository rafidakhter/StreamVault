package com.streamvault.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SV {
    val Bg = Color(0xFF09090F)
    val Surface = Color(0xFF15151F)
    val Surface2 = Color(0xFF22222F)
    val Accent = Color(0xFF8B5CF6)
    val Accent2 = Color(0xFFEC4899)
    val Dim = Color(0xFFA3A3B8)
    val Live = Color(0xFFEF4444)
}

@Composable
fun StreamVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SV.Accent,
            secondary = SV.Accent2,
            background = SV.Bg,
            surface = SV.Surface,
            surfaceVariant = SV.Surface2,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = SV.Dim,
        ),
        content = content,
    )
}
