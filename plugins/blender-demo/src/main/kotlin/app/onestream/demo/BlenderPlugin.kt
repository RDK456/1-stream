package app.onestream.demo

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Reference plugin: a handful of Creative Commons films from the Blender Foundation.
 * Doubles as the template for writing real providers and as the app's end-to-end test fixture.
 */
class BlenderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BlenderOpenMovies())
    }
}

class BlenderOpenMovies : MainAPI() {
    override var name = "Blender Open Movies"
    override var mainUrl = "https://studio.blender.org"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    override val mainPage = listOf(com.lagradost.cloudstream3.MainPageData("Open movies", "all"))

    private class Film(
        val id: String,
        val title: String,
        val year: Int,
        val plot: String,
        val poster: String?,
        /** quality (p) to url */
        val streams: List<Pair<Int, String>>,
    )

    private val wiki = "https://upload.wikimedia.org/wikipedia/commons/thumb"
    private val films = listOf(
        Film(
            "sintel-trailer", "Sintel (Trailer)", 2010,
            "A lonely young woman, Sintel, helps and befriends a dragon. Trailer for the Blender Foundation's open movie.",
            "$wiki/8/8f/Sintel_poster.jpg/330px-Sintel_poster.jpg",
            listOf(
                480 to "https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4",
                720 to "https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4",
            ),
        ),
        Film(
            "bbb-trailer", "Big Buck Bunny (Trailer)", 2008,
            "A giant rabbit takes revenge on three bullying rodents. Trailer for the Blender Foundation's open movie.",
            "$wiki/c/c5/Big_buck_bunny_poster_big.jpg/330px-Big_buck_bunny_poster_big.jpg",
            listOf(480 to "https://download.blender.org/peach/trailer/trailer_480p.mov"),
        ),
        Film(
            "tears-of-steel", "Tears of Steel", 2012,
            "In an apocalyptic future, a group of soldiers and scientists takes refuge in Amsterdam to try to stop an army of robots.",
            null,
            listOf(720 to "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov"),
        ),
        Film(
            "bbb-hls", "Big Buck Bunny (HLS test stream)", 2008,
            "Adaptive HLS version of Big Buck Bunny, handy for checking streaming playback.",
            "$wiki/c/c5/Big_buck_bunny_poster_big.jpg/330px-Big_buck_bunny_poster_big.jpg",
            listOf(0 to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
        ),
    )

    private fun Film.toSearch(): SearchResponse =
        newMovieSearchResponse(title, "$mainUrl/film/$id", TvType.Movie) { posterUrl = poster; year = this@toSearch.year }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        newHomePageResponse(request.name, films.map { it.toSearch() }, hasNext = false)

    override suspend fun search(query: String): List<SearchResponse> =
        films.filter { it.title.contains(query.trim(), ignoreCase = true) }.map { it.toSearch() }

    override suspend fun load(url: String): LoadResponse? {
        val film = films.firstOrNull { url.endsWith("/film/${it.id}") } ?: return null
        return newMovieLoadResponse(film.title, url, TvType.Movie, film.id) {
            posterUrl = film.poster
            year = film.year
            plot = film.plot
            tags = listOf("Blender Foundation", "Creative Commons")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val film = films.firstOrNull { it.id == data } ?: return false
        film.streams.forEach { (quality, link) ->
            callback(
                newExtractorLink(name, "$name ${if (quality > 0) "${quality}p" else "HLS"}", link,
                    if (link.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = "https://studio.blender.org/"
                }
            )
        }
        return true
    }
}