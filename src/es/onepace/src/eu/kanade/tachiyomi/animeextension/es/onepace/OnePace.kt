package eu.kanade.tachiyomi.animeextension.es.onepace

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.pixeldrainextractor.PixelDrainExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OnePace :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "One Pace"
    override val baseUrl = "https://onepace.net"
    override val lang = "es"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val locale: String
        get() = preferences.getString("preferred_locale", "es")!!

    // Popular
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/$locale/watch")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList =
            document.select("main h2 > a[href^='#']").map {
                val raw = it.attr("href").trimStart('#')
                val block = document.selectFirst("#$raw")
                SAnime.create().apply {
                    title = it.text()
                    setUrlWithoutDomain(raw)
                    val src = block?.selectFirst("img")?.attr("src")
                    val meta = document.selectFirst("meta[property=og:image]")?.attr("content")
                    thumbnail_url =
                        if (!src.isNullOrBlank()) {
                            if (src.startsWith("http")) src else "$baseUrl$src"
                        } else if (!meta.isNullOrBlank()) {
                            if (meta.startsWith("http")) meta else "$baseUrl$meta"
                        } else {
                            null
                        }
                }
            }
        return AnimesPage(animeList.distinctBy { it.url }, false)
    }

    // Anime details
    override fun animeDetailsRequest(anime: SAnime): Request {
        val arc = anime.url.trimStart('#').substringBefore("?")
        return GET("$baseUrl/$locale/watch", headers = Headers.headersOf("X-Arc", arc))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val arc = response.request.header("X-Arc") ?: ""
        val block = if (arc.isNotBlank()) document.selectFirst("#$arc") else null

        return SAnime.create().apply {
            title = block?.selectFirst("h2")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1")?.text().orEmpty()
            description = block?.selectFirst("p")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")

            val src = block?.selectFirst("img")?.attr("src")
            val meta = document.selectFirst("meta[property=og:image]")?.attr("content")
            thumbnail_url =
                if (!src.isNullOrBlank()) {
                    if (src.startsWith("http")) src else "$baseUrl$src"
                } else if (!meta.isNullOrBlank()) {
                    if (meta.startsWith("http")) meta else "$baseUrl$meta"
                } else {
                    null
                }

            setUrlWithoutDomain(arc)
        }
    }

    // Episodes
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val requestUrl = response.request.url.toString()
        // Parse quality from request URL if provided (stored as ?q=720p in season URL)
        val quality = requestUrl.substringAfter("?q=", "")
        val arc = response.request.header("X-Arc") ?: ""
        val pdUrl = response.request.header("X-PdUrl") ?: ""
        val block = if (arc.isNotBlank()) document.selectFirst("#$arc") else document

        val links =
            if (pdUrl.isNotBlank()) {
                block?.select("a[href='$pdUrl']") ?: emptyList()
            } else {
                block?.select("a[href*='pixeldrain']")?.filter { el ->
                    if (quality.isBlank()) true else el.text().contains(quality, ignoreCase = true) || el.attr("href").contains(quality)
                } ?: emptyList()
            }

        val episodes = mutableListOf<SEpisode>()
        val seen = mutableSetOf<String>()

        fun normPath(rawUrl: String): String = try {
            val u = java.net.URL(rawUrl)
            u.path.trimEnd('/')
        } catch (e: Exception) {
            rawUrl.substringAfter(baseUrl).substringBefore('?').trimEnd('/')
        }

        fun cleanEpisodeName(name: String): String = name
            .replace(Regex("\\[.*?\\]"), "")
            .replace(".mp4", "")
            .replace(".mkv", "")
            .trim()

        fun parseEpisodeNumber(name: String): Float {
            val cleanName = cleanEpisodeName(name)
            // Look for a number at the end or preceded by space (e.g., "Wano 05")
            val numMatch = Regex("(\\d+)(?:\\s|$)").find(cleanName)
            return numMatch?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        }

        links.forEach { el ->
            val hrefRaw = el.attr("abs:href")
            try {
                // If link is a folder (/l/{id}), call Pixeldrain API and expand files
                val listId = Regex("/l/([A-Za-z0-9_-]+)").find(hrefRaw)?.groupValues?.get(1)
                if (!listId.isNullOrEmpty()) {
                    val apiUrl = "https://pixeldrain.net/api/list/$listId"
                    val res = client.newCall(GET(apiUrl)).execute()
                    val body = res.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val itemRegex =
                            Regex("\"id\"\\s*:\\s*\"([^\"]+)\",\\s*\"name\"\\s*:\\s*\"([^\"]+)\"")
                        itemRegex.findAll(body).forEach { m ->
                            val fileId = m.groupValues[1]
                            val fileName = m.groupValues[2]
                            val fileUrl = "https://pixeldrain.net/u/$fileId"
                            val key = normPath(fileUrl)
                            if (seen.add(key)) {
                                episodes.add(
                                    SEpisode.create().apply {
                                        val epNum = parseEpisodeNumber(fileName)
                                        episode_number = epNum
                                        val prefix = if (epNum > 0) "[${epNum.toInt().toString().padStart(2, '0')}] " else ""
                                        name = "$prefix$fileName"
                                        setUrlWithoutDomain(fileUrl)
                                        val fileIdNormalized = fileId.substringBefore("?")
                                        try {
                                            setEpisodeThumbnail(this, "https://pixeldrain.net/api/file/$fileIdNormalized/thumbnail")
                                        } catch (_: Exception) {
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    // single file link
                    var href = hrefRaw
                    if (href.startsWith("/")) {
                        href = "https://pixeldrain.net$href"
                    } else if (href.contains("onepace.net/u/")) {
                        href = href.replace("onepace.net/u/", "pixeldrain.net/u/")
                    }

                    val key = normPath(href)
                    if (seen.add(key)) {
                        episodes.add(
                            SEpisode.create().apply {
                                val epNameRaw = el.text().trim()
                                val epNum = parseEpisodeNumber(epNameRaw)
                                val prefix = if (epNum > 0) "[${epNum.toInt().toString().padStart(2, '0')}] " else ""
                                name = "$prefix$epNameRaw"
                                episode_number = epNum
                                setUrlWithoutDomain(href)
                                val fileId = href.substringAfterLast("/")
                                try {
                                    setEpisodeThumbnail(this, "https://pixeldrain.net/api/file/$fileId/thumbnail")
                                } catch (_: Exception) {
                                }
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                // on error, fallback to adding the raw link
                var href = hrefRaw
                if (href.startsWith("/")) {
                    href = "https://pixeldrain.net$href"
                } else if (href.contains("onepace.net/u/")) {
                    href = href.replace("onepace.net/u/", "pixeldrain.net/u/")
                }

                val key =
                    try {
                        normPath(href)
                    } catch (_: Exception) {
                        href
                    }
                if (seen.add(key)) {
                    episodes.add(
                        SEpisode.create().apply {
                            val epNameRaw = el.text().trim()
                            val epNum = parseEpisodeNumber(epNameRaw)
                            val prefix = if (epNum > 0) "[${epNum.toInt().toString().padStart(2, '0')}] " else ""
                            name = "$prefix$epNameRaw"
                            episode_number = epNum
                            setUrlWithoutDomain(href)
                            val fileIdRaw = href.substringAfterLast("/")
                            val fileId = fileIdRaw.substringBefore("?")
                            try {
                                setEpisodeThumbnail(this, "https://pixeldrain.net/api/file/$fileId/thumbnail")
                            } catch (_: Exception) {
                            }
                        },
                    )
                }
            }
        }

        // Final safeguard: deduplicate and sort
        return episodes
            .distinctBy {
                val epUrl = it.url ?: ""
                normPath(epUrl)
            }.sortedByDescending { it.episode_number }
    }

    private fun setEpisodeThumbnail(
        episode: SEpisode,
        url: String,
    ) {
        val cls = episode.javaClass
        val methods =
            arrayOf(
                "setPreview_url",
                "setPreviewUrl",
                "setThumbnail_url",
                "setThumbnailUrl",
                "setImageUrl",
                "setImage_url",
                "setArtworkUrl",
                "setArtwork_url",
                "setCoverUrl",
                "setCover_url",
            )
        for (m in methods) {
            try {
                cls.getMethod(m, String::class.java).invoke(episode, url)
                return
            } catch (_: Exception) {
            }
        }
        val fields =
            arrayOf(
                "preview_url",
                "previewUrl",
                "thumbnail_url",
                "thumbnailUrl",
                "imageUrl",
                "image_url",
                "artworkUrl",
                "artwork_url",
                "coverUrl",
                "cover_url",
            )
        for (f in fields) {
            try {
                val fd = cls.getDeclaredField(f)
                fd.isAccessible = true
                fd.set(episode, url)
                return
            } catch (_: Exception) {
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val raw = anime.url.trimStart('#')
        val arc = raw.substringBefore("?")

        val quality = if (raw.contains("?q=")) raw.substringAfter("?q=") else ""
        val pdUrlRaw = if (raw.contains("?pdurl=")) raw.substringAfter("?pdurl=") else ""
        val pdUrl = if (pdUrlRaw.isNotEmpty()) java.net.URLDecoder.decode(pdUrlRaw, "UTF-8") else ""

        val url = if (quality.isNotBlank()) "$baseUrl/$locale/watch?q=$quality" else "$baseUrl/$locale/watch"
        val headersBuilder = Headers.Builder().add("X-Arc", arc)
        if (pdUrl.isNotBlank()) headersBuilder.add("X-PdUrl", pdUrl)

        return GET(url, headers = headersBuilder.build())
    }

    // Videos: get videos from PixelDrain
    override fun videoListRequest(episode: SEpisode): Request {
        var url = episode.url
        if (url.startsWith("/")) {
            url = "https://pixeldrain.net$url"
        } else if (url.contains("onepace.net/u/")) {
            url = url.replace("onepace.net/u/", "pixeldrain.net/u/")
        }
        return GET(url)
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        return try {
            pixelDrainExtractor.videosFromUrl(url, "PixelDrain ")
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val pixelDrainExtractor by lazy { PixelDrainExtractor(client) }

    // Latest / Search not supported
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        // Search is unsupported; return a harmless request so caller can handle an empty result.
        return GET("$baseUrl/")
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        // Return empty search results to avoid crashes when the framework requests related/search.
        return AnimesPage(emptyList(), false)
    }

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val localePref =
            ListPreference(screen.context).apply {
                key = "preferred_locale"
                title = "Idioma de la web (Website Locale)"
                entries = arrayOf("Español", "English", "Français", "Português", "Deutsch", "Italiano", "العربية")
                entryValues = arrayOf("es", "en", "fr", "pt", "de", "it", "ar")
                setDefaultValue("es")
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }
        screen.addPreference(localePref)
    }
}
