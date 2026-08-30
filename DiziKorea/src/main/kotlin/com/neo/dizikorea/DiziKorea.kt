// ! Bu araç NeO tarafından yazılmıştır.
// ! dizikorea3.com canlı sitesi üzerinde 2026-08 itibarıyla doğrulanmıştır.
// ! Not: Bu site için hazır bir referans eklenti bulunmadığından tüm seçiciler
// ! canlı site incelemesiyle sıfırdan çıkarılmıştır.
// ! - Arama, HTML değil doğrudan JSON döndüren "/ara?q=" uç noktası üzerinden yapılır.
// ! - Film sayfaları (/film/...) ile dizi sayfaları (/dizi/...) farklı şablonlar kullanır.
// ! - Bölüm/film oynatma sayfalarında "div.player-source iframe[data-src]" içinde
// !   filemoon, vidmoly ve özel (VIP) kaynaklar bulunur; VIP kaynağı desteklenmez.

package com.neo.dizikorea

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class DiziKoreaSearchItem(
    val id: Int?             = null,
    val title: String?       = null,
    val slug: String?        = null,
    val poster: String?      = null,
    val year: Int?           = null,
    val description: String? = null,
    val imdb_rating: String? = null,
    val type: String?        = null,
    val type_label: String?  = null,
    val url: String?         = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DiziKoreaSearchResponse(
    val success: Boolean?             = null,
    val items: List<DiziKoreaSearchItem>? = null,
)

class DiziKorea : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("dizikorea", "https://dizikorea3.com")
    override var name                 = "DiziKorea"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.AsianDrama, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/kore-dizileri-izle-dq1" to "Kore Dizileri",
        "${mainUrl}/cin-dizileri"           to "Çin Dizileri",
        "${mainUrl}/japon-dizileri"         to "Japon Dizileri",
        "${mainUrl}/tayland-dizileri"       to "Tayland Dizileri",
        "${mainUrl}/tayvan-dizileri"        to "Tayvan Dizileri",
        "${mainUrl}/filipin-dizileri"       to "Filipin Dizileri",
        "${mainUrl}/filmler"                to "Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = "${request.data}/sayfa/${page}"
        val document = app.get(url).document
        val home     = document.select("a.poster-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val title     = this.selectFirst("span.poster-card-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("${mainUrl}/ara", params = mapOf("q" to query)).text
        val parsed   = AppUtils.tryParseJson<DiziKoreaSearchResponse>(response)

        return parsed?.items?.mapNotNull { item ->
            val title     = item.title ?: return@mapNotNull null
            val url       = item.url ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.poster)

            if (item.type == "movie") {
                newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = posterUrl }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.AsianDrama) { this.posterUrl = posterUrl }
            }
        } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie  = url.contains("/film/")

        val title       = document.selectFirst("h1.series-title, h1.watch-title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.series-hero-poster img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("img.sidebar-poster")?.attr("src"))
        val description = document.selectFirst(".series-about-text")?.text()?.trim()

        val metaBadges = document.select("span.meta-badge")
        val ratingText = document.selectFirst("span.meta-badge.meta-rating")?.text()?.trim()
        val score      = ratingText?.substringAfter("★")?.trim()?.toDoubleOrNull()?.let { Score.from10(it) }

        val year = metaBadges.firstOrNull { Regex("""^\d{4}$""").matches(it.text().trim()) }?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(document.title())?.groupValues?.get(1)?.toIntOrNull()

        val tags    = document.select("a.meta-badge").map { it.text().trim() }
        val trailer = document.selectFirst("button.btn-trailer")?.attr("data-trailer")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.score     = score
                addTrailer(trailer)
            }
        } else {
            val episodes = document.select("a.episode-item").mapNotNull { el ->
                val epHref    = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epSeason  = Regex("""/sezon-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""/bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val epTitle   = el.selectFirst("span.ep-title")?.text()?.trim()

                newEpisode(epHref) {
                    this.name    = epTitle
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.score     = score
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val iframes  = document.select("div.player-source iframe").mapNotNull { fixUrlNull(it.attr("data-src")) }

        if (iframes.isEmpty()) return false

        for (iframe in iframes) {
            loadExtractor(iframe, data, subtitleCallback, callback)
        }

        return true
    }
}
