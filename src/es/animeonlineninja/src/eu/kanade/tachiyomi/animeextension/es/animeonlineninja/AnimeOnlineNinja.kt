package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.multisrc.dooplay.splitSeasons
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeOnlineNinja :
    DooPlay(
        "es",
        "AnimeOnline.Ninja",
        "https://ww3.animeonline.ninja",
    ) {
    override val client by lazy {
        if (preferences.getBoolean(PREF_VRF_INTERCEPT_KEY, PREF_VRF_INTERCEPT_DEFAULT)) {
            network.client
                .newBuilder()
                .addInterceptor(VrfInterceptor())
                .build()
        } else {
            network.client
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendencias/$page")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularAnimeParse(response: Response): AnimesPage {
        fetchGenresList()
        val document = response.asJsoup()
        val animes =
            document.select(popularAnimeSelector()).map { element ->
                popularAnimeFromElement(element)
            }
        val hasNextPage =
            popularAnimeNextPageSelector()?.let { selector ->
                document.selectFirst(selector) != null
            } ?: false

        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeFromElement(element: Element): SAnime =
        SAnime.create().apply {
            val img = element.selectFirst("img")!!
            val url = element.selectFirst("a")?.attr("href") ?: element.attr("href")

            setUrlWithoutDomain(url)
            title = img.attr("alt")
            thumbnail_url = img.getImageUrl()
            element.selectFirst("div.quality")?.let {
                description = it.text()
            }
            val isSeries = detectIsSeries(url)
            fetch_type = preferredFetchType(isSeries)
        }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime =
        SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val img = element.selectFirst("img")!!
            title = img.attr("alt")
            thumbnail_url = img.getImageUrl()
            val url = element.attr("href")
            val isSeries = detectIsSeries(url)
            fetch_type = preferredFetchType(isSeries)
        }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val params = AnimeOnlineNinjaFilters.getSearchParameters(filters)
        val path =
            when {
                params.genre.isNotBlank() -> {
                    if (params.genre in listOf("tendencias", "ratings")) {
                        "/" + params.genre
                    } else {
                        "/genero/${params.genre}"
                    }
                }

                params.language.isNotBlank() -> {
                    "/genero/${params.language}"
                }

                params.year.isNotBlank() -> {
                    "/release/${params.year}"
                }

                params.movie.isNotBlank() -> {
                    if (params.movie == "pelicula") {
                        "/pelicula"
                    } else {
                        "/genero/${params.movie}"
                    }
                }

                else -> {
                    buildString {
                        append(
                            when {
                                query.isNotBlank() -> "/?s=$query"
                                params.letter.isNotBlank() -> "/letra/${params.letter}/?"
                                else -> "/tendencias/?"
                            },
                        )

                        append(
                            if (contains("tendencias")) {
                                "&get=${when (params.type){
                                    "serie" -> "TV"
                                    "pelicula" -> "movies"
                                    else -> "todos"
                                }}"
                            } else {
                                "&tipo=${params.type}"
                            },
                        )

                        if (params.isInverted) append("&orden=asc")
                    }
                }
            }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path")
        } else if (path.startsWith("/letra") || path.startsWith("/tendencias")) {
            val before = path.substringBeforeLast("/")
            val after = path.substringAfterLast("/")
            GET("$baseUrl$before/page/$page/$after")
        } else {
            GET("$baseUrl$path/page/$page")
        }
    }

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage =
        if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client
                .newCall(GET("$baseUrl/$path", headers))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details =
            animeDetailsParse(response).apply {
                setUrlWithoutDomain(response.request.url.toString())
                initialized = true
            }

        return AnimesPage(listOf(details), false)
    }

    // ============================== Episodes ==============================
    override val episodeMovieText = "Película"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeFromElement(
        element: Element,
        seasonName: String,
    ): SEpisode =
        SEpisode.create().apply {
            val epNum =
                element
                    .selectFirst("div.numerando")!!
                    .text()
                    .trim()
                    .let(episodeNumberRegex::find)
                    ?.groupValues
                    ?.last() ?: "0"
            val href = element.selectFirst("a[href]")!!
            val episodeName = href.ownText()
            episode_number = epNum.toFloatOrNull() ?: 0F
            date_upload =
                element
                    .selectFirst(episodeDateSelector)
                    ?.text()
                    ?.toDate() ?: 0L
            name = "$episodeSeasonPrefix $seasonName x $epNum - $episodeName"
            setUrlWithoutDomain(href.attr("href"))

            // Extract episode image
            element.selectFirst("img")?.let { img ->
                preview_url = img.getImageUrl()
            }
        }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealAnimeDoc(response.asJsoup())
        val seasonList = doc.select(seasonListSelector)

        if (seasonList.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(doc.location())
                    episode_number = 1F
                    name = episodeMovieText
                },
            )
        }

        val selectedSeason =
            response.request.url
                .queryParameter("season")
                ?.toIntOrNull()
        val seasonsToUse =
            if (selectedSeason != null) {
                seasonList.filter { season ->
                    val seasonNum = season.selectFirst("span.se-t")?.text()?.toIntOrNull() ?: 0
                    seasonNum == selectedSeason
                }
            } else {
                seasonList
            }

        return seasonsToUse.reversed().flatMap { seasonElement ->
            getSeasonEpisodes(seasonElement).reversed()
        }
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        val doc = getRealAnimeDoc(response.asJsoup())
        val seasonList = doc.select(seasonListSelector)

        if (seasonList.isEmpty()) return emptyList()

        // Parse anime details directly from doc
        val sheader = doc.selectFirst("div.sheader")!!
        val animeTitle =
            sheader
                .selectFirst("div.poster > img")
                ?.attr("alt")
                ?.ifEmpty { sheader.selectFirst("div.data > h1")?.text() } ?: ""
        val animeThumbnail = sheader.selectFirst("div.poster > img")?.getImageUrl()
        val animeGenre = sheader.select("div.data > div.sgeneros > a").eachText().joinToString()
        val animeDescription =
            doc.selectFirst(additionalInfoSelector)?.let { info ->
                buildString {
                    append(doc.getDescription())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }

        val basePath =
            response.request.url
                .toString()
                .removePrefix(baseUrl)

        return seasonList.map { season ->
            val seasonNum = season.selectFirst("span.se-t")?.text()?.toIntOrNull() ?: 1
            val seasonName = "$animeTitle - Temporada $seasonNum"

            SAnime.create().apply {
                title = seasonName
                thumbnail_url = animeThumbnail
                description = animeDescription
                genre = animeGenre
                season_number = seasonNum.toDouble()
                fetch_type = FetchType.Episodes
                setUrlWithoutDomain(buildSeasonUrl(basePath, seasonNum))
            }
        }
    }

    private fun buildSeasonUrl(
        basePath: String,
        seasonNumber: Int,
    ): String {
        val normalizedPath =
            when {
                basePath.startsWith("http", true) -> basePath.removePrefix(baseUrl)
                basePath.startsWith("/") -> basePath
                basePath.isBlank() -> "/"
                else -> "/${basePath.trimStart('/')}"
            }
        val hasQuery = normalizedPath.contains('?')
        val separator = if (hasQuery) '&' else '?'
        return "$normalizedPath${separator}season=$seasonNumber"
    }

    // ============================ Video Links =============================
    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        val videoList =
            players.parallelCatchingFlatMapBlocking { player ->
                val name = player.selectFirst("span.title")!!.text()
                val url = getPlayerUrl(player)
                extractVideos(url, name)
            }

        val hosterMap =
            videoList.groupBy { video ->
                video.videoTitle.substringBefore(":").trim()
            }

        return hosterMap.map { (name, videos) ->
            Hoster(hosterName = name, videoList = videos)
        }
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }

    private fun extractVideos(
        url: String,
        lang: String,
    ): List<Video> {
        try {
            val matched =
                conventions
                    .firstOrNull { (_, names) ->
                        names.any {
                            it.lowercase() in url.lowercase() ||
                                it.lowercase() in lang.lowercase()
                        }
                    }?.first
            return when (matched) {
                "saidochesto", "multiserver" -> {
                    extractFromMulti(url)
                }

                "filemoon" -> {
                    filemoonExtractor.videosFromUrl(url, "$lang Filemoon:", headers)
                }

                "doodstream" -> {
                    doodExtractor.videosFromUrl(url, "$lang DoodStream", false)
                }

                "streamtape" -> {
                    streamTapeExtractor.videosFromUrl(url, "$lang StreamTape")
                }

                "mixdrop" -> {
                    mixDropExtractor.videoFromUrl(url, prefix = "$lang ")
                }

                "uqload" -> {
                    uqloadExtractor.videosFromUrl(url, prefix = lang)
                }

                "wolfstream" -> {
                    client
                        .newCall(GET(url, headers))
                        .execute()
                        .asJsoup()
                        .selectFirst("script:containsData(sources)")
                        ?.data()
                        ?.let { jsData ->
                            val videoUrl = jsData.substringAfter("{file:\"").substringBefore("\"")
                            listOf(
                                Video(
                                    videoTitle = "$lang WolfStream",
                                    videoUrl = videoUrl,
                                    headers = headers,
                                    subtitleTracks = emptyList(),
                                    audioTracks = emptyList(),
                                ),
                            )
                        }
                }

                "mp4upload" -> {
                    mp4uploadExtractor.videosFromUrl(url, headers, prefix = "$lang ")
                }

                "vidhide" -> {
                    vidHideExtractor.videosFromUrl(url) { "$lang VidHide:$it" }
                }

                "streamwish" -> {
                    streamWishExtractor.videosFromUrl(url, videoNameGen = { "$lang StreamWish:$it" })
                }

                else -> {
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private val conventions =
        listOf(
            "saidochesto" to listOf("saidochesto"),
            "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
            "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods.", "ds2play", "ds2video", "dooood", "d000d", "d0000d"),
            "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
            "mixdrop" to listOf("mixdrop"),
            "uqload" to listOf("uqload"),
            "wolfstream" to listOf("wolfstream"),
            "mp4upload" to listOf("mp4upload"),
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
        )

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    private fun extractFromMulti(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val prefLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val langSelector =
            when {
                prefLang.isBlank() -> "div"
                else -> "div.OD_$prefLang"
            }
        return document.select("div.ODDIV $langSelector > li").flatMap {
            val hosterUrl =
                it
                    .attr("onclick")
                    .toString()
                    .substringAfter("('")
                    .substringBefore("')")
            val lang =
                when (langSelector) {
                    "div" -> {
                        it
                            .parent()
                            ?.attr("class")
                            .toString()
                            .substringAfter("OD_", "")
                            .substringBefore(" ")
                    }

                    else -> {
                        prefLang
                    }
                }
            extractVideos(hosterUrl, lang)
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client
            .newCall(GET("$baseUrl/wp-json/dooplayer/v1/post/$id?type=$type&source=$num"))
            .execute()
            .let { response ->
                response.body
                    .string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val doc = getRealAnimeDoc(document)

        // Get the base anime info
        val sheader = doc.selectFirst("div.sheader")!!
        val anime =
            SAnime.create().apply {
                setUrlWithoutDomain(doc.location())
                sheader.selectFirst("div.poster > img")!!.let {
                    thumbnail_url = it.getImageUrl()
                    title =
                        it.attr("alt").ifEmpty {
                            sheader.selectFirst("div.data > h1")!!.text()
                        }
                }

                genre =
                    sheader
                        .select("div.data > div.sgeneros > a")
                        .eachText()
                        .joinToString()

                doc.selectFirst(additionalInfoSelector)?.let { info ->
                    description =
                        buildString {
                            append(doc.getDescription())
                            additionalInfoItems.forEach {
                                info.getInfo(it)?.let(::append)
                            }
                        }
                }
            }

        val seasonList = doc.select(seasonListSelector)
        val hasSeries = seasonList.isNotEmpty()

        val requestUrl = response.request.url
        val forcedEpisodeMode = isForcedEpisodeMode(requestUrl)
        val fetchType =
            when {
                forcedEpisodeMode -> FetchType.Episodes
                else -> preferredFetchType(hasSeries)
            }

        return anime.apply {
            fetch_type = fetchType
            if (forcedEpisodeMode) {
                url = requestUrl.toString().removePrefix(baseUrl)
            }
        }
    }

    override fun Document.getDescription(): String =
        select("$additionalInfoSelector div.wp-content p")
            .eachText()
            .joinToString("\n")

    override val additionalInfoItems = listOf("Título", "Temporadas", "Episodios", "Duración media")

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodio"

    override fun latestUpdatesNextPageSelector() = "div.pagination > *:last-child:not(span):not(.current)"

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = AnimeOnlineNinjaFilters.FILTER_LIST

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        ListPreference(screen.context)
            .apply {
                key = PREF_LANG_KEY
                title = PREF_LANG_TITLE
                entries = PREF_LANG_ENTRIES
                entryValues = PREF_LANG_VALUES
                setDefaultValue(PREF_LANG_DEFAULT)
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

        CheckBoxPreference(screen.context)
            .apply {
                key = PREF_VRF_INTERCEPT_KEY
                title = PREF_VRF_INTERCEPT_TITLE
                summary = PREF_VRF_INTERCEPT_SUMMARY
                setDefaultValue(PREF_VRF_INTERCEPT_DEFAULT)
            }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context)
            .apply {
                key = PREF_SPLIT_SEASONS_KEY
                title = "Split seasons"
                summary = "Mostrar temporadas como entradas separadas"
                setDefaultValue(true)
                isChecked = preferences.splitSeasons

                setOnPreferenceChangeListener { _, newValue ->
                    preferences.splitSeasons = newValue as Boolean
                    true
                }
            }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun String.toDate() = 0L

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(lang) },
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues
    override val episodeNumberRegex by lazy { Regex("""(\d+(?:\.\d+)?)$""") }

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Uqload"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "ES", "LAT")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "ES", "LAT")
        private val SERVER_LIST =
            arrayOf(
                "Filemoon",
                "DoodStream",
                "StreamTape",
                "MixDrop",
                "Uqload",
                "WolfStream",
                "saidochesto.top",
                "VidHide",
                "StreamWish",
                "Mp4Upload",
            )

        private const val PREF_VRF_INTERCEPT_KEY = "vrf_intercept"
        private const val PREF_VRF_INTERCEPT_TITLE = "Intercept VRF links (Requiere Reiniciar)"
        private const val PREF_VRF_INTERCEPT_SUMMARY = "Intercept VRF links and open them in the browser"
        private const val PREF_VRF_INTERCEPT_DEFAULT = false
    }
}
