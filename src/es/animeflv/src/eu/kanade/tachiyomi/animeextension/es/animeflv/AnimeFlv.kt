package eu.kanade.tachiyomi.animeextension.es.animeflv

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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.Exception

class AnimeFlv :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "AnimeFLV"

    override val baseUrl = "https://www3.animeflv.net"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("StreamWish", "YourUpload", "Okru", "Streamtape")
    }

    // --------------------------------Video extractors------------------------------------
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers.newBuilder().add("Referer", "$baseUrl/").build()) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?order=rating&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes =
            document.select("div.Container ul.ListAnimes li article").map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.select("div.Description a.Button").attr("abs:href"))
                    title = element.select("a h3").text()
                    thumbnail_url =
                        try {
                            element.select("a div.Image figure img").attr("src")
                        } catch (e: Exception) {
                            element.select("a div.Image figure img").attr("data-cfsrc")
                        }
                    description = element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
                }
            }
        val hasNextPage = document.select("ul.pagination li a[rel=\"next\"]").any()
        return AnimesPage(animes, hasNextPage)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        document.select("script").forEach { script ->
            if (script.data().contains("var anime_info =")) {
                val scriptData = script.data()
                val animeInfo = scriptData.substringAfter("var anime_info = [").substringBefore("];")
                val arrInfo = json.decodeFromString<List<String>>("[$animeInfo]")

                val animeId = arrInfo[0]!!.replace("\"", "")
                val animeUri = arrInfo[2]!!.replace("\"", "")

                val episodes = scriptData.substringAfter("var episodes = [").substringBefore("];").trim()
                val arrEpisodes = episodes.split("],[")
                arrEpisodes!!.forEach { arrEp ->
                    val noEpisode = arrEp!!.replace("[", "")!!.replace("]", "")!!.split(",")!![0]
                    val ep = SEpisode.create()
                    val url = "$baseUrl/ver/$animeUri-$noEpisode"
                    ep.setUrlWithoutDomain(url)
                    ep.name = "Episodio $noEpisode"
                    ep.episode_number = noEpisode.toFloat()
                    // Construir la URL del preview basándose en la estructura de AnimeFlv
                    ep.preview_url = "https://cdn.animeflv.net/screenshots/$animeId/$noEpisode/th_3.jpg"
                    episodeList.add(ep)
                }
            }
        }
        return episodeList
    }

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script:containsData(var videos = {)")?.data() ?: return emptyList()
        val responseString = jsonString.substringAfter("var videos =").substringBefore(";").trim()
        val servers = json.decodeFromString<ServerModel>(responseString).sub

        val hosterMap = mutableMapOf<String, MutableList<Video>>()

        servers.forEach { server ->
            val videos =
                when (server.title) {
                    "Stape" -> listOf(streamTapeExtractor.videoFromUrl(server.url ?: server.code)!!)
                    "Okru" -> okruExtractor.videosFromUrl(server.url ?: server.code)
                    "YourUpload" -> yourUploadExtractor.videoFromUrl(server.url ?: server.code, headers = headers)
                    "SW" -> streamWishExtractor.videosFromUrl(server.url ?: server.code, videoNameGen = { "StreamWish:$it" })
                    else -> universalExtractor.videosFromUrl(server.url ?: server.code, headers)
                }

            if (videos.isNotEmpty()) {
                val serverName = server.title ?: "Unknown"
                hosterMap.getOrPut(serverName) { mutableListOf() }.addAll(videos)
            }
        }

        return hosterMap.map { (serverName, videos) ->
            Hoster(
                hosterName = serverName,
                videoList = videos.sortVideos(),
            )
        }
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val params = AnimeFlvFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/browse?q=$query&page=$page")
            params.filter.isNotBlank() -> GET("$baseUrl/browse${params.getQuery()}&page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFlvFilters.FILTER_LIST

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")!!.attr("abs:src")
        anime.title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!.text()
        anime.description = document.selectFirst("div.Description")!!.text().removeSurrounding("\"")
        anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("span.fa-tv").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int =
        when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes =
            document.select("div.Container ul.ListEpisodios li a.fa-play").map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(
                        element
                            .select("a")
                            .attr("abs:href")
                            .replace("/ver/", "/anime/")
                            .substringBeforeLast("-"),
                    )
                    title = element.select("strong.Title").text()
                    thumbnail_url = element.select("span.Image img").attr("abs:src").replace("thumbs", "covers")
                }
            }
        return AnimesPage(animes, false)
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
    }

    @Serializable
    data class ServerModel(
        @SerialName("SUB")
        val sub: List<Sub> = emptyList(),
    )

    @Serializable
    data class Sub(
        val server: String? = "",
        val title: String? = "",
        val ads: Long? = null,
        val url: String? = null,
        val code: String = "",
        @SerialName("allow_mobile")
        val allowMobile: Boolean? = false,
    )
}
