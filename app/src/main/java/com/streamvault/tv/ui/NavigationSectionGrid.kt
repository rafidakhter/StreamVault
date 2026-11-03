package com.streamvault.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Non-scrolling grids inside the page's outer LazyColumn: no nested vertical scroll traps. */
@Composable
fun NavigationSectionGrid(title: String, section: NavigationSection, firstFocus: FocusRequester?,
    enabled: Boolean, restoreItemKey: String?, onFocused: (String, FocusRequester) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        section.tiles.chunked(section.columns).forEachIndexed { rowIndex, tiles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                tiles.forEachIndexed { columnIndex, tile ->
                    key(tile.key) {
                        val requester = remember { FocusRequester() }
                        val first = rowIndex == 0 && columnIndex == 0
                        FocusCard(onClick = tile.onClick, onFocused = { onFocused(tile.key, requester) }, focusedScale = 1.035f,
                            modifier = Modifier.weight(1f).height(if (section.compact) 52.dp else 90.dp)
                                .focusProperties { canFocus = enabled }.focusRequester(requester)
                                .then(if ((tile.key == restoreItemKey || (restoreItemKey == null && first)) && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
                            background = SV.Surface2.copy(alpha = 0.85f)) {
                            Text(tile.label, color = Color.White, fontSize = if (section.compact) 16.sp else 22.sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = 10.dp))
                        }
                    }
                }
                repeat(section.columns - tiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
