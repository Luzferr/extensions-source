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
import org.jsoup.nodes.Entities
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

    private val seasonEpisodeRegex = Regex("(\\d+)\\s*[xX-]\\s*(\\d+)")
    private val slugEpisodeRegex = Regex("(?:season|temporada)[^\\d]*(\\d+)[^\\d]+(?:episode|episodio|capitulo)[^\\d]*(\\d+)", RegexOption.IGNORE_CASE)
    private val seasonLabelRegex = Regex("(\\d+)")

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
        val variantOffsets = mutableMapOf<Pair<Int, Int>, MutableMap<String, Int>>()
        val structuredEpisodes = parseStructuredSeasons(document, variantOffsets)
        if (structuredEpisodes.isNotEmpty()) return structuredEpisodes

        val fallbackEpisodes = parseEpisodeAnchorsFallback(document, variantOffsets)
        if (fallbackEpisodes.isNotEmpty()) return fallbackEpisodes

        return listOf(createMovieEpisode(document))
    }

    private fun parseStructuredSeasons(
        document: Document,
        variantOffsets: MutableMap<Pair<Int, Int>, MutableMap<String, Int>>,
    ): List<SEpisode> {
        val seasonBlocks = document.select("#seasons .se-c, #seasons > div")
        if (seasonBlocks.isEmpty()) return emptyList()

        val episodes = seasonBlocks.flatMap { seasonBlock ->
            val seasonLabel = seasonBlock.selectFirst(".se-t")?.text()?.trim()
            val defaultSeason = seasonLabelRegex.find(seasonLabel.orEmpty())?.value?.toIntOrNull()

            seasonBlock.select("ul.episodios > li")
                .toList()
                .mapIndexedNotNull { index, element ->
                    val anchor = element.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                    val href = anchor.attr("abs:href")

                    val numerando = element.selectFirst(".numerando")?.text()
                    val titleText = anchor.ownText().ifBlank { anchor.text() }.trim()

                    val (seasonNumber, episodeNumber) = findSeasonEpisode(numerando, seasonLabel, titleText, href)
                        ?: Pair(defaultSeason ?: 1, index + 1)

                    val resolvedSeason = seasonNumber ?: defaultSeason ?: 1
                    val resolvedEpisode = episodeNumber ?: index + 1

                    val languageTag = buildLanguageTag(element)
                    val languageKey =
                        languageTag?.lowercase()?.replace("[^a-z0-9]+".toRegex(), "")?.ifBlank { null }
                    val offsetsForEpisode = variantOffsets.getOrPut(resolvedSeason to resolvedEpisode) { mutableMapOf() }
                    val variantIndex = offsetsForEpisode.getOrPut(languageKey ?: "variant") { offsetsForEpisode.size }
                    val displayName = buildEpisodeName(resolvedSeason, resolvedEpisode, titleText, languageTag)

                    val uniqueUrl = buildVariantUrl(href, languageKey ?: "variant", variantIndex)

                    val baseNumber = ((resolvedSeason.coerceAtLeast(0) * 100) + resolvedEpisode).toFloat()

                    SEpisode.create().apply {
                        setUrlWithoutDomain(uniqueUrl)
                        name = displayName
                        scanlator = languageTag
                        episode_number = baseNumber + (variantIndex * 0.01f)
                    }
                }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    private fun createMovieEpisode(document: Document): SEpisode {
        return SEpisode.create().apply {
            episode_number = 1f
            name = "PELÍCULA"
            scanlator = document.select(".AAIco-date_range").text().trim()
            setUrlWithoutDomain(document.location())
        }
    }

    private fun parseEpisodeAnchorsFallback(
        document: Document,
        variantOffsets: MutableMap<Pair<Int, Int>, MutableMap<String, Int>>,
    ): List<SEpisode> {
        val episodeAnchors = document.select("a[href*=\"/episode/\"]")
            .toList()
            .filter { it.text().isNotBlank() }

        if (episodeAnchors.isEmpty()) return emptyList()

        val episodes = episodeAnchors.mapIndexedNotNull { index, anchor ->
            val href = anchor.attr("abs:href")
            val rawName = anchor.text().trim()

            val (seasonNumber, episodeNumber) = findSeasonEpisode(rawName, href)
                ?: Pair(null, null)

            val baseName = rawName
                .takeIf { it.isNotBlank() }
                ?: "Episodio ${index + 1}"

            val resolvedSeason = seasonNumber ?: 0
            val resolvedEpisode = episodeNumber ?: (index + 1)

            val languageTag = buildLanguageTag(anchor)
            val languageKey = languageTag?.lowercase()?.replace("[^a-z0-9]+".toRegex(), "")?.ifBlank { null }
            val displayName = if (seasonNumber != null && episodeNumber != null) {
                buildEpisodeName(resolvedSeason, resolvedEpisode, rawName, languageTag)
            } else {
                baseName
            }

            val keySeason = if (seasonNumber != null) resolvedSeason else -1
            val keyEpisode = if (episodeNumber != null) resolvedEpisode else index + 1
            val offsetsForEpisode = variantOffsets.getOrPut(keySeason to keyEpisode) { mutableMapOf() }
            val variantIndex = offsetsForEpisode.getOrPut(languageKey ?: "variant") { offsetsForEpisode.size }

            val uniqueUrl = buildVariantUrl(href, languageKey ?: "variant", variantIndex)
            val baseNumber = if (seasonNumber != null && episodeNumber != null) {
                ((resolvedSeason * 100) + resolvedEpisode).toFloat()
            } else {
                (index + 1).toFloat()
            }

            SEpisode.create().apply {
                setUrlWithoutDomain(uniqueUrl)
                name = displayName
                scanlator = languageTag
                episode_number = baseNumber + (variantIndex * 0.01f)
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    private fun buildLanguageTag(element: Element): String? {
        val flagTitle = element.selectFirst("img[alt], img[title]")?.let { img ->
            sequenceOf(img.attr("alt"), img.attr("title"))
                .firstOrNull { it.isNotBlank() }
        }

        val badgeText = element.selectFirst(".flag, .server, span")?.text()?.lowercase().orEmpty()
        val raw = flagTitle?.lowercase().orEmpty() + badgeText

        return when {
            "lat" in raw || "latino" in raw -> "LAT"
            "cast" in raw || "esp" in raw || "españ" in raw || "castell" in raw -> "CAST"
            "sub" in raw || "ing" in raw || "eng" in raw -> "SUB"
            else -> null
        }
    }

    private fun findSeasonEpisode(vararg sources: String?): Pair<Int?, Int?>? {
        sources.forEach { source ->
            if (source.isNullOrBlank()) return@forEach

            seasonEpisodeRegex.find(source)?.let { match ->
                val season = match.groupValues.getOrNull(1)?.toIntOrNull()
                val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
                return season to episode
            }

            slugEpisodeRegex.find(source)?.let { match ->
                val season = match.groupValues.getOrNull(1)?.toIntOrNull()
                val episode = match.groupValues.getOrNull(2)?.toIntOrNull()
                return season to episode
            }
        }

        return null
    }

    private fun buildEpisodeName(seasonNumber: Int, episodeNumber: Int, titleText: String, languageTag: String?): String {
        val base = "T$seasonNumber - E$episodeNumber"
        val cleanTitle = titleText.trim().takeIf { it.isNotBlank() && !it.startsWith(base) }
        val name = cleanTitle?.let { "$base - $it" } ?: base
        return languageTag?.let { "$name [$it]" } ?: name
    }

    private fun buildVariantUrl(href: String, variantKey: String, variantIndex: Int): String {
        val suffix = buildString {
            append(variantKey.ifBlank { "variant" })
            if (variantIndex > 0) append(variantIndex)
        }

        return if (href.contains('#')) {
            "$href-$suffix"
        } else {
            "$href#$suffix"
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val collectedUrls = collectPlayerUrls(document, referer)
        if (collectedUrls.isEmpty()) return emptyList()

        return collectedUrls
            .distinct()
            .parallelCatchingFlatMapBlocking(::serverVideoResolver)
    }

    // Collect all playable URLs from the page, prioritising explicit player options.
    private fun collectPlayerUrls(document: Document, referer: String): List<String> {
        val primaryUrls = document.select(playerOptionSelector)
            .mapNotNull { option ->
                option.extractPlayerUrl(referer).takeIf { it.isNotBlank() }
            }

        if (primaryUrls.isNotEmpty()) return primaryUrls

        return document.select(fallbackPlayerSelector)
            .flatMap { container ->
                val html = Entities.unescape(container.html())
                val inlineCandidates = collectEmbedUrls(html)
                inlineCandidates.ifEmpty {
                    Jsoup.parse(html)
                        .selectFirst("iframe[src], iframe[data-src], iframe[data-lazy-src]")
                        ?.firstAvailableAttribute(embedSrcAttributes)
                        ?.let(::normalizePlayerUrl)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { listOf(it) }
                        ?: emptyList()
                }
            }
    }

    private fun collectEmbedUrls(html: String): List<String> = Jsoup.parse(html).collectEmbedUrls()

    // Normalises and filters every URL hidden inside players, iframes, attributes or raw text.
    private fun Document.collectEmbedUrls(): List<String> {
        val candidates = mutableSetOf<String>()

        select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
            .mapNotNull { it.firstAvailableAttribute(embedSrcAttributes) }
            .map(::normalizePlayerUrl)
            .filter { it.isNotBlank() && isSupportedHost(it) }
            .forEach(candidates::add)

        select("source[src], video[src]")
            .mapNotNull {
                it.attr("abs:src").takeIf(String::isNotBlank) ?: it.attr("src")
            }
            .map(::normalizePlayerUrl)
            .filter { it.isNotBlank() && isSupportedHost(it) }
            .forEach(candidates::add)

        playerDataAttributes.forEach { attr ->
            select("[$attr]")
                .mapNotNull { element -> element.attr(attr).takeIf(String::isNotBlank) }
                .map(::normalizePlayerUrl)
                .filter { it.isNotBlank() && isSupportedHost(it) }
                .forEach(candidates::add)
        }

        urlRegex.findAll(outerHtml())
            .map { it.value }
            .map(::normalizePlayerUrl)
            .filter { it.isNotBlank() && isSupportedHost(it) }
            .forEach(candidates::add)

        return candidates.toList()
    }

    private fun Element.firstAvailableAttribute(attributes: Array<String>): String? {
        return attributes.firstNotNullOfOrNull { attrName ->
            attr(attrName).takeIf { it.isNotBlank() }
        }
    }

    private fun Element.extractPlayerUrl(referer: String): String {
        val directCandidate = playerDirectAttributes
            .firstNotNullOfOrNull { attribute -> attr(attribute).takeIf { it.isNotBlank() } }
            ?: selectFirst("a[href]")?.attr("href")

        if (!directCandidate.isNullOrBlank()) {
            return normalizePlayerUrl(Entities.unescape(directCandidate))
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

        val primaryBody = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, formBody)).execute().use { it.body.string() }
        val body = if (primaryBody.isNotBlank() && !primaryBody.contains("error", ignoreCase = true)) {
            primaryBody
        } else {
            client.newCall(GET(ajaxUrl, ajaxHeaders)).execute().use { it.body.string() }
        }
        if (body.isBlank()) return ""

        val embedFromJson = embedUrlRegex.find(body)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let(Entities::unescape)
            ?.takeIf { it.isNotBlank() }
        if (embedFromJson != null) return normalizePlayerUrl(embedFromJson)

        val ajaxDocument = Jsoup.parse(body)
        val iframe = ajaxDocument.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrBlank()) return normalizePlayerUrl(Entities.unescape(iframe))

        val source = ajaxDocument.selectFirst("source")?.attr("src")
        if (!source.isNullOrBlank()) return normalizePlayerUrl(Entities.unescape(source))

        return ""
    }

    private fun normalizePlayerUrl(url: String): String {
        if (url.isBlank()) return ""

        val decoded = decodeIfBase64(url)
        val cleaned = Entities.unescape(decoded).trim()
        if (cleaned.isBlank()) return ""

        val resolved = when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$baseUrl$cleaned"
            else -> cleaned
        }

        return resolved.replace("&amp;", "&")
    }

    private val embedUrlRegex = Regex("\\\"embed_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val urlRegex = Regex("https?://[^\\s'\"]+")
    private val playerOptionSelector = "ul#playeroptionsul li, ul.optnslst li[data-option], ul.optnslst li[data-src]"
    private val fallbackPlayerSelector = ".TPlayerTb"
    private val playerDirectAttributes = arrayOf("data-option", "data-player", "data-src", "data-video", "data-url", "data-link", "href")
    private val playerDataAttributes = arrayOf("data-src", "data-url", "data-video", "data-link", "data-player")
    private val embedSrcAttributes = arrayOf("src", "data-src", "data-lazy-src")
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
                    if (!response.isSuccessful) "" else response.body.string()
                }
            }.getOrDefault("")

            if (embedBody.isBlank()) return emptyList()

            val candidates = Jsoup.parse(embedBody)
                .collectEmbedUrls()
                .filterNot { it.equals(resolvedUrl, ignoreCase = true) }

            if (candidates.isEmpty()) return emptyList()

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
            arrayOf("vembed", "guard", "listeamed", "bembed", "vgfplay", "vidGuard").any(resolvedUrl) -> vidGuardExtractor.videosFromUrl(resolvedUrl, prefix = "")
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
