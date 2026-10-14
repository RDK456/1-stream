package app.onestream.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onestream.AppState
import app.onestream.Bookmark
import app.onestream.Screen
import app.onestream.Tab
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TIMEOUT_MS = 30_000L

@Composable
fun SearchScreen(app: AppState) {
    val providers = app.providers
    if (providers.isEmpty()) {
        EmptyHome(app)
        return
    }
    val side = LocalSide.current

    val scope = rememberCoroutineScope()
    var query by app::query
    var selected by app::searchProvider // 0 = all (filtered) providers
    // Kept in the app data, so they survive switching tabs, opening a title and restarting
    val saved by app.store.data.collectAsState()
    val langFilter = saved.filters["homeLang"]
    val typeFilter = saved.filters["homeType"]
    var showFilters by remember { mutableStateOf(false) }
    var showScrape by remember { mutableStateOf(false) }
    if (showScrape) ScrapeDialog(app) { showScrape = false }
    val shown = providers.filter { p -> (langFilter == null || p.lang == langFilter) && (typeFilter == null || p.supportedTypes.any { it.name == typeFilter }) }
    var results by app::searchResults
    var failures by app::searchFailures
    var loading by remember { mutableStateOf(false) }
    var searched by app::searched
    val selectedName = shown.getOrNull(selected - 1)?.name
    // Adult results stay hidden unless that type is picked on purpose
    fun typeOk(t: com.lagradost.cloudstream3.TvType?) = if (typeFilter == null) t != com.lagradost.cloudstream3.TvType.NSFW else t?.name == typeFilter

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) { searched = false; results = emptyList(); failures = emptyList(); return }
        val targets = shown.filter { selectedName == null || it.name == selectedName }
        scope.launch {
            loading = true; searched = true
            val outcomes = withContext(Dispatchers.IO) {
                coroutineScope {
                    targets.map { api ->
                        async { api.name to runCatching { withTimeout(TIMEOUT_MS) { api.search(q, 1)?.items.orEmpty() } } }
                    }.awaitAll()
                }
            }
            results = outcomes.flatMap { it.second.getOrNull().orEmpty() }
            failures = outcomes.filter { it.second.isFailure }.map { "${it.first}: ${it.second.exceptionOrNull()?.message ?: "failed"}" }
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        val active = listOfNotNull(langFilter, typeFilter).size + if (selected > 0) 1 else 0
        Row(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { SearchBar(query, { query = it }, ::runSearch) }
            GhostButton("Paste link", { showScrape = true })
            GhostButton(if (active > 0) "Filters ($active)" else "Filters", { showFilters = !showFilters })
        }
        // The filter panel scrolls on its own so a short window can still reach the results below it
        if (showFilters) Column(Modifier.padding(horizontal = side).padding(bottom = 6.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            val langs = providers.groupingBy { it.lang }.eachCount().entries.sortedByDescending { it.value }.map { FilterOption(it.key, langName(it.key) + " ${it.value}") }
            val types = providers.flatMap { it.supportedTypes }.groupingBy { it.name }.eachCount().entries.sortedByDescending { it.value }
                .map { FilterOption(it.key, (if (it.key == "NSFW") "NSFW 18+" else it.key) + " ${it.value}") }
            FilterRow("Language", langs, langFilter, { app.store.setFilter("homeLang", it); selected = 0 })
            FilterRow("Type", types, typeFilter, { app.store.setFilter("homeType", it); selected = 0 })
            if (shown.size > 1) PillTabs(
                listOf("All ${shown.size}") + shown.map { it.name }, selected, { selected = it },
                Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                shown.isEmpty() -> Message("No providers match", "Clear a filter to see them again.")
                loading -> Loading(label = "Searching…")
                searched -> Results(app, results.filter { typeOk(it.type) }, failures)
                else -> Browse(app, shown.filter { selectedName == null || it.name == selectedName }, ::typeOk)
            }
        }
    }
}

@Composable
private fun EmptyHome(app: AppState) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalSide.current), contentAlignment = Alignment.Center) {
        Column(Modifier.enter().widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark(64.dp)
            Text("Nothing to watch yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Providers supply the content. Add a repository in Plugins and install a provider to get started.",
                color = Brand.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            GradientButton("Open Plugins", { app.tab = Tab.Plugins })
        }
    }
}

@Composable
private fun SearchBar(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, lerp(Color(0xFF777777), Color.White, hover), shape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(IconKind.Search, Color.White, 20.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Titles, people, genres", color = Brand.Muted, maxLines = 1)
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth().onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { onSearch(); true } else false
                },
                singleLine = true, interactionSource = src,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
            )
        }
        GradientButton("Search", onSearch)
    }
}

@Composable
private fun Results(app: AppState, results: List<SearchResponse>, failures: List<String>) {
    if (results.isEmpty() && failures.isEmpty()) { Message("No results", "Try another title or provider."); return }
    val side = LocalSide.current
    LazyVerticalGrid(
        GridCells.Adaptive(if (LocalCompact.current) 110.dp else 160.dp), Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 32.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SectionHeader("Results", trailing = "${results.size} found")
                if (failures.isNotEmpty()) Text(
                    failures.joinToString("\n"), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        itemsIndexed(results) { i, r ->
            PosterCard(r.name, r.posterUrl, subtitle = r.apiName, index = i) { app.push(Screen.Details(r.apiName, r.url)) }
        }
    }
}

/** Home: a billboard on top, then one titled row of posters per provider list. */
@Composable
private fun Browse(app: AppState, providers: List<MainAPI>, typeOk: (com.lagradost.cloudstream3.TvType?) -> Boolean) {
    val apis = providers.filter { it.hasMainPage }
    if (apis.isEmpty()) {
        Message("Search for a title", "None of the selected providers have a browse page.")
        return
    }
    val side = LocalSide.current
    val key = apis.map { it.name }
    val data by app.store.data.collectAsState()
    val feed = app.feed
    // Shelves of titles similar to what was watched or saved, mixed into the feed
    LaunchedEffect(key) {
        val seeds = data.history.map { FeedSeed(it.apiName, it.url, it.name) } + data.bookmarks.map { FeedSeed(it.apiName, it.url, it.name) }
        feed.ensure(key, apis, seeds, app::api)
    }
    // Lazy loading: when the last shelf is near, fetch the next few. Unlimited: the feed asks for later pages once the first are used.
    LaunchedEffect(feed) {
        while (true) {
            snapshotFlow {
                val li = app.homeScroll.layoutInfo
                feed.key != null && !feed.loading && !feed.exhausted && li.totalItemsCount - (li.visibleItemsInfo.lastOrNull()?.index ?: 0) <= 4
            }.first { it }
            feed.loadMore()
        }
    }
    val list = feed.rows.map { it.copy(list = it.list.filter { r -> typeOk(r.type) }) }.filter { it.list.isNotEmpty() }
    // Started but not finished, newest first
    val resume = data.history.filter { it.durationMs > 0 && it.positionMs > 15_000 && it.positionMs < it.durationMs * 0.95 }.take(15)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // About 190dp per poster, with a partly visible last one to show that the row scrolls
        val posterW = (maxWidth - side) / (maxWidth / 190.dp).coerceIn(2.5f, 8.5f) - 8.dp
        val featured = list.take(3).flatMap { it.list }.filter { it.posterUrl != null }.distinctBy { it.url }.take(5)
        LazyColumn(Modifier.fillMaxSize(), state = app.homeScroll, contentPadding = PaddingValues(bottom = 40.dp)) {
            if (featured.isNotEmpty()) item {
                Billboard(app, featured, maxWidth) {
                    list.flatMap { it.list }.randomOrNull()?.let { app.push(Screen.Details(it.apiName, it.url)) }
                }
            }
            if (resume.isNotEmpty()) item {
                PosterShelf("Continue watching", null, { app.tab = Tab.History }) {
                    rowItemsIndexed(resume) { i, h ->
                        PosterCard(
                            h.name, h.posterUrl, subtitle = h.episodeName ?: "${formatTime(h.durationMs - h.positionMs)} left",
                            progress = h.positionMs.toFloat() / h.durationMs, modifier = Modifier.width(posterW), index = i,
                            onInfo = { app.push(Screen.Details(h.apiName, h.url)) },
                        ) { app.push(Screen.Play(h.apiName, h.url, h.name, h.posterUrl, h.data, h.episodeName, h.positionMs)) }
                    }
                }
            }
            if (data.bookmarks.isNotEmpty()) item {
                PosterShelf("My list", null, { app.tab = Tab.Library }) {
                    rowItemsIndexed(data.bookmarks) { i, b ->
                        PosterCard(b.name, b.posterUrl, modifier = Modifier.width(posterW), index = i) { app.push(Screen.Details(b.apiName, b.url)) }
                    }
                }
            }
            when {
                list.isEmpty() && !feed.exhausted -> item { HomeSkeleton(posterW, hero = resume.isEmpty() && data.bookmarks.isEmpty()) }
                list.isEmpty() -> item { Message("Nothing to show", modifier = Modifier.fillMaxWidth().height(240.dp)) }
                else -> {
                    items(list, key = { it.id }) { row ->
                        PosterShelf(row.name, row.provider.takeIf { apis.size > 1 }, { app.push(Screen.Shelf(row.name, row.list)) }) {
                            rowItemsIndexed(row.list) { i, r ->
                                PosterCard(r.name, r.posterUrl, modifier = Modifier.width(posterW), index = i) { app.push(Screen.Details(r.apiName, r.url)) }
                            }
                        }
                    }
                    if (!feed.exhausted) item { Loading(Modifier.fillMaxWidth().height(120.dp)) }
                }
            }
        }
    }
}

/** A titled row of posters. The vertical padding leaves room for the hovered poster to grow. */
@Composable
private fun PosterShelf(title: String, tag: String?, onAll: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    val side = LocalSide.current
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    // A page at a time: most of what is visible, so the last poster seen stays on screen as the next page starts
    fun page(dir: Int) = scope.launch { state.animateScrollBy(dir * state.layoutInfo.viewportSize.width * 0.8f) }
    Column(Modifier.padding(top = 10.dp)) {
        Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (tag != null) Text(tag, Modifier.padding(start = 10.dp, bottom = 2.dp), color = Brand.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (onAll != null) Text(
                "Show all ›", Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onAll).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 8.dp, vertical = 4.dp),
                color = Brand.Muted, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            )
        }
        Box(Modifier.hoverable(src)) {
            LazyRow(state = state, contentPadding = PaddingValues(horizontal = side, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
            if (hovered && state.canScrollBackward) ShelfArrow("‹", Modifier.align(Alignment.CenterStart), toLeft = true) { page(-1) }
            if (hovered && state.canScrollForward) ShelfArrow("›", Modifier.align(Alignment.CenterEnd), toLeft = false) { page(1) }
        }
    }
}

/** A fade at the shelf edge with a big chevron; clicking pages the row. */
@Composable
private fun ShelfArrow(glyph: String, modifier: Modifier, toLeft: Boolean, onClick: () -> Unit) {
    val fade = listOf(Brand.Ink.copy(alpha = 0.9f), Color.Transparent)
    Box(
        modifier.padding(vertical = 14.dp).fillMaxHeight().width(52.dp)
            .background(Brush.horizontalGradient(if (toLeft) fade else fade.reversed()))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 40.sp, fontWeight = FontWeight.Light, color = Color.White) }
}

/** Full-width featured title: art fills the width, fades into the page, big title and two actions bottom left. */
@Composable
private fun Billboard(app: AppState, items: List<SearchResponse>, width: androidx.compose.ui.unit.Dp, onSurprise: () -> Unit) {
    var index by remember(items) { mutableStateOf(0) }
    val reduce = LocalReduceMotion.current
    val side = LocalSide.current
    val compact = LocalCompact.current
    val data by app.store.data.collectAsState()
    LaunchedEffect(items) {
        while (items.size > 1) { delay(8_000); index = (index + 1) % items.size }
    }
    Box(Modifier.fillMaxWidth().height((width * 0.4f).coerceIn(260.dp, 520.dp))) {
        AnimatedContent(
            index, Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(if (reduce) Motion.Ms.fast else 800)) togetherWith fadeOut(tween(if (reduce) Motion.Ms.fast else 800)) },
        ) { i ->
            val item = items[i]
            val inLibrary = data.bookmarks.any { it.url == item.url && it.apiName == item.apiName }
            Box(Modifier.fillMaxSize()) {
                AsyncImage(item.posterUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Brand.Ink.copy(alpha = 0.92f), Brand.Ink.copy(alpha = 0.35f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Brand.Ink)))
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = side, end = side, bottom = if (compact) 28.dp else 56.dp).widthIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
                ) {
                    val size = if (compact) 28.sp else 48.sp
                    Text(item.name, fontSize = size, fontWeight = FontWeight.Black, lineHeight = size * 1.1f, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        item.type?.let { Text(it.name, color = Brand.Muted, fontWeight = FontWeight.Medium) }
                        Text(item.apiName, color = Brand.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton("Play", { app.push(Screen.Details(item.apiName, item.url)) }, icon = IconKind.Play)
                        GhostButton("More info", { app.push(Screen.Details(item.apiName, item.url)) })
                        if (!compact) GhostButton(
                            "", { app.store.toggleBookmark(Bookmark(item.name, item.url, item.apiName, item.posterUrl)) },
                            icon = if (inLibrary) IconKind.Check else IconKind.Add,
                        )
                    }
                }
            }
        }
        if (items.size > 1 && !compact) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = side, bottom = 62.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.indices.forEach { i ->
                Box(
                    Modifier.height(3.dp).width(if (i == index) 28.dp else 18.dp)
                        .background(if (i == index) Color.White else Color.White.copy(alpha = 0.35f)).clickable { index = i },
                )
            }
        }
    }
}
