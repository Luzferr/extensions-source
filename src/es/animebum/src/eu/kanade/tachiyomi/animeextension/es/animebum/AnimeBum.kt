package eu.kanade.tachiyomi.animeextension.es.animebum

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeBum :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "AnimeBum"

    override val baseUrl = "https://www.animebum.net"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes =
            document.select("article.serie").map { element ->
                SAnime.create().apply {
                    // Extraer el título y enlace
                    val titleElement = element.selectFirst("div.title h3 a")
                    title = titleElement?.attr("title") ?: "Sin título"
                    setUrlWithoutDomain(titleElement?.attr("href") ?: "")
                    // Extraer la imagen
                    val imageElement = element.selectFirst("figure.image img")
                    thumbnail_url = imageElement?.attr("src") ?: ""
                    fetch_type = FetchType.Episodes
                }
            }
        val hasNextPage = document.select("ul.pagination li a[rel=next]").firstOrNull() != null
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes =
            document.select("div.search-results__item").map { element ->
                SAnime.create().apply {
                    val titleElement = element.selectFirst("div.search-results__left a h2")
                    title = titleElement?.text().orEmpty()

                    val urlElement = element.selectFirst("div.search-results__left a")
                    setUrlWithoutDomain(urlElement?.attr("href").orEmpty())

                    val imgElement = element.selectFirst("div.search-results__img a img")
                    thumbnail_url = imgElement?.attr("src").orEmpty()

                    val descriptionElement = element.selectFirst("div.search-results__left div.description")
                    description = descriptionElement?.text().orEmpty()

                    fetch_type = FetchType.Episodes
                }
            }
        val hasNextPage = document.select("a.next.page-numbers").firstOrNull() != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        val synopsisElement = document.selectFirst("div.description p")
        anime.description = synopsisElement?.text() ?: "Sin sinopsis"

        val yearElement = document.selectFirst("p.datos-serie strong:contains(Año)")
        anime.genre = yearElement?.text() ?: ""
        // sie es fin o emison la clase
        val statusElement =
            if (document.selectFirst("p.datos-serie strong.emision") != null) {
                document.selectFirst("p.datos-serie strong.emision")
            } else {
                document.selectFirst("p.datos-serie strong.fin")
            }
        anime.status = parseStatus(statusElement?.text() ?: "")

        val genresElement = document.select("div.boom-categories a")
        anime.genre = genresElement.joinToString(", ") { it.text() }
        anime.fetch_type = FetchType.Episodes

        return anime
    }

    private fun parseStatus(status: String): Int =
        when (status) {
            "En emisión" -> SAnime.ONGOING
            "Finalizado" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document
            .select("ul.list-episodies li")
            .map { element ->
                SEpisode.create().apply {
                    val episodeUrl = element.selectFirst("a")?.attr("href").orEmpty()
                    val episodeTitle =
                        element
                            .selectFirst("a")
                            ?.ownText()
                            ?.trim()
                            .orEmpty()
                    val episodeNumber =
                        Regex("""Episodio (\d+)""")
                            .find(episodeTitle)
                            ?.groupValues
                            ?.get(1)
                            ?.toFloatOrNull()

                    setUrlWithoutDomain(episodeUrl)
                    name = episodeTitle
                    episode_number = episodeNumber ?: 1F
                }
            }.sortedByDescending { it.episode_number }
    }

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val scriptContent =
            document.select("script:containsData(var video = [])").firstOrNull()?.data()
                ?: return emptyList()

        val iframeRegex = """video\[\d+\]\s*=\s*['"]<iframe[^>]+src=["']([^"']+)["']""".toRegex()
        val matches = iframeRegex.findAll(scriptContent)

        for (match in matches) {
            var videoUrl = match.groupValues[1]

            if (videoUrl.startsWith("//")) {
                videoUrl = "https:$videoUrl"
            }

            val vidHideDomains = listOf("vidhide", "VidHidePro", "luluvdo", "vidhideplus")

            val video =
                when {
                    vidHideDomains.any { videoUrl.contains(it, ignoreCase = true) } -> {
                        vidHideExtractor.videosFromUrl(videoUrl)
                    }

                    "drive.google" in videoUrl -> {
                        val newUrl = "https://gdriveplayer.to/embed2.php?link=$videoUrl"
                        Log.d("AnimeBum", "New URL: $newUrl")
                        gdrivePlayerExtractor.videosFromUrl(newUrl, "GdrivePlayer", headers)
                    }

                    videoUrl.contains("streamwish") -> {
                        streamWishExtractor.videosFromUrl(videoUrl)
                    }

                    videoUrl.contains("ok.ru") -> {
                        okruExtractor.videosFromUrl(videoUrl)
                    }

                    videoUrl.contains("listeamed") -> {
                        vidGuardExtractor.videosFromUrl(videoUrl)
                    }

                    else -> {
                        emptyList()
                    }
                }
            videoList.addAll(video)
        }

        val sortedVideos = videoList.sortVideos()
        return listOf(
            Hoster(
                hosterName = "Multi",
                videoList = sortedVideos,
            ),
        )
    }

    // ============================ Video Extractor ==========================

    private val vidHideExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }

    // ============================ Filters =============================
    override fun getFilterList(): AnimeFilterList =
        AnimeFilterList(
            AnimeFilter.Header("La busqueda por texto ignora el filtro"),
            GenreFilter(),
        )

    private class GenreFilter :
        UriPartFilter(
            "Género",
            arrayOf(
                Pair("<Seleccionar>", ""),
                Pair("Acción", "genero/accion"),
                Pair("Aventura", "genero/aventura"),
                Pair("Ciencia Ficción", "genero/ciencia-ficcion"),
                Pair("Comedia", "genero/comedia"),
                Pair("Drama", "genero/drama"),
                Pair("Terror", "genero/terror"),
                Pair("Suspenso", "genero/suspenso"),
                Pair("Romance", "genero/romance"),
                Pair("Magia", "genero/magia"),
                Pair("Misterio", "genero/misterio"),
                Pair("Superpoderes", "genero/super-poderes"),
                Pair("Shounen", "genero/shounen"),
                Pair("Deportes", "genero/deportes"),
                Pair("Fantasía", "genero/fantasia"),
                Pair("Sobrenatural", "genero/sobrenatural"),
                Pair("Música", "genero/musica"),
                Pair("Escolares", "genero/escolares"),
                Pair("Seinen", "genero/seinen"),
                Pair("Histórico", "genero/historico"),
                Pair("Psicológico", "genero/psicologico"),
                Pair("Mecha", "genero/mecha"),
                Pair("Juegos", "genero/juegos"),
                Pair("Militar", "genero/militar"),
                Pair("Recuentos de la Vida", "genero/recuentos-de-la-vida"),
                Pair("Demonios", "genero/demonios"),
                Pair("Artes Marciales", "genero/artes-marciales"),
                Pair("Espacial", "genero/espacial"),
                Pair("Shoujo", "genero/shoujo"),
                Pair("Samurái", "genero/samurai"),
                Pair("Harem", "genero/harem"),
                Pair("Parodia", "genero/parodia"),
                Pair("Ecchi", "genero/ecchi"),
                Pair("Demencia", "genero/demencia"),
                Pair("Vampiros", "genero/vampiros"),
                Pair("Josei", "genero/josei"),
                Pair("Shounen Ai", "genero/shounen-ai"),
                Pair("Shoujo Ai", "genero/shoujo-ai"),
                Pair("Latino", "genero/latino"),
                Pair("Policía", "genero/policia"),
                Pair("Yaoi", "genero/yaoi"),
            ),
        )

    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================ Preferences =============================

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST =
            arrayOf(
                "YourUpload",
                "BurstCloud",
                "Voe",
                "Mp4Upload",
                "Doodstream",
                "Upload",
                "BurstCloud",
                "Upstream",
                "StreamTape",
                "Amazon",
                "Fastream",
                "Filemoon",
                "StreamWish",
                "Okru",
                "Streamlare",
            )
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
