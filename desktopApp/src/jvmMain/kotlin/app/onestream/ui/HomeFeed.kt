package app.onestream.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One shelf of Home. [id] is unique for the life of the feed, so it can key the lazy list. */
data class FeedRow(val id: Int, val provider: String, val name: String, val list: List<SearchResponse>)

/** A title the user watched or saved; its provider's "recommendations" become a "Because you watched" shelf. */
class FeedSeed(val apiName: String, val url: String, val title: String)

/**
 * Home's endless feed. Shelves are fetched a few at a time as the page is scrolled (lazy loading); when every section's
 * first page is used up it asks for the next page of each, and so on until the providers have nothing new left.
 * Recommendation shelves from the viewer's own history and library are mixed in, one after every three shelves.
 * Lives in [app.onestream.AppState] so it survives opening a title and coming back.
 */
class HomeFeed {
    var rows by mutableStateOf<List<FeedRow>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var exhausted by mutableStateOf(false)
        private set
    var key: List<String>? = null
        private set

    private class Section(val api: MainAPI, val data: MainPageData) {
        val seen = HashSet<String>()
        var alive = true
    }

    private val sections = ArrayList<Section>()
    private val queue = ArrayList<suspend () -> List<FeedRow>>()
    private val seenRecs = HashSet<String>()
    private val seqNo = java.util.concurrent.atomic.AtomicInteger()
    private var nextPage = 1
    private var find: (String) -> MainAPI? = { null }

    /** Starts over when the set of providers changes; otherwise keeps what is already loaded. */
    fun ensure(key: List<String>, apis: List<MainAPI>, seeds: List<FeedSeed>, find: (String) -> MainAPI?) {
        if (this.key == key) return
        this.key = key; this.find = find
        rows = emptyList(); exhausted = false; seqNo.set(0); nextPage = 1
        sections.clear(); queue.clear(); seenRecs.clear()
        apis.forEach { api -> api.mainPage.forEach { sections += Section(api, it) } }
        // Round-robin over providers so no single one fills the top of the page
        val byApi = sections.groupBy { it.api.name }.values.toList()
        val ordered = (0 until (byApi.maxOfOrNull { it.size } ?: 0)).flatMap { i -> byApi.mapNotNull { it.getOrNull(i) } }
        val pages = ordered.map { s -> suspend { pageRows(s, 1) } }
        val recs = seeds.distinctBy { it.apiName + it.url }.take(10).map { s -> suspend { recommendationRows(s) } }
        val recIter = recs.iterator()
        pages.forEachIndexed { i, p ->
            queue += p
            if ((i + 1) % 3 == 0 && recIter.hasNext()) queue += recIter.next()
        }
        recIter.forEachRemaining { queue += it }
        nextPage = 2
    }

    /** Fetches the next few shelves. Cancelling it midway leaves them queued for the next call. */
    suspend fun loadMore() {
        if (loading || exhausted) return
        loading = true
        try {
            if (queue.isEmpty()) {
                val alive = sections.filter { it.alive }
                if (alive.isEmpty()) { exhausted = true; return }
                val page = nextPage++
                alive.forEach { s -> queue += { pageRows(s, page) } }
            }
            val batch = queue.take(3)
            val got = withContext(Dispatchers.IO) {
                coroutineScope {
                    batch.map { load ->
                        async {
                            try { withTimeout(30_000) { load() } }
                            catch (e: TimeoutCancellationException) { emptyList() }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { emptyList() } // one provider failing must not stop the feed
                        }
                    }.awaitAll()
                }
            }.flatten()
            queue.removeAll(batch.toSet())
            rows = rows + got
        } finally { loading = false }
    }

    private suspend fun pageRows(s: Section, page: Int): List<FeedRow> {
        val result = try {
            s.api.getMainPage(page, MainPageRequest(s.data.name, s.data.data, s.data.horizontalImages))
        } catch (e: Exception) { s.alive = false; throw e }
        val out = result?.items.orEmpty().mapNotNull { row ->
            val fresh = row.list.filter { s.seen.add(it.url) } // a provider that ignores the page number only repeats itself
            if (fresh.isEmpty()) null else FeedRow(seqNo.getAndIncrement(), s.api.name, if (page > 1) "${row.name} · more" else row.name, fresh)
        }
        if (out.isEmpty()) s.alive = false
        return out
    }

    private suspend fun recommendationRows(seed: FeedSeed): List<FeedRow> {
        val recs = find(seed.apiName)?.load(seed.url)?.recommendations.orEmpty().filter { seenRecs.add(it.url) }
        return if (recs.isEmpty()) emptyList() else listOf(FeedRow(seqNo.getAndIncrement(), seed.apiName, "Because you watched ${seed.title}", recs))
    }
}
