package eu.kanade.tachiyomi.animeextension.es.animejl

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
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
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class Animejl :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Animejl"

    override val baseUrl = "https://www.anime-jl.net"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("StreamWish", "YourUpload", "Okru", "StreamTape", "StreamHideVid", "Voe", "Uqload", "Mp4upload")
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?order=rating&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes =
            document.select("div.Container ul.ListAnimes li article").map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.select("div.Description a.Button").attr("abs:href"))
                    title = element.select("a h3").text()
                    thumbnail_url = element.select("a div.Image figure img").attr("src").replace("/storage", "$baseUrl/storage")
                    description = element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
                }
            }
        val hasNextPage = document.selectFirst("ul.pagination li a[rel=\"next\"]") != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val script = document.select("script:containsData(var episodes =)").firstOrNull()?.data() ?: return emptyList()

        val episodesPattern = Regex("var episodes = (\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL)
        val episodesMatch = episodesPattern.find(script) ?: return emptyList()
        val episodesString = episodesMatch.groupValues[1]

        val animeInfoPattern = Regex("var anime_info = \\[(.*?)\\];")
        val animeInfoMatch = animeInfoPattern.find(script) ?: return emptyList()
        val animeInfo = animeInfoMatch.groupValues[1].split(",").map { it.trim('"') }

        val animeSlug = animeInfo.getOrNull(2) ?: ""
        val animeId = animeInfo.getOrNull(0) ?: ""
        val episodePattern = Regex("\\[(\\d+),\"(.*?)\",\"(.*?)\",\"(.*?)\"\\]")
        val episodeMatches = episodePattern.findAll(episodesString)

        episodeMatches.forEach { match ->
            try {
                val episodeNumber = match.groupValues[1].toIntOrNull() ?: 0
                val episodeImage = match.groupValues[3].takeIf { it.isNotBlank() }
                val url = "$baseUrl/anime/$animeId/$animeSlug/episodio-$episodeNumber"
                val episode = SEpisode.create()
                episode.setUrlWithoutDomain(url)
                episode.episode_number = episodeNumber.toFloat()
                episode.name = "Episodio $episodeNumber"
                episodeImage?.let {
                    episode.preview_url = if (it.startsWith("http")) it else "$baseUrl/storage/$it"
                }
                episodeList.add(episode)
            } catch (e: Exception) {
                Log.e("Animejl", "Error processing episode: ${e.message}")
            }
        }

        return episodeList.sortedByDescending { it.episode_number }
    }

    override fun seasonListParse(response: Response) = throw UnsupportedOperationException()

    // --------------------------------Video extractors------------------------------------
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val scriptContent =
            document.selectFirst("script:containsData(var video = [)")?.data()
                ?: return emptyList()
        val videoList = mutableListOf<Video>()
        val videoPattern = Regex("""video\[\d+\] = '<iframe src="(.*?)"""")
        val matches = videoPattern.findAll(scriptContent)
        matches.forEach { match ->
            val url = match.groupValues[1]
            val videos =
                when {
                    url.contains("streamtape") -> listOfNotNull(streamTapeExtractor.videoFromUrl(url))
                    url.contains("ok.ru") -> okruExtractor.videosFromUrl(url)
                    url.contains("yourupload") -> yourUploadExtractor.videoFromUrl(url, headers)
                    url.contains("streamwish") || url.contains("playerwish") -> streamWishExtractor.videosFromUrl(url)
                    url.contains("streamhidevid") -> streamHideVidExtractor.videosFromUrl(url)
                    url.contains("voe") -> voeExtractor.videosFromUrl(url)
                    url.contains("uqload") -> uqloadExtractor.videosFromUrl(url)
                    url.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(url, headers)
                    else -> universalExtractor.videosFromUrl(url, headers)
                }
            videoList.addAll(videos)
        }

        val hosterMap =
            videoList.groupBy { video ->
                video.videoTitle.substringBefore(":").trim()
            }

        return hosterMap.map { (name, videos) ->
            Hoster(hosterName = name, videoList = videos)
        }
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val params = AnimejlFilters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?q=$query&page=$page")
            params.filter.isNotBlank() -> GET("$baseUrl/animes${params.getQuery()}&page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimejlFilters.FILTER_LIST

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes =
            document.select("div.Container ul.ListAnimes li article").map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.select("div.Description a.Button").attr("abs:href"))
                    title = element.select("a h3").text()
                    thumbnail_url = element.select("a div.Image figure img").attr("src").replace("/storage", "$baseUrl/storage")
                    description = element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
                }
            }
        val hasNextPage = document.selectFirst("ul.pagination li a[rel=\"next\"]") != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")!!.attr("abs:src")
            title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!.text()
            description = document.selectFirst("div.Description")!!.text().removeSurrounding("\"")
            genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
            status = parseStatus(document.select("span.fa-tv").text())
        }
    }

    private fun parseStatus(statusString: String): Int =
        when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?order=updated&page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes =
            document.select("div.Container ul.ListAnimes li article").map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.select("div.Description a.Button").attr("abs:href"))
                    title = element.select("a h3").text()
                    thumbnail_url = element.select("a div.Image figure img").attr("src").replace("/storage", "$baseUrl/storage")
                    description = element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
                }
            }
        val hasNextPage = document.selectFirst("ul.pagination li a[rel=\"next\"]") != null
        return AnimesPage(animes, hasNextPage)
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
}
