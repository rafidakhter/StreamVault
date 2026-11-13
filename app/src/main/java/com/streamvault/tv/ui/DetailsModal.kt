package com.streamvault.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

fun Route.isDetailsModal() = this is Route.MovieDetails || this is Route.SeriesDetails || this is Route.CatalogDetails

/** The underlying route stays composed so scroll, row history and search results survive dismissal. */
@Composable
fun DetailsModal(route: Route, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.97f).clip(RoundedCornerShape(22.dp)).background(SV.Bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Details", color = Color.White)
                TvButton("Close", small = true, onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (route) {
                    is Route.MovieDetails -> MovieDetailsScreen(route.id)
                    is Route.SeriesDetails -> SeriesDetailsScreen(route.key)
                    is Route.CatalogDetails -> CatalogDetailsScreen(route)
                    else -> Unit
                }
            }
        }
    }
}
