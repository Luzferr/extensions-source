package eu.kanade.tachiyomi.lib.pixeldrainextractor

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.OkHttpClient

class PixelDrainExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val mId = Regex("/u/(.*)").find(url)?.groupValues?.get(1)
        return if (mId.isNullOrEmpty()) {
            listOf(Video(videoUrl = url, videoTitle = "${prefix}PixelDrain"))
        } else {
            val downloadUrl = "https://pixeldrain.net/api/file/$mId?download"
            listOf(Video(videoUrl = downloadUrl, videoTitle = "${prefix}PixelDrain"))
        }
    }
}
