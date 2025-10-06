package eu.kanade.tachiyomi.animeextension.es.gnula

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamsilkextractor.StreamSilkExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

class Gnula :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "Gnula"

    override val baseUrl = "https://gnula.life"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

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

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
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
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(jsonString).jsonObject }.getOrNull() ?: return emptyList()
        val pageProps = root.obj("props")?.obj("pageProps") ?: return emptyList()

        return if (response.request.url
                .toString()
                .contains("/movies/")
        ) {
            pageProps.obj("post") ?: return emptyList()
            listOf(
                SEpisode.create().apply {
                    name = "Película"
                    episode_number = 1F
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        } else {
            val post = pageProps.obj("post") ?: return emptyList()
            val seasons = post.array("seasons") ?: return emptyList()
            var episodeCounter = 1F
            seasons
                .flatMap { seasonElement ->
                    val seasonObj = seasonElement.jsonObjectOrNull() ?: return@flatMap emptyList()
                    val seasonNumber = seasonObj.long("number")?.toInt() ?: 0
                    val episodes = seasonObj.array("episodes") ?: return@flatMap emptyList()

                    episodes.mapNotNull { episodeElement ->
                        val episodeObj = episodeElement.jsonObjectOrNull() ?: return@mapNotNull null
                        val slug = episodeObj.obj("slug") ?: return@mapNotNull null
                        val slugName = slug.string("name") ?: return@mapNotNull null
                        val slugSeason = slug.string("season") ?: return@mapNotNull null
                        val slugEpisode = slug.string("episode") ?: return@mapNotNull null
                        val episodeNumber = episodeObj.long("number")?.toInt() ?: 0
                        val title = episodeObj.string("title") ?: ""
                        val overview = episodeObj.string("overview") ?: episodeObj.string("description")
                        val preview = episodeObj.string("image")?.optimizeImageUrl()

                        SEpisode.create().apply {
                            episode_number = episodeCounter++
                            name = "T$seasonNumber - E$episodeNumber - $title"
                            date_upload = episodeObj.string("releaseDate")?.toDate() ?: 0L
                            summary = overview
                            preview_url = preview
                            setUrlWithoutDomain("$baseUrl/series/$slugName/seasons/$slugSeason/episodes/$slugEpisode")
                        }
                    }
                }
        }.reversed()
    }

    override suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

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

    // --------------------------------Video extractors------------------------------------
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }
    private val fastreamExtractor by lazy { FastreamExtractor(client, headers) }
    private val upstreamExtractor by lazy { UpstreamExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val streamSilkExtractor by lazy { StreamSilkExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    fun serverVideoResolver(
        url: String,
        prefix: String = "",
        serverName: String? = "",
    ): List<Video> {
        return runCatching {
            val source = serverName?.ifEmpty { url } ?: url
            val matched = canonicalServerSlug(source)
            val displayServer = displayServerName(matched)
            val prefixBase = buildPrefix(prefix, displayServer)
            val prefixWithSpace = prefixBase.withTrailingSpace()
            when (matched) {
                "voe" -> {
                    voeExtractor.videosFromUrl(url, prefixWithSpace)
                }

                "okru" -> {
                    okruExtractor.videosFromUrl(url, prefixWithSpace)
                }

                "filemoon" -> {
                    filemoonExtractor.videosFromUrl(url, prefix = prefixWithSpace)
                }

                "amazon" -> {
                    val body = client.newCall(GET(url)).execute().asJsoup()
                    return if (body.select("script:containsData(var shareId)").toString().isNotBlank()) {
                        val shareId =
                            body
                                .selectFirst("script:containsData(var shareId)")!!
                                .data()
                                .substringAfter("shareId = \"")
                                .substringBefore("\"")
                        val amazonApiJson =
                            client
                                .newCall(
                                    GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"),
                                ).execute()
                                .asJsoup()
                        val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
                        val amazonApi =
                            client
                                .newCall(
                                    GET(
                                        "https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId",
                                    ),
                                ).execute()
                                .asJsoup()
                        val videoUrl =
                            amazonApi
                                .toString()
                                .substringAfter(
                                    "\"FOLDER\":",
                                ).substringAfter("tempLink\":\"")
                                .substringBefore("\"")
                        listOf(Video(videoUrl = videoUrl, videoTitle = buildVideoName(prefixBase, "Amazon")))
                    } else {
                        emptyList()
                    }
                }

                "uqload" -> {
                    uqloadExtractor.videosFromUrl(url, prefixWithSpace)
                }

                "mp4upload" -> {
                    mp4uploadExtractor.videosFromUrl(url, headers, prefix = prefixWithSpace)
                }

                "streamwish" -> {
                    streamWishExtractor.videosFromUrl(url, videoNameGen = { quality -> buildVideoName(prefixBase, quality) })
                }

                "doodstream" -> {
                    doodExtractor.videosFromUrl(url, prefixWithSpace)
                }

                "streamlare" -> {
                    streamlareExtractor.videosFromUrl(url, prefixWithSpace)
                }

                "yourupload" -> {
                    yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = prefixWithSpace)
                }

                "burstcloud" -> {
                    burstCloudExtractor.videoFromUrl(url, headers = headers, prefix = prefixWithSpace)
                }

                "fastream" -> {
                    fastreamExtractor.videosFromUrl(url, prefix = prefixWithSpace)
                }

                "upstream" -> {
                    upstreamExtractor.videosFromUrl(url, prefix = prefixWithSpace)
                }

                "streamsilk" -> {
                    streamSilkExtractor.videosFromUrl(url, videoNameGen = { quality -> buildVideoName(prefixBase, quality) })
                }

                "streamtape" -> {
                    streamTapeExtractor.videosFromUrl(url, quality = prefixBase)
                }

                "vidhide" -> {
                    vidHideExtractor.videosFromUrl(url, videoNameGen = { quality -> buildVideoName(prefixBase, quality) })
                }

                "vidguard" -> {
                    vidGuardExtractor.videosFromUrl(url, prefix = prefixWithSpace)
                }

                else -> {
                    universalExtractor.videosFromUrl(url, headers, prefix = prefixWithSpace)
                }
            }
        }.getOrNull() ?: emptyList()
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
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")?.data() ?: return SAnime.create()
        val root = runCatching { json.parseToJsonElement(jsonString).jsonObject }.getOrNull() ?: return SAnime.create()
        val pageProps = root.obj("props")?.obj("pageProps") ?: return SAnime.create()
        val post = pageProps.obj("post") ?: return SAnime.create()
        return SAnime.create().apply {
            title = post.obj("titles")?.string("name") ?: ""
            thumbnail_url = post.obj("images")?.string("poster")
            description = post.string("overview")
            genre =
                post
                    .array("genres")
                    ?.mapNotNull { it.jsonObjectOrNull()?.string("name")?.takeIf { name -> name.isNotBlank() } }
                    ?.joinToString()
            artist =
                post
                    .obj("cast")
                    ?.array("acting")
                    ?.firstNotNullOfOrNull {
                        it.jsonObjectOrNull()?.string("name")?.takeIf { name -> name.isNotBlank() }
                    }
                    ?: ""
            status = if (root.string("page")?.contains("movie", true) == true) SAnime.COMPLETED else SAnime.UNKNOWN
        }
    }

    private fun JsonArray?.collectHosters(
        languageTag: String,
        hosterGroups: LinkedHashMap<Pair<String, String>, MutableList<Video>>,
    ) {
        val regions = this?.mapNotNull { it.jsonObjectOrNull() } ?: return

        val entries =
            regions.parallelCatchingFlatMapBlocking { region ->
                val serverSlug = region.string("cyberlocker").orEmpty()
                val videos = extractVideosFromRegion(region, languageTag, serverSlug)
                if (videos.isEmpty()) {
                    emptyList()
                } else {
                    listOf(HosterEntry(languageTag, serverSlug, videos))
                }
            }

        entries.forEach { entry ->
            val displayName = displayServerName(entry.serverSlug)
            val key = entry.languageTag to displayName
            val group = hosterGroups.getOrPut(key) { mutableListOf() }
            group.addAll(entry.videos)
        }
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

    private data class HosterGroupMeta(
        val index: Int,
        val languageTag: String,
        val serverDisplay: String,
        val hoster: Hoster,
    ) {
        fun matchesLanguage(preferred: String): Int = if (languageTag == preferred) 1 else 0

        fun matchesServer(preferred: String): Int = if (serverDisplay.equals(preferred, ignoreCase = true)) 1 else 0
    }

    private fun displayServerName(serverSlug: String): String {
        val canonical = canonicalServerSlug(serverSlug)
        if (canonical.isBlank()) return "Unknown"

        return SERVER_DISPLAY_NAMES[canonical] ?: canonical.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }

    private fun canonicalServerSlug(serverSlug: String): String {
        val lower = serverSlug.lowercase()
        return conventions
            .firstOrNull { (key, names) ->
                key.equals(lower, true) ||
                    lower.contains(key, ignoreCase = true) ||
                    names.any { name ->
                        name.equals(lower, true) || lower.contains(name, true)
                    }
            }?.first ?: lower
    }

    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList(
            AnimeFilter.Header("La busqueda por texto ignora el filtro"),
            GenreFilter(),
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

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun String.toAbsoluteUrl(): String =
        if (startsWith("http", true)) {
            this
        } else {
            val separator = if (startsWith("/")) "" else "/"
            "$baseUrl$separator$this"
        }

    private fun String.toDate(): Long = runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L

    private fun urlSolverByType(
        type: String,
        slug: String,
    ): String =
        when (type) {
            "PaginatedMovie", "PaginatedGenre" -> "$baseUrl/movies/$slug"
            "PaginatedSerie" -> "$baseUrl/series/$slug"
            else -> ""
        }

    private fun JsonObject.string(key: String): String? = get(key).stringValue()

    private fun JsonObject.long(key: String): Long? = get(key).stringValue()?.toLongOrNull()

    private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun String.optimizeImageUrl(): String =
        if (contains("/original/", ignoreCase = true)) {
            replace("/original/", "/w400/")
        } else {
            this
        }

    private fun buildPrefix(
        languageTag: String,
        serverName: String,
    ): String =
        sequenceOf(languageTag, serverName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun String.withTrailingSpace(): String = if (isBlank()) "" else "$this "

    private fun buildVideoName(
        prefix: String,
        detail: String,
    ): String =
        when {
            prefix.isBlank() -> detail.trim()
            detail.isBlank() -> prefix.trim()
            else -> "$prefix ${detail.trim()}"
        }

    private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

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
    }
}
