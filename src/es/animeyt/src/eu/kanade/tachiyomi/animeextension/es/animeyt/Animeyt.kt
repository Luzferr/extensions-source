package eu.kanade.tachiyomi.animeextension.es.animeyt

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Animeyt :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "AnimeYT"

    override val baseUrl = "https://ytanime.tv"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Fastream"
        private val SERVER_LIST = arrayOf("Fastream")
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/mas-populares?page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.video-block div.row div.col-md-2 div.video-card")
        val hasNextPage = document.select("ul.pagination li.page-item:last-child a").any()
        val animeList =
            elements.map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.select("div.video-card div.video-card-body div.video-title a").attr("href"))
                    title = element.select("div.video-card div.video-card-body div.video-title a").text()
                    thumbnail_url = element.select("div.video-card div.video-card-image a:nth-child(2) img").attr("src")
                }
            }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url.toAbsoluteUrl(), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val elements = document.select("#caps ul.list-group li.list-group-item a")
        return elements
            .mapIndexed { index, element ->
                val epNum = getNumberFromEpsString(element.select("span.sa-series-link__number").text())
                val epParsed =
                    when {
                        epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: (index + 1).toFloat()
                        else -> (index + 1).toFloat()
                    }
                SEpisode.create().apply {
                    setUrlWithoutDomain(element.attr("href"))
                    episode_number = epParsed
                    name = "Episodio $epParsed"
                }
            }
    }

    private fun getNumberFromEpsString(epsStr: String): String = epsStr.filter { it.isDigit() }

    override fun hosterListRequest(episode: SEpisode): Request = GET(episode.url.toAbsoluteUrl(), headers)

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val hosters = mutableListOf<Hoster>()

        document.select("#plays iframe").forEach { container ->
            val server =
                container
                    .attr("src")
                    .split(".")[0]
                    .replace("https://", "")
                    .replace("http://", "")

            var url = container.attr("src")
            if (server == "fastream") {
                if (url.contains("emb.html")) {
                    val key = url.split("/").last()
                    url = "https://fastream.to/embed-$key.html"
                }
                val videos = FastreamExtractor(client, headers).videosFromUrl(url)
                if (videos.isNotEmpty()) {
                    hosters.add(
                        Hoster(
                            hosterName = "Fastream",
                            videoList = videos.sortVideos(),
                        ),
                    )
                }
            }
        }
        return hosters
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this
            .sortedWith(
                compareBy(
                    { it.videoTitle.contains(server, true) },
                    { it.videoTitle.contains(quality) },
                    {
                        Regex("""(\d+)p""")
                            .find(it.videoTitle)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull() ?: 0
                    },
                ),
            ).reversed()
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = GET("$baseUrl/search?q=$query&page=$page")

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            thumbnail_url =
                document
                    .selectFirst(
                        "div.sa-series-dashboard__poster div.sa-layout__line.sa-layout__line--sm div figure.sa-poster__fig img",
                    )?.attr("src")
                    ?: ""
            title = document.selectFirst("#info div.sa-layout__line div div.sa-title-series__title span")?.html() ?: ""
            description = document.selectFirst("#info div.sa-layout__line p.sa-text")?.text()?.removeSurrounding("\"") ?: ""
            // genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
            status = parseStatus(document.select("#info > div:nth-child(2) > button").text())
        }
    }

    private fun parseStatus(statusString: String): Int =
        when {
            statusString.contains("En Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

    private fun String.toAbsoluteUrl(): String =
        if (startsWith("http", true)) {
            this
        } else {
            val separator = if (startsWith("/")) "" else "/"
            "$baseUrl$separator$this"
        }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/ultimos-animes?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY_KEY
                title = "Preferred quality"
                entries = QUALITY_LIST
                entryValues = QUALITY_LIST
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"

                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }.also(screen::addPreference)

        ListPreference(screen.context)
            .apply {
                key = PREF_SERVER_KEY
                title = "Preferred server"
                entries = SERVER_LIST
                entryValues = SERVER_LIST
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "%s"

                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }.also(screen::addPreference)
    }
}
