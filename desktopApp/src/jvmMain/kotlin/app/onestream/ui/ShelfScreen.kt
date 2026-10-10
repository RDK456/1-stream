package app.onestream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onestream.AppState
import app.onestream.Screen

/** "Show all" from a Home row: every title of that row in a grid. */
@Composable
fun ShelfScreen(app: AppState, s: Screen.Shelf) {
    val side = LocalSide.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton("Back", { app.pop() }, icon = IconKind.Back)
            Text(s.title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${s.items.size} titles", color = Brand.Muted)
        }
        LazyVerticalGrid(
            GridCells.Adaptive(if (LocalCompact.current) 110.dp else 160.dp), Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 32.dp),
        ) {
            itemsIndexed(s.items) { i, r -> PosterCard(r.name, r.posterUrl, subtitle = r.apiName, index = i) { app.push(Screen.Details(r.apiName, r.url)) } }
        }
    }
}
