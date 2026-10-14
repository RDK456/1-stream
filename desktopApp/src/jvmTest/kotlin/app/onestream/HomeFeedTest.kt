package app.onestream

import app.onestream.ui.FeedSeed
import app.onestream.ui.HomeFeed
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two sections, each with 3 pages of fresh titles, then nothing; one title has recommendations. */
private class PagedApi : MainAPI() {
    override var name = "Paged"
    override var mainUrl = "https://paged.example"
    override val hasMainPage = true
    override val mainPage = listOf(com.lagradost.cloudstream3.MainPageData("Hot", "hot"), com.lagradost.cloudstream3.MainPageData("New", "new"))
    var calls = 0
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        calls++
        val items = if (page > 3) emptyList() else (1..4).map { newMovieSearchResponse("${request.data} p$page #$it", "https://paged.example/${request.data}/$page/$it", TvType.Movie) }
        return newHomePageResponse(HomePageList(request.name, items))
    }
    override suspend fun load(url: String): LoadResponse =
        newMovieLoadResponse("Seed", url, TvType.Movie, url) {
            recommendations = listOf(newMovieSearchResponse("Similar", "https://paged.example/similar", TvType.Movie))
        }
}

class HomeFeedTest {
    @Test fun loadsLazilyThenEndlesslyThenStops() = runBlocking {
        val api = PagedApi()
        val feed = HomeFeed()
        feed.ensure(listOf("Paged"), listOf(api), listOf(FeedSeed("Paged", "https://paged.example/seed", "Seed"))) { api }
        assertEquals(0, api.calls) // nothing is fetched until asked
        feed.loadMore()
        assertTrue(api.calls <= 3 && feed.rows.isNotEmpty(), "one batch only: ${api.calls}")
        var guard = 0
        while (!feed.exhausted && guard++ < 50) feed.loadMore()
        assertTrue(feed.exhausted, "feed ends when providers run dry")
        val names = feed.rows.map { it.name }
        assertTrue(names.any { it.endsWith("· more") }, "later pages appear: $names")
        assertTrue(names.any { it == "Because you watched Seed" }, "recommendation shelf: $names")
        assertEquals(6, feed.rows.count { !it.name.startsWith("Because") }, "2 sections x 3 pages")
        assertEquals(feed.rows.size, feed.rows.map { it.id }.toSet().size, "ids are unique")
    }
}
