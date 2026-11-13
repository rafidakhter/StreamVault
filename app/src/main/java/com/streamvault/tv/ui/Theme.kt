package com.streamvault.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object SV {
    val Bg = Color(0xFF090E13)
    val Surface = Color(0xFF161F24)
    val Surface2 = Color(0xFF233039)
    val Accent = Color(0xFF3E7D72)
    val Accent2 = Color(0xFF7895A5)
    val Dim = Color(0xFFB0BABF)
    val GlassBorder = Color.White.copy(alpha = 0.13f)
    val Ambient = Brush.linearGradient(listOf(Color(0xFF17282B), Bg, Color(0xFF141B24)))
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

/** Translucent layers and a fine highlight provide depth without expensive backdrop blurs. */
fun Modifier.glassPanel(shape: Shape = RoundedCornerShape(18.dp)): Modifier = clip(shape)
    .background(Brush.linearGradient(listOf(SV.Surface2.copy(alpha = 0.91f), SV.Surface.copy(alpha = 0.86f))))
    .border(1.dp, SV.GlassBorder, shape)
