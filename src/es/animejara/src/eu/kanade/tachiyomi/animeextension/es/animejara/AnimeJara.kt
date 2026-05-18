package eu.kanade.tachiyomi.animeextension.es.animejara

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.lib.mailruextractor.MailRuExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeJara :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "AnimeJara"
    override val baseUrl = "https://animejara.com"
    override val lang = "es"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient =
        network.client
            .newBuilder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 404 &&
                    response.request.url.encodedPath
                        .contains("/episode/")
                ) {
                    response.newBuilder().code(200).build()
                } else {
                    response
                }
            }.build()

    // ============================== Extractors ==============================

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mailRuExtractor by lazy { MailRuExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val videoProxy by lazy { M3u8Integration(client) }

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/catalogo?estado=Emision")

    override fun popularAnimeParse(response: Response): AnimesPage = catalogParse(response)

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/catalogo")

    override fun latestUpdatesParse(response: Response): AnimesPage = catalogParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val params = mutableListOf<String>()
        if (query.isNotBlank()) {
            params.add("q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        }
        filters.forEach { filter ->
            when (filter) {
                is AnimeJaraFilters.TypeFilter -> {
                    filter.toUriPart()?.let { params.add("tipo=$it") }
                }

                is AnimeJaraFilters.StatusFilter -> {
                    filter.toUriPart()?.let { params.add("estado=$it") }
                }

                is AnimeJaraFilters.LanguageFilter -> {
                    filter.toUriPart()?.let { params.add("idioma=$it") }
                }

                is AnimeJaraFilters.YearFilter -> {
                    filter.toUriPart()?.let { params.add("anio=$it") }
                }

                is AnimeJaraFilters.GenreFilter -> {
                    filter.toUriPart()?.let { params.add("tag=$it") }
                }

                else -> {}
            }
        }
        val queryStr = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return GET("$baseUrl/catalogo$queryStr")
    }

    override fun searchAnimeParse(response: Response): AnimesPage = catalogParse(response)

    // ============================== Catalog Parse ==============================

    private fun catalogParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes =
            document.select("a.anime-card").map { card ->
                SAnime.create().apply {
                    setUrlWithoutDomain(card.attr("href"))
                    title = card.selectFirst("h3.card-title")?.text()?.trim() ?: ""
                    thumbnail_url =
                        card.selectFirst("img.card-poster")?.let {
                            it.attr("data-src").ifBlank { it.attr("abs:src") }
                        }
                }
            }
        return AnimesPage(animes, hasNextPage = false)
    }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text()?.trim() ?: ""
            thumbnail_url = document.selectFirst("img.main-poster-img")?.attr("abs:src")
            status =
                when (
                    document
                        .selectFirst(".label-poster")
                        ?.text()
                        ?.trim()
                        ?.uppercase()
                ) {
                    "EMISION" -> SAnime.ONGOING
                    "FINALIZADO" -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }
            genre = document.select(".anime-categorias span").joinToString { it.text().trim() }
            description = document.selectFirst(".anime-sinopsis-contenedor div")?.text()?.trim()
        }
    }

    // ============================== Episode List ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val scriptData = document.select("script").joinToString("\n") { it.data() }

        val slug =
            SLUG_REGEX.find(scriptData)?.groupValues?.get(1)
                ?: return emptyList()

        val temporadasJson =
            TEMPORADAS_REGEX.find(scriptData)?.groupValues?.get(1)
                ?: return emptyList()

        val allTemporadas = json.decodeFromString<List<TemporadaDto>>(temporadasJson)
        var globalEpCounter = 0

        return allTemporadas
            .flatMap { temporada ->
                temporada.episodios.map { episodio ->
                    globalEpCounter++
                    val epNum = episodio.numeroEpisodio.toIntOrNull() ?: 0
                    SEpisode.create().apply {
                        setUrlWithoutDomain("/episode/$slug-${temporada.numeroTemporada}x$epNum/")
                        name = buildString {
                            append("T${temporada.numeroTemporada} - ")
                            append("Episodio $epNum")
                            if (episodio.nombreEpisodio.isNotBlank()) append(": ${episodio.nombreEpisodio}")
                        }
                        episode_number = globalEpCounter.toFloat()
                        date_upload = parseDate(episodio.fechaActualizacion)
                        scanlator = episodio.idiomas.joinToString(", ")
                    }
                }
            }.reversed()
    }

    // ============================== Video List ==============================

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val scriptData = document.select("script").joinToString("\n") { it.data() }

        val enlacesJson =
            ENLACES_REGEX.find(scriptData)?.groupValues?.get(1)
                ?: return emptyList()

        val enlaces = json.decodeFromString<List<String>>(enlacesJson)
        val langButtons = document.select(".boton-idioma")

        val videoList = mutableListOf<Video>()
        val embedHeaders =
            headers
                .newBuilder()
                .set("Referer", "$baseUrl/")
                .build()

        enlaces.forEachIndexed { index, enlace ->
            val langName =
                langButtons
                    .getOrNull(index)
                    ?.selectFirst(".lang-name")
                    ?.text()
                    ?.trim()
                    ?: "Servidor ${index + 1}"

            val embedUrl = enlace.replace("\\/", "/").trim()
            if (embedUrl.isBlank()) return@forEachIndexed

            try {
                val embedResponse = client.newCall(GET(embedUrl, embedHeaders)).execute()
                val embedDoc = embedResponse.asJsoup()

                embedDoc.select("#logo-list li[onclick*=playVideo]").forEach { li ->
                    val onclick = li.attr("onclick")
                    val serverUrl =
                        PLAY_VIDEO_REGEX
                            .find(onclick)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                            ?: return@forEach

                    val serverName = li.selectFirst(".nombre-server")?.text()?.trim() ?: "Unknown"
                    val hosterKey = "$langName: $serverName"

                    try {
                        val videos = kotlinx.coroutines.runBlocking {
                            extractVideos(serverUrl, hosterKey)
                        }
                        if (videos.isNotEmpty()) {
                            videoList.addAll(videos)
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        return videoList.sortVideos()
    }

    private suspend fun extractVideos(
        url: String,
        prefix: String,
    ): List<Video> {
        val resolvedUrl =
            if ("streamhj.top" in url && "go.php" in url) {
                android.net.Uri
                    .parse(url)
                    .getQueryParameter("v") ?: url
            } else {
                url
            }
        val lowerUrl = resolvedUrl.lowercase()
        return when {
            "filemoon" in lowerUrl ||
                "filemooon" in lowerUrl ||
                "f75s" in lowerUrl ||
                "bysekoze" in lowerUrl -> {
                filemoonExtractor.videosFromUrl(resolvedUrl, prefix = "$prefix - ")
            }

            "streamtape" in lowerUrl -> {
                streamTapeExtractor.videosFromUrl(resolvedUrl, quality = prefix)
            }

            "uqload" in lowerUrl -> {
                uqloadExtractor.videosFromUrl(resolvedUrl, prefix = "$prefix - ")
            }

            "mixdrop" in lowerUrl -> {
                mixDropExtractor.videosFromUrl(resolvedUrl, prefix = "$prefix - ")
            }

            "vidhide" in lowerUrl || "filelions" in lowerUrl -> {
                vidHideExtractor.videosFromUrl(resolvedUrl) { "$prefix - $it" }
            }

            "streamwish" in lowerUrl || "swish" in lowerUrl || "medixiru" in lowerUrl -> {
                streamWishExtractor.videosFromUrl(resolvedUrl) { "$prefix - $it" }
            }

            "mp4upload" in lowerUrl -> {
                mp4uploadExtractor.videosFromUrl(resolvedUrl, headers, prefix = "$prefix - ") { videoUrl: String, videoHeaders: Map<String, String> ->
                    videoProxy.createProxyUrl(videoUrl, videoHeaders)
                }
            }

            "ok.ru" in lowerUrl -> {
                okruExtractor.videosFromUrl(resolvedUrl, prefix = "$prefix - ")
            }

            "mail.ru" in lowerUrl -> {
                mailRuExtractor.videosFromUrl(resolvedUrl, prefix = "$prefix - ")
            }

            "yourupload" in lowerUrl -> {
                yourUploadExtractor.videoFromUrl(resolvedUrl, headers, prefix = "$prefix - ")
            }

            else -> {
                emptyList()
            }
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeJaraFilters.getFilterList()

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY_KEY
                title = "Calidad preferida"
                entries = QUALITY_VALUES
                entryValues = QUALITY_VALUES
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(key, newValue as String).commit()
                }
            }.also(screen::addPreference)

        ListPreference(screen.context)
            .apply {
                key = PREF_LANGUAGE_KEY
                title = "Idioma preferido"
                entries = LANGUAGE_VALUES
                entryValues = LANGUAGE_VALUES
                setDefaultValue(PREF_LANGUAGE_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(key, newValue as String).commit()
                }
            }.also(screen::addPreference)

        ListPreference(screen.context)
            .apply {
                key = PREF_SERVER_KEY
                title = "Servidor preferido"
                entries = SERVER_VALUES
                entryValues = SERVER_VALUES
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(key, newValue as String).commit()
                }
            }.also(screen::addPreference)
    }

    // ============================== Utilities ==============================

    private fun parseDate(dateStr: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(dateStr)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang, true).not() },
                { it.quality.contains(server, true).not() },
                { it.quality.contains(quality, true).not() },
            ),
        )
    }

    companion object {
        private val SLUG_REGEX = Regex("""ANIME_SLUG\s*=\s*['"]([^'"]+)""")
        private val TEMPORADAS_REGEX = Regex("""TEMPORADAS_DATA\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL)
        private val ENLACES_REGEX = Regex("""enlaces\s*=\s*(\[[^\]]+\])""")
        private val PLAY_VIDEO_REGEX = Regex("""playVideo\(["']\s*(https?://[^\s"']+)""")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_LANGUAGE_KEY = "pref_language"
        private const val PREF_LANGUAGE_DEFAULT = "LATINO"
        private val LANGUAGE_VALUES = arrayOf("LATINO", "JAPONES", "CASTELLANO")

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "filemoon"
        private val SERVER_VALUES =
            arrayOf(
                "filemoon",
                "streamtape",
                "uqload",
                "mixdrop",
                "vidhide",
                "streamwish",
                "mp4upload",
                "okru",
                "yourupload",
            )
    }
}
