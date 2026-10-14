package app.onestream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.onestream.AppState
import app.onestream.Bookmark
import app.onestream.Screen
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One playable thing: a movie, a live stream or a single episode. */
data class PlayItem(
    val data: String,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val description: String? = null,
)

fun LoadResponse.playItems(): List<PlayItem> = when (this) {
    is MovieLoadResponse -> if (dataUrl.isBlank()) emptyList() else listOf(PlayItem(dataUrl, name))
    is LiveStreamLoadResponse -> listOf(PlayItem(dataUrl, name))
    is TvSeriesLoadResponse -> episodes.map { it.toItem() }
    is AnimeLoadResponse -> {
        val pick = episodes[DubStatus.Subbed] ?: episodes[DubStatus.Dubbed] ?: episodes.values.firstOrNull().orEmpty()
        pick.map { it.toItem() }
    }
    else -> emptyList()
}

private fun com.lagradost.cloudstream3.Episode.toItem() = PlayItem(
    data = data,
    title = name ?: episode?.let { "Episode $it" } ?: "Episode",
    season = season,
    episode = episode,
    posterUrl = posterUrl,
    description = description,
)

private sealed interface Loaded {
    data object Busy : Loaded
    data class Failed(val message: String) : Loaded
    data class Ready(val r: LoadResponse) : Loaded
}

@Composable
fun DetailsScreen(app: AppState, s: Screen.Details) {
    val api = app.api(s.apiName)
    var state by remember(s) { mutableStateOf<Loaded>(Loaded.Busy) }
    LaunchedEffect(s) {
        state = Loaded.Busy
        state = if (api == null) Loaded.Failed("Provider \"${s.apiName}\" is not installed.")
        else runCatching { withContext(Dispatchers.IO) { withTimeout(45_000) { api.load(s.url) } } }
            .fold(
                { r -> if (r == null) Loaded.Failed("The provider returned nothing for this title.") else Loaded.Ready(r) },
                { Loaded.Failed(it.message ?: it.javaClass.simpleName) },
            )
    }
    Box(Modifier.fillMaxSize()) {
        when (val st = state) {
            Loaded.Busy -> Loading(label = "Loading…")
            is Loaded.Failed -> Message("Could not load this title", st.message, isError = true)
            is Loaded.Ready -> DetailsContent(app, s, st.r)
        }
        GhostButton("←  Back", app::pop, Modifier.padding(20.dp))
    }
}

@Composable
private fun DetailsContent(app: AppState, s: Screen.Details, r: LoadResponse) {
    val data by app.store.data.collectAsState()
    val side = LocalSide.current
    val bookmarked = data.bookmarks.any { it.url == s.url && it.apiName == s.apiName }
    val items = remember(r) { r.playItems() }
    val history = data.history.firstOrNull { it.url == s.url && it.apiName == s.apiName }
    val seasons = remember(items) { items.mapNotNull { it.season }.distinct().sorted() }
    var seasonIdx by remember(items) { mutableStateOf(0) }
    val season = seasons.getOrNull(seasonIdx)
    val shown = if (season == null) items else items.filter { it.season == season }

    var dl by remember { mutableStateOf<PlayItem?>(null) }

    fun playOf(item: PlayItem, startMs: Long = 0): Screen.Play {
        val p = Screen.Play(s.apiName, s.url, r.name, r.posterUrl, item.data, item.title.takeIf { items.size > 1 }, startMs)
        // Built on demand, so a long series does not create hundreds of screens up front
        val i = items.indexOf(item)
        if (i >= 0 && i + 1 < items.size) p.next = { playOf(items[i + 1]) }
        return p
    }
    fun play(item: PlayItem, startMs: Long = 0) = app.push(playOf(item, startMs))

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
              Box(Modifier.fillMaxWidth().height((maxWidth * 0.45f).coerceIn(300.dp, 520.dp))) {
                r.posterUrl?.let {
                    AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Brand.Ink.copy(alpha = 0.92f), Brand.Ink.copy(alpha = 0.35f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Brand.Ink)))
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = side).padding(bottom = 18.dp).widthIn(max = 680.dp).enter(2), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val size = if (LocalCompact.current) 28.sp else 46.sp
                        Text(r.name, fontSize = size, fontWeight = FontWeight.Black, lineHeight = size * 1.1f, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOfNotNull(r.year?.toString(), r.type.name, r.duration?.let { "$it min" }, r.score?.toString(), s.apiName)
                                .forEach { Chip(it) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val resume = history?.takeIf { it.positionMs > 5_000 }
                            val resumeItem = resume?.let { h -> items.firstOrNull { it.data == h.data } }
                            if (resume != null && resumeItem != null) {
                                GradientButton("Resume ${resume.episodeName?.let { "$it · " } ?: ""}${formatTime(resume.positionMs)}", { play(resumeItem, resume.positionMs) }, icon = IconKind.Play)
                                GhostButton("Start over", { play(resumeItem) }, icon = IconKind.Replay)
                            } else if (items.isNotEmpty()) {
                                GradientButton(if (items.size == 1) "Play" else "Play first episode", { play(items.first()) }, icon = IconKind.Play)
                            } else {
                                Text("Nothing to play yet", color = Brand.Muted)
                            }
                            GhostButton(
                                if (bookmarked) "In library" else "Add to library",
                                { app.store.toggleBookmark(Bookmark(r.name, s.url, s.apiName, r.posterUrl)) },
                                icon = if (bookmarked) IconKind.Check else IconKind.Add,
                            )
                            if (items.size == 1) GhostButton("Download", { dl = items.first() }, icon = IconKind.Download)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = side, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                r.plot?.let { Plate(Modifier.enter(3), title = "Overview") { Text(it, lineHeight = 22.sp) } }
                r.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(tags) { Chip(it) } }
                }
            }
        }
        if (items.size > 1) item {
            Column(Modifier.padding(top = 8.dp)) {
                Column(Modifier.padding(horizontal = side)) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.clickable(enabled = seasons.size > 1) { open = true }.pointerHoverIcon(PointerIcon.Hand),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(3.dp).height(36.dp).background(Brand.Red))
                            Column(Modifier.padding(start = 10.dp)) {
                                Row {
                                    Text(if (season != null) "Season $season" else "Episodes", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    if (seasons.size > 1) Text("  ▾", fontSize = 18.sp, color = Brand.Muted)
                                }
                                Text("${shown.size} episodes", style = MaterialTheme.typography.labelMedium, color = Brand.Muted)
                            }
                        }
                        DropdownMenu(open, { open = false }) {
                            seasons.forEachIndexed { i, n -> DropdownMenuItem({ Text("Season $n") }, { seasonIdx = i; open = false }) }
                        }
                    }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = side, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shown) { ep ->
                        val h = history?.takeIf { it.data == ep.data && it.positionMs > 5_000 }
                        EpisodeCard(ep, h?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f }, { dl = ep }) { play(ep, h?.positionMs ?: 0) }
                    }
                }
            }
        }
        r.actors?.takeIf { it.isNotEmpty() }?.let { cast ->
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(36.dp).background(Brand.Red))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("Cast", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("The cast behind this title", style = MaterialTheme.typography.labelMedium, color = Brand.Muted)
                        }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = side, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cast.take(30)) { a ->
                            Column(Modifier.width(124.dp)) {
                                Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(6.dp)).background(Brand.PanelHi)) {
                                    a.actor.image?.let { AsyncImage(it, a.actor.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                }
                                Text(a.actor.name, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                (a.roleString ?: a.role?.name)?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Brand.Muted, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        }
    }
    dl?.let { item ->
        DownloadDialog(app, s, item, if (items.size > 1) "${r.name} - ${item.title}" else r.name) { dl = null }
    }
}
/** Movy-style episode tile: still image, number, title, two lines of synopsis, thin progress bar. */
@Composable
private fun EpisodeCard(ep: PlayItem, progress: Float?, onDownload: () -> Unit, onPlay: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.width(300.dp).height(190.dp).hoverLift(src, 1.03f).clip(shape).background(Brand.Panel)
            .border(1.dp, lerp(Brand.Line, Color.White, hover * 0.6f), shape)
            .clickable(src, null, onClick = onPlay).pointerHoverIcon(PointerIcon.Hand),
    ) {
        ep.posterUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.92f))))
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDownload).padding(6.dp)) {
            Glyph(IconKind.Download, Color.White, 16.dp)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ep.episode?.let { "EPISODE $it" } ?: "EPISODE", fontSize = 10.sp, letterSpacing = 1.sp, color = Brand.Muted, fontWeight = FontWeight.Medium)
            Text(ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            ep.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
        }
        if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0xFF5A5A5A))) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Brand.Red))
        }
    }
}
