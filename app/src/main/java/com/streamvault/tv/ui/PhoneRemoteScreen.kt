package com.streamvault.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.tv.remote.PhoneRemote
import kotlinx.coroutines.delay

@Composable
fun PhoneRemoteScreen(focusRequest: Int = 0) {
    val context = LocalContext.current
    val state by PhoneRemote.state.collectAsState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { PhoneRemote.enable(context) }
    LaunchedEffect(focusRequest) { firstFocus.safeRequestFocus() }
    LaunchedEffect(state.address) {
        if (state.address != null) { delay(120_000); PhoneRemote.refreshCode() }
    }
    val qr = remember(state.address) { state.address?.let { runCatching { qrBitmap(it) }.getOrNull() } }
    Row(Modifier.fillMaxSize().padding(36.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your phone. Your TV.", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Browse StreamVault on your phone and play on this screen.", color = SV.Dim, fontSize = 18.sp)
            Text("1  Connect your phone to the same Wi-Fi.\n\n2  Scan this QR code with your camera.\n\n3  Pick a movie, series episode, or live channel.",
                color = Color.White, fontSize = 17.sp)
            Text("Play, pause, seek, and switch episodes from the phone. Keep StreamVault open on the TV.", color = SV.Dim, fontSize = 15.sp)
            Text("${state.phones} phone${if (state.phones == 1) "" else "s"} paired", color = SV.Accent, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Refresh QR", modifier = Modifier.focusRequester(firstFocus), small = true,
                    onClick = { PhoneRemote.enable(context); PhoneRemote.refreshCode() })
                TvButton("Disconnect phones", small = true, onClick = { PhoneRemote.disconnectPhones() })
            }
            TvButton("Turn off phone remote", small = true, onClick = { PhoneRemote.disable() })
            state.error?.let { Text(it, color = SV.Live, fontSize = 14.sp) }
        }
        Column(Modifier.width(280.dp).align(Alignment.CenterVertically).glassPanel(RoundedCornerShape(22.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (qr != null) Image(qr, "Scan to pair your phone", Modifier.size(232.dp).background(Color.White))
            else Text("Phone remote is off or the TV has no local network connection.", color = SV.Dim)
            Text(state.address?.substringBefore("/#").orEmpty(), color = Color.White, fontSize = 14.sp)
            Text("QR codes expire after 2 minutes. Paired phones stay connected while the app is open.", color = SV.Dim, fontSize = 13.sp)
            Text("For use on your trusted home Wi-Fi.", color = SV.Dim, fontSize = 12.sp)
        }
    }
}
