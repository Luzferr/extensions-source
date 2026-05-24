package eu.kanade.tachiyomi.animeextension.es.latinoyt

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.goodstramextractor.GoodStreamExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LatinoYT :
    AnimeStream(
        "es",
        "LatinoYT",
        "https://latinoyt.com",
    ) {

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val QUALITY_LIST = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf(
            "Filemoon",
            "Voe",
            "StreamWish",
            "GoodStream",
            "DoodStream",
            "StreamTape",
        )
    }

    override val animeListUrl = "$baseUrl/anime"
    override val fetchFilters = false

    // ============================ Extractors =============================
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val goodStreamExtractor by lazy { GoodStreamExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "#sidebar ul li"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val linkEl = element.selectFirst("a.series")!!
        setUrlWithoutDomain(linkEl.attr("abs:href"))
        title = element.selectFirst("h4 a.series")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: ""
        thumbnail_url = element.selectFirst("div.imgseries img")?.getImageUrl()
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.selectFirst("div.tt, div.ttl")?.text()?.trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: element.text().trim()
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
    }

    // ============================ Video Links =============================
    override fun getVideoList(url: String, name: String): List<Video> {
        var fixedUrl = url
        if (fixedUrl.contains("ytlinker.online")) {
            fixedUrl = fixedUrl.replace("ytlinker.online", "old.mytsumi.com")
        }

        val loweredUrl = fixedUrl.lowercase()
        if (loweredUrl.contains("animed23") || loweredUrl.contains("mytsumi") || loweredUrl.contains("ytlinker")) {
            if (loweredUrl.contains("server=multi") || loweredUrl.contains("/multiplayer/")) {
                return extractFromMytsumi(fixedUrl)
            }
            if (loweredUrl.contains("/players/") || loweredUrl.contains("options.php")) {
                return extractFromPlayers(fixedUrl, name)
            }
        }

        val lowercaseUrl = fixedUrl.lowercase()
        val lowercaseName = name.lowercase()
        return runCatching {
            when {
                lowercaseUrl.contains("filemoon") || lowercaseUrl.contains("fmoon") ||
                    lowercaseUrl.contains("bysesukior") ->
                    filemoonExtractor.videosFromUrl(fixedUrl)
                lowercaseUrl.contains("goodstream") || lowercaseName.contains("goodstream") ->
                    goodStreamExtractor.videosFromUrl(fixedUrl, "GoodStream")
                lowercaseUrl.contains("voe") || lowercaseName.contains("voe") ->
                    voeExtractor.videosFromUrl(fixedUrl)
                lowercaseUrl.contains("streamwish") || lowercaseUrl.contains("awish") ||
                    lowercaseUrl.contains("strw") || lowercaseUrl.contains("wishembed") ->
                    streamWishExtractor.videosFromUrl(fixedUrl)
                lowercaseUrl.contains("dood") || lowercaseName.contains("dood") ->
                    doodExtractor.videosFromUrl(fixedUrl)
                lowercaseUrl.contains("streamtape") || lowercaseUrl.contains("stape") ->
                    streamtapeExtractor.videosFromUrl(fixedUrl)
                lowercaseUrl.contains("ok.ru") || lowercaseUrl.contains("okru") ->
                    okruExtractor.videosFromUrl(fixedUrl)
                lowercaseUrl.contains("yourupload") -> {
                    val yourUploadHeaders = headers.newBuilder()
                        .set("Referer", "https://www.yourupload.com/")
                        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                        .build()
                    val videos = yourUploadExtractor.videoFromUrl(fixedUrl, yourUploadHeaders, "YourUpload", "YourUpload - ")
                    videos.map { it.copy(headers = yourUploadHeaders) }
                }
                else ->
                    universalExtractor.videosFromUrl(fixedUrl, headers)
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Extracts videos from mytsumi-style multi-server URLs.
     *
     * Supports two formats:
     * - Old mytsumi: `mytsumi.com/multiplayer/options.php?server=multi&value=<id>&bg=...`
     *   Returns a page with a direct `videoTabs` JSON.
     * - New animed23: `animed23.online/opciones/options.php?server=multi&value=<base64>&bg=...`
     *   Requires navigating through splash page -> player.php?data=... -> contenedor.php?id=...
     */
    private fun extractFromMytsumi(url: String): List<Video> {
        val urlObj = url.toHttpUrlOrNull() ?: return emptyList()
        val host = urlObj.host
        val contenedorHeaders = headers.newBuilder().set("Referer", "https://$host/").build()

        if (host.contains("animed23")) {
            val optionsHtml = runCatching { client.newCall(GET(url, contenedorHeaders)).execute().body.string() }.getOrElse { return emptyList() }
            val directMatch = VIDEO_TABS_REGEX.find(optionsHtml) ?: VIDEO_TABS_REGEX_NO_SEMICOLON.find(optionsHtml)
            if (directMatch != null) {
                val jsonArray = runCatching { JSONArray(directMatch.groupValues[1]) }.getOrNull()
                if (jsonArray != null) return processVideoTabs(jsonArray)
            }
            val playerUrl = PLAYER_URL_REGEX.find(optionsHtml)?.groupValues?.get(1) ?: return emptyList()
            val fixedPlayerUrl = when {
                playerUrl.startsWith("//") -> "https:$playerUrl"
                playerUrl.startsWith("/") -> "https://$host$playerUrl"
                else -> playerUrl
            }
            val playerHtml = runCatching { client.newCall(GET(fixedPlayerUrl, contenedorHeaders)).execute().body.string() }.getOrElse { return emptyList() }
            val containerIds = CONTAINER_ID_REGEX.findAll(playerHtml).map { it.groupValues[1] }.toList()
            val videos = mutableListOf<Video>()
            for (id in containerIds) {
                val cUrl = "https://$host/multiplayer/contenedor.php?id=$id"
                val cHtml = runCatching { client.newCall(GET(cUrl, contenedorHeaders)).execute().body.string() }.getOrElse { continue }
                if (cHtml.contains("Contenedor no encontrado")) continue
                val tabMatch = VIDEO_TABS_REGEX.find(cHtml) ?: VIDEO_TABS_REGEX_NO_SEMICOLON.find(cHtml)
                if (tabMatch != null) {
                    val jsonArray = runCatching { JSONArray(tabMatch.groupValues[1]) }.getOrNull()
                    if (jsonArray != null) videos.addAll(processVideoTabs(jsonArray))
                }
            }
            return videos
        }

        val valueParam = urlObj.queryParameter("value") ?: return emptyList()
        val contenedorHtml = runCatching { client.newCall(GET("https://$host/multiplayer/contenedor.php?id=$valueParam", contenedorHeaders)).execute().body.string() }.getOrElse { return emptyList() }
        val oldMatch = VIDEO_TABS_REGEX.find(contenedorHtml) ?: VIDEO_TABS_REGEX_NO_SEMICOLON.find(contenedorHtml)
        if (oldMatch != null) {
            val jsonArray = runCatching { JSONArray(oldMatch.groupValues[1]) }.getOrNull()
            if (jsonArray != null) return processVideoTabs(jsonArray)
        }
        return emptyList()
    }

    /**
     * Processes the JSON array of video tabs and extracts videos from each tab.
     */
    private fun processVideoTabs(jsonArray: JSONArray): List<Video> {
        val videos = mutableListOf<Video>()
        for (i in 0 until jsonArray.length()) {
            val tab = runCatching { jsonArray.getJSONObject(i) }.getOrNull() ?: continue
            val tabUrl = tab.optString("url", "")
            if (tabUrl.isEmpty()) continue
            val tabName = tab.optString("tab_name", "Server ${i + 1}")
            if (tabUrl.contains("mega.nz") || tabUrl.contains("short.icu")) continue
            val extracted = runCatching { getVideoList(tabUrl, tabName) }.getOrElse { emptyList() }
            videos.addAll(extracted)
        }
        return videos
    }

    /**
     * Extracts videos from a mytsumi players URL.
     */
    private fun extractFromPlayers(url: String, name: String): List<Video> {
        val host = url.toHttpUrlOrNull()?.host ?: "old.mytsumi.com"
        val contenedorHeaders = headers.newBuilder()
            .set("Referer", "https://$host/")
            .build()

        val doc = runCatching {
            client.newCall(GET(url, contenedorHeaders)).execute().asJsoup()
        }.getOrNull() ?: return emptyList()

        val resolvedUrl = doc.selectFirst("div.play a")?.attr("href")
            ?: doc.selectFirst("a[href*=http]")?.attr("href")
            ?: doc.selectFirst("a[href^=/]")?.attr("abs:href")
            ?: return emptyList()

        return getVideoList(resolvedUrl, name)
    }

    // ======================== Parsing Defensivo ========================
    override fun animeDetailsParse(document: Document): SAnime = runCatching {
        super.animeDetailsParse(document)
    }.getOrElse {
        SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst("h1.entry-title, h1, title")?.text() ?: "Anime"
            thumbnail_url = document.selectFirst("div.thumb > img, div.limage > img, img[itemprop=image]")?.getImageUrl()
            description = document.selectFirst(".entry-content[itemprop=description], .desc, .sinopsis, p")?.text()
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val eplisterElements = doc.select("div.eplister > ul > li > a")
        if (eplisterElements.isNotEmpty()) {
            val eps = eplisterElements.mapNotNull { element ->
                runCatching { episodeFromElement(element) }.getOrNull()
            }
            if (eps.isNotEmpty()) return eps
        }
        val bxclElements = doc.select("div.bxcl ul li a, ul.episodios li a, .list-group-item a")
        if (bxclElements.isNotEmpty()) {
            return bxclElements.mapIndexed { index, element ->
                SEpisode.create().apply {
                    setUrlWithoutDomain(element.attr("href"))
                    val text = element.text()
                    name = text.ifEmpty { "Episodio ${bxclElements.size - index}" }
                    episode_number = Regex("""\d+""").find(text)?.value?.toFloatOrNull()
                        ?: (bxclElements.size - index).toFloat()
                }
            }
        }
        return runCatching { super.episodeListParse(response) }.getOrElse { emptyList() }
    }

    // ============================== Preferences ===============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(server, true) },
                { it.videoTitle.contains(quality) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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

        ListPreference(screen.context).apply {
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

// ============================== Regex ===============================
// Regex to extract the player.php iframe URL from the splash page JavaScript
private val PLAYER_URL_REGEX = Regex(
    """iframe\.src\s*=\s*['"]([^'"]+)['"]""",
)

// Regex to extract container IDs from onclick="play('id')"
private val CONTAINER_ID_REGEX = Regex(
    """play\s*\(\s*['"]([^'"]+)['"]\s*\)""",
)

// DOTALL flag with pattern that allows optional semicolon
private val VIDEO_TABS_REGEX = Regex(
    """const\s+videoTabs\s*=\s*(\[.*?\]);""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)

// Fallback regex without semicolon requirement
private val VIDEO_TABS_REGEX_NO_SEMICOLON = Regex(
    """const\s+videoTabs\s*=\s*(\[[\s\S]*?\])""",
)
