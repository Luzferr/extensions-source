package eu.kanade.tachiyomi.animeextension.all.debridindex

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import okhttp3.Request
import okhttp3.Response

class AnimeCore : AnimeHttpSource() {
    override val id: Long = 9099608567050495800L

    override val name = "AnimeCore"

    override val baseUrl = "https://animecore.to"

    override val lang = "pt-BR"

    override val supportsLatest = false

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create()

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    override fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override fun hosterListParse(response: Response): List<Hoster> = emptyList()

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
