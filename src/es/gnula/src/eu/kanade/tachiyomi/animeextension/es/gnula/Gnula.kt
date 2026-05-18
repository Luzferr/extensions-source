package eu.kanade.tachiyomi.animeextension.es.gnula

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.burstcloudextractor.BurstCloudExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.fastreamextractor.FastreamExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamlareextractor.StreamlareExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.upstreamextractor.UpstreamExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.catchingFlatMapBlocking
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Gnula :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Gnula"

    override val baseUrl = "https://gnula.life"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST =
            arrayOf(
                "Voe",
                "Filemoon",
                "StreamWish",
                "VidHide",
                "Doodstream",
                "StreamTape",
                "Netu",
                "Amazon",
                "BurstCloud",
                "Fastream",
                "Upstream",
                "StreamSilk",
                "Streamlare",
                "Okru",
                "Uqload",
                "Mp4Upload",
                "YourUpload",
                "VidGuard",
            )

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[CAST]", "[SUB]")

        const val PREF_SPLIT_SEASONS_KEY = "split_seasons"
        const val PREF_SPLIT_SEASONS_DEFAULT = true
        internal const val LEGACY_PREF_FETCH_TYPE_KEY = "preferred_fetch_type"
        internal const val LEGACY_FETCH_TYPE_SEASONS = "1"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }

        private val SERVER_DISPLAY_NAMES =
            mapOf(
                "voe" to "Voe",
                "okru" to "Okru",
                "filemoon" to "Filemoon",
                "amazon" to "Amazon",
                "uqload" to "Uqload",
                "mp4upload" to "Mp4Upload",
                "streamwish" to "StreamWish",
                "doodstream" to "Doodstream",
                "streamlare" to "Streamlare",
                "yourupload" to "YourUpload",
                "burstcloud" to "BurstCloud",
                "fastream" to "Fastream",
                "upstream" to "Upstream",
                "streamsilk" to "StreamSilk",
                "streamtape" to "StreamTape",
                "vidhide" to "VidHide",
                "vidguard" to "VidGuard",
                "netu" to "Netu",
            )
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/archives/movies/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val jsonString =
            document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data()
                ?: return AnimesPage(emptyList(), false)
        val root =
            runCatching { json.parseToJsonElement(jsonString).jsonObject }.getOrNull()
                ?: return AnimesPage(emptyList(), false)
        val pageProps = root.obj("props")?.obj("pageProps") ?: return AnimesPage(emptyList(), false)
        val results = pageProps.obj("results") ?: return AnimesPage(emptyList(), false)
        val dataEntries = results.array("data") ?: return AnimesPage(emptyList(), false)
        val hasNextPage =
            document.selectFirst("ul.pagination > li.page-item.active ~ li > a > span.visually-hidden")?.text()?.contains("Next") ?: false
        var type = results.string("__typename") ?: ""

        val animeList =
            dataEntries.mapNotNull { element ->
                val item = element.jsonObjectOrNull() ?: return@mapNotNull null
                val slug = item.obj("slug")?.string("name") ?: return@mapNotNull null
                val urlSlug = item.obj("url")?.string("slug").orEmpty()

                if (urlSlug.isNotBlank()) {
                    type =
                        when {
                            "series" in urlSlug -> "PaginatedSerie"
                            "movies" in urlSlug -> "PaginatedMovie"
                            else -> type
                        }
                }

                SAnime.create().apply {
                    title = item.obj("titles")?.string("name") ?: ""
                    thumbnail_url = item.obj("images")?.string("poster")?.replace("/original/", "/w200/")
                    val isSeries = type.equals("PaginatedSerie", true)
                    fetch_type = preferredFetchType(isSeries)
                    setUrlWithoutDomain(urlSolverByType(type, slug))
                }
            }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/archives/movies/releases/page/$page", headers)

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.url.toAbsoluteUrl(), headers)

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url.toAbsoluteUrl(), headers)

    override fun hosterListRequest(episode: SEpisode): Request = GET(episode.url.toAbsoluteUrl(), headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val pageProps = response.extractPageProps() ?: return emptyList()
        val meta = pageProps.toGnulaMeta() ?: return emptyList()

        if (meta.isMovie) {
            val episodeUrl = response.request.url.toString()
            return listOf(
                SEpisode.create().apply {
                    name = meta.title.ifBlank { "Película" }
                    episode_number = 1F
                    setUrlWithoutDomain(episodeUrl.removePrefix(baseUrl))
                },
            )
        } else {
            val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return emptyList()
            val jsonData = jsonString.parseTo<SeasonModel>().props.pageProps
            var episodeCounter = 1F
            jsonData.post.seasons
                .flatMap { season ->
                    season.episodes.map { ep ->
                        val episode = SEpisode.create().apply {
                            episode_number = episodeCounter++
                            name = "T${season.number} - E${ep.number} - ${ep.title}"
                            date_upload = ep.releaseDate?.let(DATE_FORMATTER::tryParse) ?: 0L
                            setUrlWithoutDomain("$baseUrl/series/${ep.slug.name}/seasons/${ep.slug.season}/episodes/${ep.slug.episode}")
                        }
                        episode
                    }
                }
        }.reversed()
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        if (anime.fetch_type != FetchType.Seasons) return emptyList()

        val request = GET(anime.url.toAbsoluteUrl(), headers)
        return client.newCall(request).execute().use { seasonListParse(it) }
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        val pageProps = response.extractPageProps() ?: return emptyList()
        val meta = pageProps.toGnulaMeta() ?: return emptyList()
        if (meta.isMovie) return emptyList()

        val basePath =
            response.request.url
                .toString()
                .removePrefix(baseUrl)

        return meta.seasons.map { season ->
            season.toSAnime(basePath, meta, baseUrl)
        }
    }

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return emptyList()

        val root = runCatching { json.parseToJsonElement(jsonString).jsonObject }.getOrNull() ?: return emptyList()
        val pageProps = root.obj("props")?.obj("pageProps") ?: return emptyList()

        val players =
            if (response.request.url
                    .toString()
                    .contains("/movies/")
            ) {
                pageProps.obj("post")?.obj("players")
            } else {
                pageProps.obj("episode")?.obj("players")
            } ?: return emptyList()

        val groupedHosters = linkedMapOf<Pair<String, String>, MutableList<Video>>()

        players.array("latino").collectHosters("[LAT]", groupedHosters)
        players.array("spanish").collectHosters("[CAST]", groupedHosters)
        players.array("english").collectHosters("[SUB]", groupedHosters)

        val preferredLang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT) ?: PREF_LANGUAGE_DEFAULT
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        val hosterGroups =
            groupedHosters.entries.mapIndexed { index, (key, videos) ->
                val (languageTag, serverDisplay) = key
                val sortedVideos = videos.sortVideos()
                HosterGroupMeta(
                    index = index,
                    languageTag = languageTag,
                    serverDisplay = serverDisplay,
                    hoster =
                        Hoster(
                            hosterName = "$languageTag $serverDisplay",
                            videoList = sortedVideos,
                        ),
                )
            }

        return hosterGroups
            .sortedWith(
                compareByDescending<HosterGroupMeta> { it.matchesLanguage(preferredLang) }
                    .thenByDescending { it.matchesServer(preferredServer) }
                    .thenBy { it.index },
            ).map { it.hoster }
    }

    private suspend fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("voe") -> VoeExtractor(client, headers).videosFromUrl(url, prefix)

            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> OkruExtractor(client).videosFromUrl(url, prefix)

            embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") -> {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "$prefix Filemoon:", headers = vidHeaders)
            }

            embedUrl.contains("uqload") -> UqloadExtractor(client).videosFromUrl(url, prefix = prefix)

            embedUrl.contains("mp4upload") -> Mp4uploadExtractor(client).videosFromUrl(url, headers, prefix = prefix)

            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") -> {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
            }

            embedUrl.contains("doodstream") || embedUrl.contains("dood.") || embedUrl.contains("ds2play") || embedUrl.contains("doods.") -> {
                val url2 = url.replace("https://doodstream.com/e/", "https://dood.to/e/")
                listOf(DoodExtractor(client).videosFromUrl(url2, quality = prefix, redirect = false)).flatten()
            }

            embedUrl.contains("streamlare") -> StreamlareExtractor(client).videosFromUrl(url, prefix = prefix)

            embedUrl.contains("yourupload") || embedUrl.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix)

            embedUrl.contains("burstcloud") || embedUrl.contains("burst") -> BurstCloudExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix)

            embedUrl.contains("fastream") -> FastreamExtractor(client, headers).videosFromUrl(url, prefix = "$prefix Fastream:")

            embedUrl.contains("upstream") -> UpstreamExtractor(client).videosFromUrl(url, prefix = prefix)

            embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape") -> listOf(StreamTapeExtractor(client).videoFromUrl(url, quality = "$prefix StreamTape")!!)

            embedUrl.contains("ahvsh") || embedUrl.contains("streamhide") || embedUrl.contains("guccihide") || embedUrl.contains("streamvid") -> VidHideExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$prefix StreamHideVid:$it" })

            else -> UniversalExtractor(client).videosFromUrl(url, headers, prefix = prefix)
        }
    }

    private val conventions =
        listOf(
            "voe" to
                listOf(
                    "voe",
                    "voesx",
                    "tubelessceliolymph",
                    "simpulumlamerop",
                    "urochsunloath",
                    "nathanfromsubject",
                    "yip.",
                    "metagnathtuggers",
                    "donaldlineelse",
                ),
            "okru" to listOf("ok.ru", "okru"),
            "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
            "amazon" to listOf("amazon", "amz"),
            "uqload" to listOf("uqload"),
            "mp4upload" to listOf("mp4upload"),
            "streamwish" to
                listOf(
                    "wishembed",
                    "streamwish",
                    "strwish",
                    "wish",
                    "Kswplayer",
                    "Swhoi",
                    "Multimovies",
                    "Uqloads",
                    "neko-stream",
                    "swdyu",
                    "iplayerhls",
                    "streamgg",
                ),
            "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
            "streamlare" to listOf("streamlare", "slmaxed"),
            "yourupload" to listOf("yourupload", "upload"),
            "burstcloud" to listOf("burstcloud", "burst"),
            "fastream" to listOf("fastream"),
            "upstream" to listOf("upstream"),
            "streamsilk" to listOf("streamsilk"),
            "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
            "vidhide" to
                listOf(
                    "ahvsh",
                    "streamhide",
                    "guccihide",
                    "streamvid",
                    "vidhide",
                    "kinoger",
                    "smoothpre",
                    "dhtpre",
                    "peytonepre",
                    "earnvids",
                    "ryderjet",
                ),
            "vidguard" to listOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "bembed"),
        )

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val preferredLang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        val qualityRegex = Regex("""(\d+)p""")

        fun Video.matchesLanguage() = if (videoTitle.contains(preferredLang)) 1 else 0

        fun Video.matchesServer() = if (videoTitle.contains(preferredServer, ignoreCase = true)) 1 else 0

        fun Video.matchesQuality() = if (videoTitle.contains(preferredQuality)) 1 else 0

        fun Video.displayResolution(): Int =
            resolution ?: qualityRegex
                .find(videoTitle)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() ?: 0

        return sortedWith(
            compareBy(
                { it.matchesLanguage() },
                { it.matchesServer() },
                { it.matchesQuality() },
                { it.displayResolution() },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?q=$query&p=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val pageProps = response.extractPageProps() ?: return SAnime.create()
        val meta = pageProps.toGnulaMeta() ?: return SAnime.create()
        val requestUrl = response.request.url
        val forcedEpisodeMode =
            requestUrl.queryParameter("season") != null ||
                requestUrl.encodedPath.contains("/episodes/")
        val fetchType =
            when {
                forcedEpisodeMode -> FetchType.Episodes
                else -> preferredFetchType(meta.seasons.isNotEmpty())
            }

        return SAnime.create().apply {
            title = meta.title
            thumbnail_url = meta.poster
            description = meta.overview
            meta.genres
                .takeIf { it.isNotEmpty() }
                ?.joinToString()
                ?.let { genre = it }
            meta.director?.takeIf { it.isNotBlank() }?.let { author = it }
            artist = meta.cast.firstOrNull().orEmpty()
            status = if (meta.isMovie) SAnime.COMPLETED else SAnime.UNKNOWN
            fetch_type = fetchType
            if (forcedEpisodeMode) {
                url = requestUrl.toString().removePrefix(baseUrl)
            }
        }
    }

    private fun List<Region>.toVideoList(lang: String): List<Video> = catchingFlatMapBlocking {
        client.newCall(GET(it.result)).awaitSuccess().useAsJsoup().select("script")
            .map { sc -> sc.data() }
            .firstOrNull { data -> data.contains("var url = '") }
            ?.let { data ->
                val url = data.substringAfter("var url = '").substringBefore("';")
                serverVideoResolver(url, lang)
            } ?: emptyList()
    }

    private fun extractVideosFromRegion(
        region: JsonObject,
        languageTag: String,
        serverSlug: String,
    ): List<Video> {
        val resultUrl = region.string("result").orEmpty()
        if (resultUrl.isBlank()) return emptyList()

        val extractedUrl = resolveEmbeddedUrl(resultUrl)
        if (extractedUrl.isBlank()) return emptyList()

        return serverVideoResolver(extractedUrl, languageTag, serverSlug)
    }

    private fun resolveEmbeddedUrl(resultUrl: String): String {
        var extractedUrl = ""
        runCatching {
            client.newCall(GET(resultUrl)).execute().use { callResponse ->
                callResponse.asJsoup().select("script").forEach { script ->
                    val data = script.data()
                    if (data.contains("var url = '")) {
                        extractedUrl = data.substringAfter("var url = '").substringBefore("';")
                    }
                }
            }
        }
        return extractedUrl
    }

    private data class HosterEntry(
        val languageTag: String,
        val serverSlug: String,
        val videos: List<Video>,
    )

    private class GenreFilter :
        UriPartFilter(
            "Géneros",
            arrayOf(
                Pair("<selecionar>", ""),
                Pair("Películas", "archives/movies/releases"),
                Pair("Series", "archives/series/releases"),
                Pair("Acción", "genres/accion"),
                Pair("Animación", "genres/animacion"),
                Pair("Crimen", "genres/crimen"),
                Pair("Fámilia", "genres/familia"),
                Pair("Misterio", "genres/misterio"),
                Pair("Suspenso", "genres/suspenso"),
                Pair("Aventura", "genres/aventura"),
                Pair("Ciencia Ficción", "genres/ciencia-ficcion"),
                Pair("Drama", "genres/drama"),
                Pair("Fantasía", "genres/fantasia"),
                Pair("Romance", "genres/romance"),
                Pair("Terror", "genres/terror"),
            ),
        )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private inline fun <reified T> String.parseTo(): T = json.decodeFromString<T>(this)

    private fun urlSolverByType(type: String, slug: String): String = when (type) {
        "PaginatedMovie", "PaginatedGenre" -> "$baseUrl/movies/$slug"
        "PaginatedSerie" -> "$baseUrl/series/$slug"
        else -> ""
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_LANGUAGE_KEY
                title = "Preferred language"
                entries = LANGUAGE_LIST
                entryValues = LANGUAGE_LIST
                setDefaultValue(PREF_LANGUAGE_DEFAULT)
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

        SwitchPreferenceCompat(screen.context)
            .apply {
                key = PREF_SPLIT_SEASONS_KEY
                title = "Split seasons"
                summary = "Mostrar temporadas como entradas separadas"
                setDefaultValue(PREF_SPLIT_SEASONS_DEFAULT)
                isChecked = preferences.splitSeasons

                setOnPreferenceChangeListener { _, newValue ->
                    preferences.splitSeasons = newValue as Boolean
                    true
                }
            }.also(screen::addPreference)
    }
}

private var SharedPreferences.splitSeasons: Boolean
    get() {
        if (contains(Gnula.PREF_SPLIT_SEASONS_KEY)) {
            return getBoolean(Gnula.PREF_SPLIT_SEASONS_KEY, true)
        }

        val legacy = getString(Gnula.LEGACY_PREF_FETCH_TYPE_KEY, null)
        val migrated = legacy.equals(Gnula.LEGACY_FETCH_TYPE_SEASONS, ignoreCase = true)

        if (legacy != null) {
            edit()
                .putBoolean(Gnula.PREF_SPLIT_SEASONS_KEY, migrated)
                .remove(Gnula.LEGACY_PREF_FETCH_TYPE_KEY)
                .apply()
        }

        return legacy?.let { migrated } ?: true
    }
    set(value) {
        edit()
            .putBoolean(Gnula.PREF_SPLIT_SEASONS_KEY, value)
            .remove(Gnula.LEGACY_PREF_FETCH_TYPE_KEY)
            .apply()
    }
