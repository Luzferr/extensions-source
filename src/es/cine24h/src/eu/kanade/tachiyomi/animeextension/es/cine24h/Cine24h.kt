package eu.kanade.tachiyomi.animeextension.es.cine24h

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class Cine24h : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Cine24h"

    override val baseUrl = "https://cine24h.online"

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
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf("Voe", "Fastream", "Filemoon", "Doodstream", "VidGuard")

        private const val FLAG_LAT = "\uD83C\uDDF2\uD83C\uDDFD "
        private const val FLAG_ES = "\uD83C\uDDEA\uD83C\uDDF8 "
        private const val FLAG_US = "\uD83C\uDDFA\uD83C\uDDF8 "
    }

    private val episodeRegex = Regex("(\\d+)[xX](\\d+)")

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            description = document.selectFirst(".Single .Description")?.text()
            genre = document.select(".Single .InfoList a").joinToString { it.text() }
            thumbnail_url = document.selectFirst(".Single .Image img")?.getImageUrl()?.replace("/w185/", "/w500/")
            if (document.location().contains("/movie/")) {
                status = SAnime.COMPLETED
            } else {
                val statusText = document.select(".InfoList .AAIco-adjust").map { it.text() }
                    .find { "En Producción:" in it }?.substringAfter("En Producción:")?.trim()
                status = when (statusText) { "Sí" -> SAnime.ONGOING else -> SAnime.COMPLETED }
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/peliculas/" else "$baseUrl/peliculas/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return response.parseListing(includeSeries = false)
    }

    override fun latestUpdatesParse(response: Response) = response.parseListing(includeSeries = true)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/estrenos/" else "$baseUrl/estrenos/page/$page/"
        return GET(url, headers)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query", headers)
            genreFilter.state != 0 -> {
                val base = genreFilter.toUriPart().trim('/')
                val path = if (page == 1) "$base/" else "$base/page/$page/"
                GET("$baseUrl/$path", headers)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = response.parseListing(includeSeries = true)

    private fun Response.parseListing(includeSeries: Boolean): AnimesPage {
        val document = asJsoup()
        val cards = document.collectCatalogAnchors(includeSeries)
        val animeList = cards.map { it.toSAnime() }
        val hasNext = document.hasNextPage()
        return AnimesPage(animeList, hasNext)
    }

    private fun Document.collectCatalogAnchors(includeSeries: Boolean): List<Element> {
        val selectors = mutableListOf(
            "a[href*=\"/peliculas/\"]:has(img[src*=\"image.tmdb.org\"])",
        )
        if (includeSeries) selectors += "a[href*=\"/series/\"]:has(img[src*=\"image.tmdb.org\"])"

        val anchors = selectors.flatMap { select(it) }
            .distinctBy { it.attr("abs:href") }

        if (anchors.isNotEmpty()) return anchors

        return select(".TPost a:not([target])").distinctBy { it.attr("abs:href") }
    }

    private fun Document.hasNextPage(): Boolean {
        val selectors = listOf(
            "a.page-numbers.next",
            "a.next",
            "a[rel=next]",
            "a:matches((?i)Siguiente)",
        )
        return selectors.any { select(it).isNotEmpty() }
    }

    private fun Element.toSAnime(): SAnime {
        val link = attr("abs:href")
        val titleItem = extractTitle()
        val iconHints = select("img[src*=\"idiomas\"]")
            .flatMap { img -> listOf(img.attr("alt"), img.attr("title"), img.attr("src")) }
            .joinToString(" ") { it.lowercase() }

        val textHints = ("$titleItem $link").lowercase()

        val prefix = when {
            iconHints.contains("lat") || textHints.contains("(lat") || textHints.contains("-lat") -> FLAG_LAT
            iconHints.contains("esp") || iconHints.contains("cast") || textHints.contains("(es") || textHints.contains("-es") -> FLAG_ES
            iconHints.contains("sub") || iconHints.contains("ing") || iconHints.contains("eng") || textHints.contains("(sub") -> FLAG_US
            else -> ""
        }

        val poster = selectFirst("img[src*=\"image.tmdb.org\"]") ?: selectFirst("img")

        return SAnime.create().apply {
            title = (prefix + titleItem).trim()
            thumbnail_url = poster?.getImageUrl()?.replace("/w185/", "/w342/")
            setUrlWithoutDomain(link)
        }
    }

    private fun Element.extractTitle(): String {
        val fullText = text()
        val candidates = sequenceOf(
            selectFirst("img[src*=\"image.tmdb.org\"]")?.attr("alt"),
            selectFirst(".Title")?.text(),
            selectFirst(".Title a")?.text(),
            selectFirst("h2, h3, h4")?.text(),
            fullText.substringBefore("###", fullText).trim(),
        ).map { it?.trim().orEmpty() }

        val title = candidates.firstOrNull { it.isNotBlank() }
        if (!title.isNullOrBlank()) return title

        val slug = attr("href").substringAfterLast('/').substringBeforeLast('.').replace('-', ' ')
        return slug.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return if (document.location().contains("/movie/")) {
            listOf(
                SEpisode.create().apply {
                    episode_number = 1f
                    name = "PELÍCULA"
                    scanlator = document.select(".AAIco-date_range").text().trim()
                    setUrlWithoutDomain(document.location())
                },
            )
        } else {
            parseSeriesEpisodes(document)
        }
    }

    private fun parseSeriesEpisodes(document: Document): List<SEpisode> {
        val episodeAnchors = document.select("a[href*=\"/episode/\"]")
            .toList()
            .filter { it.text().isNotBlank() }
            .distinctBy { it.attr("abs:href") }

        val episodes = episodeAnchors.mapIndexed { index, anchor ->
            val href = anchor.attr("abs:href")
            val rawName = anchor.text().trim()

            val match = episodeRegex.find(href) ?: episodeRegex.find(rawName)
            val season = match?.groupValues?.getOrNull(1)
            val episodeNumber = match?.groupValues?.getOrNull(2)

            val baseName = match
                ?.let { rawName.replace(it.value, "").trim(' ', '-', ':') }
                ?.takeIf { it.isNotBlank() }
                ?: rawName

            val formattedName = if (season != null && episodeNumber != null) {
                "T$season - E$episodeNumber" + if (baseName.isNotBlank()) " - $baseName" else ""
            } else {
                baseName.ifBlank { "Episodio ${index + 1}" }
            }

            val number = match?.let {
                val seasonValue = it.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
                val episodeValue = it.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                (seasonValue * 100 + episodeValue).toFloat()
            } ?: (index + 1).toFloat()

            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                name = formattedName
                episode_number = number
            }
        }

        return episodes.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()

        val playerOptions = document.select("ul#playeroptionsul li, ul.optnslst li[data-option], ul.optnslst li[data-src]")
        if (playerOptions.isNotEmpty()) {
            val uniqueOptions = playerOptions
                .mapNotNull { option ->
                    val url = option.extractPlayerUrl(referer)
                    if (url.isBlank()) null else url
                }
                .distinct()

            if (uniqueOptions.isNotEmpty()) {
                return uniqueOptions.parallelCatchingFlatMapBlocking(::serverVideoResolver)
            }
        }

        return document.select(".TPlayerTb").parallelCatchingFlatMapBlocking { container ->
            val html = org.jsoup.nodes.Entities.unescape(container.html())
            val primaryCandidates = extractEmbedCandidates(html)
            if (primaryCandidates.isNotEmpty()) {
                return@parallelCatchingFlatMapBlocking primaryCandidates.flatMap(::serverVideoResolver)
            }

            val fallbackUrl = Jsoup.parse(html).selectFirst("iframe")?.let { iframe ->
                iframe.absUrl("src").ifBlank { iframe.attr("src") }
            }?.replace("&#038;", "&") ?: ""

            if (fallbackUrl.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val embedHeaders = headers.newBuilder()
                .add("Referer", referer)
                .add("Origin", baseUrl)
                .build()

            val embedBody = runCatching {
                client.newCall(GET(fallbackUrl, embedHeaders)).execute().use { response ->
                    if (!response.isSuccessful) "" else response.body?.string().orEmpty()
                }
            }.getOrDefault("")

            if (embedBody.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val embeddedCandidates = extractEmbedCandidates(embedBody)
            embeddedCandidates.flatMap(::serverVideoResolver)
        }
    }

    private fun Element.extractPlayerUrl(referer: String): String {
        val directCandidate = sequenceOf<String?>(
            attr("data-option"),
            attr("data-player"),
            attr("data-src"),
            attr("data-video"),
            attr("data-url"),
            attr("data-link"),
            attr("href"),
            selectFirst("a[href]")?.attr("href"),
        ).firstOrNull { !it.isNullOrBlank() }

        directCandidate?.let {
            return normalizePlayerUrl(org.jsoup.nodes.Entities.unescape(it))
        }

        val post = attr("data-post")
        val nume = attr("data-nume")
        val type = attr("data-type")

        if (post.isBlank() || nume.isBlank()) return ""

        val ajaxHeaders = headers.newBuilder()
            .add("Referer", referer)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Origin", baseUrl)
            .build()

        val formBody = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", post)
            .add("nume", nume)
            .add("type", type.ifBlank { "movie" })
            .build()

        val ajaxTypeQuery = type.ifBlank { "movie" }
        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?action=doo_player_ajax&post=$post&nume=$nume&type=$ajaxTypeQuery"

        val primaryBody = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, formBody)).execute().use { it.body?.string().orEmpty() }
        val body = if (primaryBody.isNotBlank() && !primaryBody.contains("error", ignoreCase = true)) {
            primaryBody
        } else {
            client.newCall(GET(ajaxUrl, ajaxHeaders)).execute().use { it.body?.string().orEmpty() }
        }
        if (body.isBlank()) return ""

        val embedFromJson = embedUrlRegex.find(body)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let(org.jsoup.nodes.Entities::unescape)
            ?.takeIf { it.isNotBlank() }
        if (embedFromJson != null) return normalizePlayerUrl(embedFromJson)

        val ajaxDocument = Jsoup.parse(body)
        val iframe = ajaxDocument.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) return normalizePlayerUrl(org.jsoup.nodes.Entities.unescape(iframe))

        val source = ajaxDocument.selectFirst("source")?.attr("src")
        if (!source.isNullOrBlank()) return normalizePlayerUrl(org.jsoup.nodes.Entities.unescape(source))

        return ""
    }

    private fun normalizePlayerUrl(url: String): String {
        if (url.isBlank()) return ""

        val decoded = decodeIfBase64(url)
        val cleaned = org.jsoup.nodes.Entities.unescape(decoded).trim()
        if (cleaned.isBlank()) return ""

        val resolved = when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$baseUrl$cleaned"
            else -> cleaned
        }

        return resolved.replace("&amp;", "&")
    }

    private fun extractEmbedCandidates(html: String): Set<String> {
        if (html.isBlank()) return emptySet()

        val doc = Jsoup.parse(html)
        val candidates = mutableSetOf<String>()

        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            .map { element ->
                sequenceOf("src", "data-src", "data-lazy-src")
                    .map(element::attr)
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }
            .map(::normalizePlayerUrl)
            .filter { it.isNotBlank() && isSupportedHost(it) }
            .forEach(candidates::add)

        doc.select("source[src], video[src]")
            .map { it.attr("abs:src").ifBlank { it.attr("src") } }
            .map(::normalizePlayerUrl)
            .filter { it.isNotBlank() && isSupportedHost(it) }
            .forEach(candidates::add)

        val dataAttributes = listOf("data-src", "data-url", "data-video", "data-link", "data-player")
        dataAttributes.forEach { attr ->
            doc.select("[$attr]")
                .map { it.attr(attr) }
                .map(::normalizePlayerUrl)
                .filter { it.isNotBlank() && isSupportedHost(it) }
                .forEach(candidates::add)
        }

        val text = doc.outerHtml()
        urlRegex.findAll(text)
            .map { it.value }
            .map(::normalizePlayerUrl)
            .filter { it.isNotBlank() && isSupportedHost(it) }
            .forEach(candidates::add)

        return candidates
    }

    private val embedUrlRegex = Regex("\\\"embed_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val urlRegex = Regex("https?://[^\\s'\"]+")
    private val hostHints = listOf(
        "voe",
        "fastream",
        "filemoon",
        "dood",
        "doods",
        "ds2play",
        "listeamed",
        "guard",
        "vembed",
        "bembed",
        "vgfplay",
        "moonplayer",
        "moonwatch",
        "streamwish",
        "uqload",
        "streamtape",
        "okru",
        "mixdrop",
        "sbembed",
    )
    private val base64Regex = Regex("^[A-Za-z0-9+/=_-]+$")

    private fun isSupportedHost(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.isBlank()) return false
        if (lower.contains("trembed")) return true
        if (lower.contains("/player")) return true
        return hostHints.any { lower.contains(it) }
    }

    private fun decodeIfBase64(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        if (!trimmed.matches(base64Regex)) return trimmed

        val sanitized = trimmed
            .replace('-', '+')
            .replace('_', '/')

        val padded = if (sanitized.length % 4 == 0) sanitized else sanitized + "=".repeat(4 - (sanitized.length % 4))

        return runCatching {
            val decoded = Base64.decode(padded, Base64.DEFAULT)
            val result = decoded.decodeToString().trim()
            if (result.startsWith("http") || result.startsWith("//") || result.startsWith("/")) result else trimmed
        }.getOrElse { trimmed }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }

    private fun serverVideoResolver(url: String, depth: Int = 0): List<Video> {
        if (depth >= 3) return emptyList()

        val resolvedUrl = normalizePlayerUrl(url)

        if (!isSupportedHost(resolvedUrl)) return emptyList()

        if (resolvedUrl.contains("?trembed=") || resolvedUrl.contains("&trembed=")) {
            val refererHeader = if (depth == 0) baseUrl else resolvedUrl
            val embedHeaders = headers.newBuilder()
                .add("Referer", refererHeader)
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val embedBody = runCatching {
                client.newCall(GET(resolvedUrl, embedHeaders)).execute().use { response ->
                    if (!response.isSuccessful) "" else response.body?.string().orEmpty()
                }
            }.getOrDefault("")

            if (embedBody.isBlank()) return emptyList()

            val embedDocument = Jsoup.parse(embedBody)

            val dataAttributes = listOf("data-src", "data-url", "data-video", "data-link", "data-player")
            val attributeCandidates = dataAttributes.flatMap { attr ->
                embedDocument.select("[$attr]")
                    .map { element -> normalizePlayerUrl(element.attr(attr)) }
            }

            val candidates = (attributeCandidates + extractEmbedCandidates(embedBody))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { it.equals(resolvedUrl, ignoreCase = true) }
                .toSet()

            if (candidates.isEmpty()) {
                val iframeFallback = Jsoup.parse(embedBody)
                    .selectFirst("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                    ?.let { element ->
                        sequenceOf("src", "data-src", "data-lazy-src")
                            .map(element::attr)
                            .firstOrNull { it.isNotBlank() }
                    }
                    ?.let(::normalizePlayerUrl)
                    ?.takeIf { it.isNotBlank() }

                return iframeFallback?.let { serverVideoResolver(it, depth + 1) } ?: emptyList()
            }

            return candidates.flatMap { candidate -> serverVideoResolver(candidate, depth + 1) }
        }
        Log.e("Cine24h", "Resolving video from: $resolvedUrl")

        return when {
            arrayOf("fastream").any(resolvedUrl) -> {
                val link = if (resolvedUrl.contains("emb.html")) {
                    "https://fastream.to/embed-${resolvedUrl.substringAfterLast('/')}"
                } else {
                    resolvedUrl
                }
                FastreamExtractor(client, headers).videosFromUrl(link)
            }
            arrayOf("filemoon", "moonplayer", "moonwatch").any(resolvedUrl) -> filemoonExtractor.videosFromUrl(resolvedUrl, prefix = "Filemoon:", headers = headers)
            arrayOf("doodstream", "dood.", "ds2play", "doods.").any(resolvedUrl) -> doodExtractor.videosFromUrl(resolvedUrl, "")
            arrayOf("voe", "jilliandescribecompany").any(resolvedUrl) -> voeExtractor.videosFromUrl(resolvedUrl)
            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay","vidGuard").any(resolvedUrl) -> vidGuardExtractor.videosFromUrl(resolvedUrl, prefix = "")
            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Películas", "peliculas"),
            Pair("Series", "series"),
            Pair("Acción", "category/accion"),
            Pair("Animación", "category/animacion"),
            Pair("Anime", "category/anime"),
            Pair("Aventura", "category/aventura"),
            Pair("Bélica", "category/belica"),
            Pair("Ciencia ficción", "category/ciencia-ficcion"),
            Pair("Comedia", "category/comedia"),
            Pair("Crimen", "category/crimen"),
            Pair("Documental", "category/documental"),
            Pair("Drama", "category/drama"),
            Pair("Familia", "category/familia"),
            Pair("Fantasía", "category/fantasia"),
            Pair("Gerra", "category/gerra"),
            Pair("Historia", "category/historia"),
            Pair("Misterio", "category/misterio"),
            Pair("Música", "category/musica"),
            Pair("Navidad", "category/navidad"),
            Pair("Película de TV", "category/pelicula-de-tv"),
            Pair("Romance", "category/romance"),
            Pair("Suspenso", "category/suspense"),
            Pair("Terror", "category/terror"),
            Pair("Western", "category/western"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    protected open fun Element.getImageUrl(): String? {
        return when {
            isValidUrl("data-src") -> attr("abs:data-src")
            isValidUrl("data-lazy-src") -> attr("abs:data-lazy-src")
            isValidUrl("srcset") -> attr("abs:srcset").substringBefore(" ")
            isValidUrl("src") -> attr("abs:src")
            else -> ""
        }
    }

    protected open fun Element.isValidUrl(attrName: String): Boolean {
        if (!hasAttr(attrName)) return false
        return !attr(attrName).contains("data:image/")
    }

    private fun Array<String>.any(url: String): Boolean = this.any { url.contains(it, ignoreCase = true) }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
    }
}
