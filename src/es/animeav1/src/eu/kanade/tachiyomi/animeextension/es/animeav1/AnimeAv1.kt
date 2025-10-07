package eu.kanade.tachiyomi.animeextension.es.animeav1

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.pixeldrainextractor.PixelDrainExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeAv1 :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "AnimeAv1"

    override val baseUrl = "https://animeav1.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "DUB")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "DUB")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "PixelDrain"
        private val SERVER_LIST =
            arrayOf(
                "PixelDrain",
                "HLS",
                "StreamWish",
                "Voe",
                "YourUpload",
                "FileLions",
                "StreamHideVid",
            )
    }

    private val languageSectionRegex = Regex("""([A-Z]+)\s*:\s*\[(.*?)]""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val serverEntryRegex = Regex("""server\s*:\s*['"]([^'"]+)['"]\s*,\s*url\s*:\s*['"]([^'"]+)['"]""")

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val animeDetails =
            SAnime.create().apply {
                title = doc.selectFirst("h1.line-clamp-2")?.text()?.trim() ?: ""
                description = doc.selectFirst(".entry > p")?.text()
                genre = doc.select("header > .items-center > a").joinToString { it.text() }
                thumbnail_url = doc.selectFirst("img.object-cover")?.attr("src")
                fetch_type = FetchType.Episodes
            }
        doc.select("header > .items-center.text-sm span").eachText().forEach {
            when {
                it.contains("Finalizado") -> animeDetails.status = SAnime.COMPLETED
                it.contains("En emisión") -> animeDetails.status = SAnime.ONGOING
            }
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/catalogo?order=popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article[class*=\"group/item\"]")
        val nextPage = document.select(".pointer-events-none:not([class*=\"max-sm:hidden\"]) ~ a").any()
        val animeList =
            elements.map { element ->
                SAnime.create().apply {
                    setUrlWithoutDomain(element.selectFirst("a")?.attr("abs:href").orEmpty())
                    title = element.select("header h3").text()
                    thumbnail_url = element.selectFirst(".bg-current img")?.attr("abs:src")
                    fetch_type = FetchType.Episodes
                }
            }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/catalogo?order=latest_released&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val params = AnimeAv1Filters.getSearchParameters(filters)
        return when {
            query.isNotBlank() -> {
                GET("$baseUrl/catalogo?search=$query&page=$page", headers)
            }

            params.filter.isNotBlank() -> {
                GET(
                    "$baseUrl/catalogo${params.getQuery().run { if (isNotBlank()) "$this&page=$page" else this }}",
                    headers,
                )
            }

            else -> {
                popularAnimeRequest(page)
            }
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url.toAbsoluteUrl(), headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val script = doc.selectFirst("script:containsData(node_ids)")?.data().orEmpty()

        val mediaIdRegex = """media\s*:\s*\{\s*id\s*:\s*(\d+)""".toRegex()
        val mediaId = mediaIdRegex.find(script)?.groupValues?.get(1)
        val cdnUrlRegex = """PUBLIC_CDN_URL\s*[:=]\s*['"](https?://[^'"]+)['"]""".toRegex()
        val cdnUrl = cdnUrlRegex.find(script)?.groupValues?.get(1) ?: "https://cdn.animeav1.com"

        val episodeListRegex = """episodes\s*:\s*\[([^\]]*)\]""".toRegex()
        val episodeRegex = """\{\s*id\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*,\s*number\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*\}""".toRegex()
        val episodes =
            episodeListRegex
                .find(script)
                ?.let {
                    episodeRegex
                        .findAll(it.groupValues[1])
                        .map { match ->
                            val number = match.groupValues[2]
                            val episodeNumber = number.toFloatOrNull() ?: 0F
                            val imageUrl =
                                if (mediaId != null) {
                                    "$cdnUrl/screenshots/$mediaId/${number.toInt()}.jpg"
                                } else {
                                    null
                                }

                            SEpisode.create().apply {
                                name = "Episodio $number"
                                episode_number = episodeNumber
                                imageUrl?.let { preview_url = it }
                                setUrlWithoutDomain("${doc.location().removeSuffix("/")}/$number")
                            }
                        }.toList()
                }.orEmpty()

        return episodes.reversed()
    }

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListRequest(episode: SEpisode): Request = GET(episode.url.toAbsoluteUrl(), headers)

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(node_ids)")?.data().orEmpty()
        if (script.isBlank()) return emptyList()

        val hosterGroups = linkedMapOf<Pair<String, String>, MutableList<Video>>()

        val requestEntries =
            languageSectionRegex
                .findAll(script)
                .flatMap { sectionMatch ->
                    val languageTag = sectionMatch.groupValues[1].trim().uppercase()
                    val normalizedLanguage = if (languageTag == "ALL") "" else languageTag
                    val block = sectionMatch.groupValues[2]
                    serverEntryRegex
                        .findAll(block)
                        .mapNotNull { entryMatch ->
                            val serverSlug = entryMatch.groupValues[1].trim()
                            val url = entryMatch.groupValues[2].trim().substringBefore("?embed")
                            if (url.isBlank()) {
                                null
                            } else {
                                HosterRequestEntry(normalizedLanguage, serverSlug, url)
                            }
                        }
                }.toList()

        if (requestEntries.isEmpty()) return emptyList()

        val resolvedEntries =
            requestEntries.parallelCatchingFlatMapBlocking { entry ->
                val videos = serverVideoResolver(entry.url, languageLabel(entry.languageTag), entry.serverSlug)
                if (videos.isEmpty()) {
                    emptyList()
                } else {
                    listOf(HosterEntry(entry.languageTag, entry.serverSlug, videos))
                }
            }

        resolvedEntries.forEach { entry ->
            val serverDisplay = displayServerName(entry.serverSlug)
            val key = entry.languageTag to serverDisplay
            val list = hosterGroups.getOrPut(key) { mutableListOf() }
            list.addAll(entry.videos)
        }

        val preferredLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        val hosters =
            hosterGroups.entries.mapIndexed { index, (key, videos) ->
                val (languageTag, serverDisplay) = key
                val languageDisplay = languageLabel(languageTag)
                val sortedVideos = videos.sortVideos()
                HosterGroupMeta(
                    index = index,
                    languageTag = languageTag,
                    serverDisplay = serverDisplay,
                    hoster =
                        Hoster(
                            hosterName =
                                listOfNotNull(
                                    languageDisplay.takeIf { it.isNotBlank() },
                                    serverDisplay,
                                ).joinToString(" ")
                                    .ifBlank { serverDisplay },
                            videoList = sortedVideos,
                        ),
                )
            }

        return hosters
            .sortedWith(
                compareByDescending<HosterGroupMeta> { it.matchesLanguage(preferredLang) }
                    .thenByDescending { it.matchesServer(preferredServer) }
                    .thenBy { it.index },
            ).map { it.hoster }
    }

    override fun getFilterList(): AnimeFilterList = AnimeAv1Filters.FILTER_LIST

    // --------------------------------Video extractors------------------------------------
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val pixelDrainExtractor by lazy { PixelDrainExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    fun serverVideoResolver(
        url: String,
        prefix: String = "",
        serverName: String? = "",
    ): List<Video> =
        runCatching {
            val matched =
                if (!serverName.isNullOrBlank()) {
                    canonicalServerSlug(serverName)
                } else {
                    conventions
                        .firstNotNullOfOrNull { (key, names) ->
                            if (names.any { name -> url.contains(name, ignoreCase = true) }) {
                                key
                            } else {
                                null
                            }
                        } ?: ""
                }

            val prefixBase = buildPrefix(prefix, displayServerName(matched))
            val prefixWithSpace = prefixBase.withTrailingSpace()

            when (matched) {
                "voe" -> {
                    voeExtractor.videosFromUrl(url, prefixWithSpace)
                }

                "pixeldrain" -> {
                    pixelDrainExtractor.videosFromUrl(url, prefixWithSpace)
                }

                "mp4upload" -> {
                    mp4uploadExtractor.videosFromUrl(url, headers, prefix = prefixWithSpace)
                }

                "streamwish" -> {
                    streamWishExtractor.videosFromUrl(url, videoNameGen = { quality -> buildVideoName(prefixBase, quality) })
                }

                "filelions" -> {
                    streamWishExtractor.videosFromUrl(url, videoNameGen = { quality -> buildVideoName(prefixBase, quality) })
                }

                "yourupload" -> {
                    yourUploadExtractor.videoFromUrl(url, headers = headers, prefix = prefixWithSpace)
                }

                "playerzilla" -> {
                    val m3u = url.replace("play/", "m3u8/")
                    listOf(
                        Video(
                            videoTitle = buildVideoName(prefixBase, "HLS"),
                            videoUrl = m3u,
                        ),
                    )
                }

                else -> {
                    universalExtractor.videosFromUrl(url, headers, prefix = prefixWithSpace)
                }
            }
        }.getOrNull() ?: emptyList()

    private val conventions =
        listOf(
            "voe" to
                listOf(
                    "voe",
                    "tubelessceliolymph",
                    "simpulumlamerop",
                    "urochsunloath",
                    "nathanfromsubject",
                    "nathanfromsubject",
                    "yip.",
                    "metagnathtuggers",
                    "donaldlineelse",
                ),
            "pixeldrain" to listOf("pixeldrain", "PDrain", "pixeldr"),
            "mp4upload" to listOf("mp4upload"),
            "streamwish" to
                listOf(
                    "wishembed",
                    "streamwish",
                    "strwish",
                    "wish",
                    "kswplayer",
                    "swhoi",
                    "multimovies",
                    "uqloads",
                    "neko-stream",
                    "swdyu",
                    "iplayerhls",
                    "streamgg",
                ),
            "filelions" to listOf("filelions", "lion", "fviplions"),
            "yourupload" to listOf("yourupload", "upload"),
            "vidhide" to
                listOf(
                    "streamhidevid",
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
            "playerzilla" to listOf("player.zilla", "playerzilla", "zilla-networks", "zilla", "hls"),
        )

    private val serverDisplayNames =
        mapOf(
            "voe" to "Voe",
            "pixeldrain" to "PixelDrain",
            "mp4upload" to "Mp4Upload",
            "streamwish" to "StreamWish",
            "filelions" to "FileLions",
            "yourupload" to "YourUpload",
            "vidhide" to "StreamHideVid",
            "playerzilla" to "HLS",
        )

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val preferredLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT
        val qualityRegex = Regex("""(\d+)p""", RegexOption.IGNORE_CASE)

        fun Video.matchesLanguage(): Int =
            if (preferredLang.isBlank()) {
                0
            } else if (videoTitle.contains(preferredLang, ignoreCase = true)) {
                1
            } else {
                0
            }

        fun Video.matchesServer(): Int =
            if (preferredServer.isBlank()) {
                0
            } else if (videoTitle.contains(preferredServer, ignoreCase = true)) {
                1
            } else {
                0
            }

        fun Video.matchesQuality(): Int = if (videoTitle.contains(preferredQuality, ignoreCase = true)) 1 else 0

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

    private fun displayServerName(serverSlug: String): String {
        val canonical = canonicalServerSlug(serverSlug)
        if (canonical.isBlank()) return "Unknown"
        return serverDisplayNames[canonical] ?: canonical.replaceFirstChar { char ->
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
                        name.equals(lower, true) || lower.contains(name, ignoreCase = true)
                    }
            }?.first ?: lower
    }

    private fun buildPrefix(
        languageLabel: String,
        serverName: String,
    ): String =
        sequenceOf(languageLabel, serverName)
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

    private fun languageLabel(languageTag: String): String = languageTag.takeIf { it.isNotBlank() }?.let { "[${it.uppercase()}]" } ?: ""

    private data class HosterRequestEntry(
        val languageTag: String,
        val serverSlug: String,
        val url: String,
    )

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
        fun matchesLanguage(preferred: String): Int =
            if (preferred.isBlank()) {
                0
            } else if (languageTag.equals(preferred, ignoreCase = true)) {
                1
            } else {
                0
            }

        fun matchesServer(preferred: String): Int =
            if (preferred.isBlank()) {
                0
            } else if (serverDisplay.equals(preferred, ignoreCase = true)) {
                1
            } else {
                0
            }
    }

    // Funciones auxiliares simplificadas
    private fun String.toAbsoluteUrl(): String =
        if (startsWith("http", true)) {
            this
        } else {
            val separator = if (startsWith("/")) "" else "/"
            "$baseUrl$separator$this"
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
                key = PREF_LANG_KEY
                title = "Preferred Language"
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
