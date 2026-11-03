package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.List
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

data class CategoryChoice(val key: String, val title: String, val count: Int? = null)

@Composable
fun CategoryPickerButton(choices: List<CategoryChoice>, selectedKey: String?, onPick: (CategoryChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    TvButton("Categories", icon = Icons.Rounded.List, onClick = { open = true },
        modifier = Modifier.focusRequester(buttonFocus))
    if (open) {
        Dialog(onDismissRequest = {
            open = false
            scope.launch { buttonFocus.safeRequestFocus() }
        }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var query by remember { mutableStateOf("") }
            val visible = remember(choices, query) { choices.filter { it.title.contains(query.trim(), ignoreCase = true) } }
            val selectedIndex = visible.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
            val initialFocus = remember { FocusRequester() }
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                if (visible.isEmpty()) searchFocus.safeRequestFocus() else initialFocus.safeRequestFocus()
            }
            LaunchedEffect(query) { if (query.isNotEmpty()) listState.scrollToItem(0) }
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.88f).heightIn(max = 600.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(listOf(
                    Color(0xFF263044).copy(alpha = 0.84f),
                    Color(0xFF101522).copy(alpha = 0.76f),
                )))
                .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(22.dp))
                .padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose a category", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("${choices.size} categories · press Back to close", color = Color.White.copy(alpha = 0.82f), fontSize = 14.sp)
                    }
                    TvButton("Close", onClick = {
                        open = false
                        scope.launch { buttonFocus.safeRequestFocus() }
                    })
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search categories") },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus))
                Spacer(Modifier.height(14.dp))
                if (visible.isEmpty()) {
                    Text("No matching categories", color = SV.Dim, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(visible, key = { _, choice -> choice.key }) { index, choice ->
                            FocusCard(onClick = { open = false; onPick(choice) }, focusedScale = 1.015f,
                                modifier = Modifier.fillMaxWidth().then(
                                    if (index == selectedIndex) Modifier.focusRequester(initialFocus) else Modifier),
                                background = Color.White.copy(alpha = if (choice.key == selectedKey) 0.16f else 0.04f)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(choice.title, color = Color.White, fontSize = 17.sp,
                                        fontWeight = if (choice.key == selectedKey) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    choice.count?.let {
                                        Text("$it", color = Color.White.copy(alpha = 0.82f), fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                                    }
                                    if (choice.key == selectedKey) Icon(Icons.Rounded.Check, "Selected", tint = Color.White,
                                        modifier = Modifier.padding(start = 12.dp).size(22.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
