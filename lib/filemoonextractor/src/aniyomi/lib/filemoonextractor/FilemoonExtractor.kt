package aniyomi.lib.filemoonextractor

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.jsunpacker.JsUnpacker
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class FilemoonExtractor(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences? = null,
) {

    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String = "Filemoon - ", headers: Headers? = null): List<Video> {
        var httpUrl = url.toHttpUrl()

        // FIX 4: añadir User-Agent real para evitar bloqueos de Cloudflare
        val videoHeaders = (headers?.newBuilder() ?: Headers.Builder())
            .set("Referer", url)
            .set("Origin", "https://${httpUrl.host}")
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .build()

        val doc = client.newCall(GET(url, videoHeaders)).execute().asJsoup()

        val jsEval = doc.selectFirst("script:containsData(eval):containsData(m3u8)")?.data() ?: run {
            // FIX 1: reemplazar !! por ?: return emptyList() para evitar NPE
            val iframeUrl = doc.selectFirst("iframe[src]")?.attr("src")
                ?: return emptyList()

            // FIX 5: reconstruir headers con el host del iframe, no del original
            httpUrl = iframeUrl.toHttpUrl()
            val iframeHeaders = Headers.Builder()
                .set("Referer", iframeUrl)
                .set("Origin", "https://${httpUrl.host}")
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .build()

            val iframeDoc = client.newCall(GET(iframeUrl, iframeHeaders)).execute().asJsoup()

            // FIX 2: reemplazar !! por ?: return emptyList() para evitar NPE
            iframeDoc.selectFirst("script:containsData(eval):containsData(m3u8)")?.data()
                ?: return emptyList()
        }

        val unpacked = JsUnpacker.unpackAndCombine(jsEval).orEmpty()

        // FIX 3 + MEJORA: usar regex en lugar de substringAfter/substringBefore
        // Cubre formatos: {file:"url"}, {file:"url",type:"hls"}, sources:[{file:"url"}]
        val masterUrl = unpacked.takeIf(String::isNotBlank)
            ?.let {
                Regex("""(?:file|src)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
                    .find(it)?.groupValues?.get(1)
            }
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()

        val subtitleTracks = buildList {
            val subUrl = httpUrl.queryParameter("sub.info")
                ?: unpacked.substringAfter("fetch('", "")
                    .substringBefore("').")
                    .takeIf(String::isNotBlank)
            if (subUrl != null) {
                runCatching {
                    client.newCall(GET(subUrl, videoHeaders)).execute()
                        .body.string()
                        .let { json.decodeFromString<List<SubtitleDto>>(it) }
                        .forEach { add(Track(it.file, it.label)) }
                }
            }
        }

        val videoList = playlistUtils.extractFromHls(
            masterUrl,
            subtitleList = subtitleTracks,
            referer = "https://${httpUrl.host}/",
            videoNameGen = { "$prefix$it" },
        )

        val subPref = preferences?.getString(PREF_SUBTITLE_KEY, PREF_SUBTITLE_DEFAULT).orEmpty()
        return videoList.map {
            Video(
                videoUrl = it.videoUrl,
                videoTitle = it.videoTitle,
                audioTracks = it.audioTracks,
                // si subPref está vacío, contains("") siempre es true → se incluyen todos
                subtitleTracks = it.subtitleTracks.filter { track ->
                    track.lang.contains(subPref, ignoreCase = true)
                },
            )
        }
    }

    @Serializable
    data class SubtitleDto(val file: String, val label: String)

    companion object {
        fun addSubtitlePref(screen: PreferenceScreen) {
            EditTextPreference(screen.context).apply {
                key = PREF_SUBTITLE_KEY
                title = "Filemoon subtitle preference"
                summary = "Leave blank to use all subs"
                setDefaultValue(PREF_SUBTITLE_DEFAULT)
            }.also(screen::addPreference)
        }

        private const val PREF_SUBTITLE_KEY = "pref_filemoon_sub_lang_key"
        private const val PREF_SUBTITLE_DEFAULT = "eng"
    }
}
