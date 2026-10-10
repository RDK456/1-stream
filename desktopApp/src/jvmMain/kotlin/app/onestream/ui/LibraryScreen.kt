package app.onestream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.onestream.AppState
import app.onestream.Bookmark
import app.onestream.Screen

private fun key(b: Bookmark) = "${b.apiName}|${b.url}"

/** Only titles the user added with "Add to library". Edit mode selects several and removes them. */
@Composable
fun LibraryScreen(app: AppState) {
    val data by app.store.data.collectAsState()
    val items = data.bookmarks
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    if (items.isEmpty()) {
        Message("Your library is empty", "Open a title and press “Add to library”. Things you watch are in History.")
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(if (LocalCompact.current) 110.dp else 150.dp), Modifier.fillMaxSize().padding(horizontal = LocalSide.current),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { SectionHeader("Library", trailing = "${items.size} titles") }
                if (editing) {
                    GhostButton("Select all", { selected = items.map(::key).toSet() })
                    GradientButton("Remove ${selected.size}", { app.store.removeBookmarks(selected); selected = emptySet() }, enabled = selected.isNotEmpty())
                    GhostButton("Done", { editing = false; selected = emptySet() })
                } else GhostButton("Edit", { editing = true })
            }
        }
        itemsIndexed(items, key = { _, b -> key(b) }) { i, b ->
            val on = key(b) in selected
            Box {
                PosterCard(
                    b.name, b.posterUrl, subtitle = b.apiName, index = i,
                    modifier = if (on) Modifier.border(2.dp, Brand.Red, RoundedCornerShape(6.dp)) else Modifier,
                ) {
                    if (editing) selected = if (on) selected - key(b) else selected + key(b)
                    else app.push(Screen.Details(b.apiName, b.url))
                }
                if (editing) Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).size(24.dp).clip(CircleShape)
                        .background(if (on) Brand.Red else Brand.Ink.copy(alpha = 0.7f)).border(1.dp, Brand.Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (on) Text("✓", color = Color(0xFF05080C), fontWeight = FontWeight.Bold) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}
