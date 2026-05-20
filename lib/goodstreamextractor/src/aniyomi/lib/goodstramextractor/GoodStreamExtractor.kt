package aniyomi.lib.goodstramextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import kotlin.getValue

class GoodStreamExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val videos = mutableListOf<Video>()

        runCatching {
            val doc = client.newCall(GET(url, headers)).execute().asJsoup()

            // Step 1: Try structured file regex in scripts first
            doc.select("script").forEach { script ->
                val scriptData = script.data()
                val fileRegex = Regex("""(?i)file\s*:\s*["']((?:https?:)?//[^"']+\.(?:m3u8|mp4|mkv)[^"']*)["']""")
                fileRegex.findAll(scriptData).forEach { match ->
                    var link = match.groupValues[1]
                    if (link.startsWith("//")) {
                        link = "https:$link"
                    }
                    addVideoLink(link, url, name, videos)
                }
            }

            // Step 2: Fallback to loose raw URL scan inside scripts ONLY if structured scan found nothing
            if (videos.isEmpty()) {
                doc.select("script").forEach { script ->
                    val scriptData = script.data()
                    val genericUrlRegex = Regex("""https?://[^\s"'`<>]+?\.(?:m3u8|mp4)[^\s"'`<>]*""")
                    genericUrlRegex.findAll(scriptData).forEach { match ->
                        val link = match.value
                        addVideoLink(link, url, name, videos)
                    }
                }
            }

            // Step 3: Fallback to loose raw URL scan in the entire HTML body ONLY if scripts found nothing
            if (videos.isEmpty()) {
                val htmlContent = doc.outerHtml()
                val genericUrlRegex = Regex("""https?://[^\s"'`<>]+?\.(?:m3u8|mp4)[^\s"'`<>]*""")
                genericUrlRegex.findAll(htmlContent).forEach { match ->
                    val link = match.value
                    addVideoLink(link, url, name, videos)
                }
            }
        }

        // Deduplicate videos by their videoUrl
        return videos.distinctBy { it.videoUrl }
    }

    private fun addVideoLink(link: String, url: String, name: String, videos: MutableList<Video>) {
        if (link.contains(".m3u8")) {
            runCatching {
                videos.addAll(
                    playlistUtils.extractFromHls(link, url, videoNameGen = { "$name$it" }),
                )
            }
        } else {
            videos.add(
                Video(
                    videoUrl = link,
                    videoTitle = name,
                    headers = headers,
                ),
            )
        }
    }
}
