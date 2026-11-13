package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamvault.tv.data.Repo
import com.streamvault.tv.data.UserProfile

private val profileColors = listOf(Color(0xFF406D66), Color(0xFF526A81), Color(0xFF7E6753),
    Color(0xFF6B627F), Color(0xFF5B7160), Color(0xFF7E5961))

@Composable
fun ProfileAvatar(profile: UserProfile, modifier: Modifier = Modifier) {
    val color = profileColors[(profile.color % profileColors.size + profileColors.size) % profileColors.size]
    Box(modifier.clip(CircleShape).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.65f))))
        .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
        .semantics { contentDescription = "${profile.name} profile" }, contentAlignment = Alignment.Center) {
        Text(profile.initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

@Composable
fun ProfilePickerDialog(onDismiss: () -> Unit, onPick: (UserProfile) -> Unit) {
    val profiles by Repo.profiles.collectAsState()
    val active by Repo.activeProfile.collectAsState()
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<UserProfile?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val initialFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(editing, managing) {
        if (editing) nameFocus.safeRequestFocus() else initialFocus.safeRequestFocus()
    }
    fun save() {
        runCatching {
            val profile = target?.let { Repo.renameProfile(it.id, name) } ?: Repo.addProfile(name)
            if (target == null) onPick(profile) else { editing = false; error = null }
        }.onFailure { error = it.message ?: "Unable to save profile." }
    }
    Dialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxWidth(0.9f).heightIn(max = 580.dp)
            .glassPanel(RoundedCornerShape(24.dp)).verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(if (editing) if (target == null) "Add a profile" else "Edit profile" else if (managing) "Manage profiles" else "Who's watching?",
                        color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (editing) "Just a name. No sign-in needed." else "Your history, your list, your space.", color = SV.Dim, fontSize = 14.sp)
                }
                TvButton("Close", small = true, onClick = onDismiss)
            }
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ProfileAvatar(UserProfile("preview", name.ifBlank { "You" }, target?.color ?: profiles.size), Modifier.size(64.dp))
                    OutlinedTextField(name, onValueChange = { name = it.take(40); error = null }, label = { Text("Name") },
                        singleLine = true, modifier = Modifier.weight(1f).focusRequester(nameFocus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }))
                }
                error?.let { Text(it, color = SV.Live, fontSize = 14.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton(if (target == null) "Create profile" else "Save name", primary = true, small = true, onClick = { save() })
                    TvButton("Back", small = true, onClick = { editing = false; error = null })
                    if (target != null && profiles.size > 1) TvButton("Remove profile", small = true, onClick = { confirmRemove = true })
                }
            } else {
                profiles.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { profile ->
                            FocusCard(onClick = {
                                if (managing) { target = profile; name = profile.name; editing = true; error = null }
                                else onPick(profile)
                            }, focusedScale = 1.025f, shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.weight(1f).height(140.dp)
                                    .then(if (profile.id == active.id) Modifier.focusRequester(initialFocus) else Modifier)) {
                                Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                                    ProfileAvatar(profile, Modifier.size(54.dp))
                                    Text(profile.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (profile.id == active.id) Text("Current profile", color = SV.Dim, fontSize = 11.sp)
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (profiles.size < 8) TvButton("Add profile", small = true, onClick = { target = null; name = ""; editing = true; error = null })
                    TvButton(if (managing) "Done" else "Manage profiles", small = true, onClick = { managing = !managing })
                }
            }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, containerColor = SV.Surface,
        title = { Text("Remove ${target?.name}?") },
        text = { Text("This removes this profile's watch history, My List, and preferences. Shared playlists stay on the TV.") },
        confirmButton = {
            TvButton("Remove", small = true, onClick = {
                val profile = target ?: return@TvButton
                runCatching { Repo.removeProfile(profile.id) }
                    .onSuccess { confirmRemove = false; editing = false; error = null }
                    .onFailure { confirmRemove = false; error = it.message }
            })
        }, dismissButton = { TvButton("Keep profile", small = true, onClick = { confirmRemove = false }) })
}
