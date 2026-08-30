// ! Bu araç NeO tarafından yazılmıştır.
// ! hdfilmizle.vip canlı sitesi üzerinde 2026-08 itibarıyla doğrulanmıştır. Hazır referans eklenti
// ! bulunmadığından seçiciler canlı site incelemesiyle sıfırdan çıkarılmıştır.
// ! - Film kartları "a.poster[title][href]" ile bulunur; dizi linkleri "/dizi/{slug}/" öneki taşır,
// !   filmler kök dizinde "/{slug}/" şeklindedir.
// ! - Sayfalama: "{kategoriUrl}page/{n}/" (WordPress standardı).
// ! - Arama: POST "/search/" (form: query=<sorgu>, header: X-Requested-With: XMLHttpRequest) düz
// !   JSON dizisi döner: [{id,name,slug,year,type("dizi"|"film"),thumb_url,...}].
// ! - Dizi bölümleri tek sayfada "div.card-list a[href*=/bolum-]" ile, href örneği:
// !   "/dizi/{slug}/sezon-{n}/bolum-{m}/".
// ! - Video kaynakları AJAX DEĞİL, sayfanın kendi içine gömülü bir "let parts = [...]" JS dizisinde
// !   sunucu tarafından hazır JSON olarak gelir (id, video_id, episode_id, name, lang, data). "data"
// !   alanı ya tam bir "<iframe src=\"...\">" HTML'i ya da düz bir URL'dir (site kodundaki lazyifr()
// !   fonksiyonuyla aynı mantık burada da uygulanır).
// ! - NOT: "vidrame.pro/vr/{hash}" gibi bazı kaynak URL'leri sunucu tarafında yalnızca gerçek iframe
// !   navigasyonlarını kabul ediyor gibi görünüyor (düz fetch/XHR isteği 404 döndü); CloudStream'in
// !   HTTP istemcisiyle bu kaynaklardan bazıları çalışmayabilir. loadExtractor() yine de denenir.

package com.neo.hdfilmizle

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class HDFilmizleSearchItem(
    val id: Int?            = null,
    val name: String?       = null,
    val slug: String?       = null,
    val year: Int?          = null,
    val type: String?       = null,
    val thumb_url: String?  = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HDFilmizlePart(
    val id: Int?            = null,
    val video_id: Int?      = null,
    val episode_id: Int?    = null,
    val name: String?       = null,
    val lang: String?       = null,
    val data: String?       = null,
)

class HDFilmizle : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("hdfilmizle", "https://www.hdfilmizle.vip")
    override var name                 = "HDFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl                                          to "HD Filmler",
        "$mainUrl/film-robotu/"                          to "Keşfet",
        "$mainUrl/yabanci-dizi-izle-3/"                  to "Diziler",
        "$mainUrl/en-cok-izlenen-filmler-hd-2/"           to "En Çok İzlenenler",
        "$mainUrl/imdb-puani-yuksek-500/"                 to "IMDb 500",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/${page}/"
        val document = app.get(url).document

        val home = document.select("a.poster[title][href]").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href  = fixUrlNull(this.attr("href")) ?: return null
        val title = this.attr("title").ifBlank { null }
            ?: this.selectFirst(".poster-title, h2.title")?.text()?.trim()
        if (title.isNullOrBlank()) return null

        val img       = this.selectFirst(".poster-image img") ?: this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        val year      = this.selectFirst(".poster-year")?.text()?.trim()?.take(4)?.toIntOrNull()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year      = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year      = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/search/",
            data    = mapOf("query" to query),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val items = AppUtils.tryParseJson<List<HDFilmizleSearchItem>>(response) ?: return emptyList()

        return items.mapNotNull { item ->
            val slug = item.slug ?: return@mapNotNull null
            val name = item.name ?: return@mapNotNull null

            val isSeries = item.type == "dizi"
            val href     = if (isSeries) "$mainUrl/dizi/$slug/" else "$mainUrl/$slug/"
            val poster   = fixUrlNull(item.thumb_url)

            if (isSeries) {
                newTvSeriesSearchResponse(name, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year      = item.year
                }
            } else {
                newMovieSearchResponse(name, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year      = item.year
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isSeries = url.contains("/dizi/")

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title    = rawTitle.replace(Regex("""\(\d{4}\)\s*$"""), "").trim().ifBlank { rawTitle }
        val year     = Regex("""\((\d{4})\)\s*$""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val score = document.selectFirst(".rate span")?.text()?.trim()?.toDoubleOrNull()?.let { Score.from10(it) }

        val poster = fixUrlNull(
            document.selectFirst("img[src*=/poster/]")?.attr("src")?.ifBlank { null }
                ?: document.selectFirst("img[data-src*=/poster/]")?.attr("data-src")
        )

        val description = document.selectFirst("article.text-white p")?.text()?.trim()
            ?: document.selectFirst("article.text-white")?.text()?.trim()

        val tags = document.select("div.genres a").map { it.text().trim() }

        if (isSeries) {
            val episodeRegex = Regex("""/sezon-(\d+)/bolum-(\d+)""")

            val episodes = document.select("div.card-list a[href*=/bolum-]").mapNotNull { epEl ->
                val epHref = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null
                val match  = episodeRegex.find(epHref) ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: 1
                val epNum  = match.groupValues[2].toIntOrNull()

                newEpisode(epHref) {
                    this.season  = season
                    this.episode = epNum
                }
            }

            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.score     = score
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = score
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        val scriptText = document.select("script:not([src])").map { it.data() }
            .firstOrNull { it.contains("let parts") } ?: return false

        val partsJson = Regex("""let parts\s*=\s*(\[.+?\]);""", RegexOption.DOT_MATCHES_ALL)
            .find(scriptText)?.groupValues?.get(1) ?: return false

        val parts = AppUtils.tryParseJson<List<HDFilmizlePart>>(partsJson) ?: return false
        if (parts.isEmpty()) return false

        var found = false

        for (part in parts) {
            val raw = part.data?.trim()
            if (raw.isNullOrBlank()) continue

            val videoUrl = if (raw.contains("iframe")) {
                Regex("""src="([^"]+)""").find(raw)?.groupValues?.get(1)
            } else {
                raw
            } ?: continue

            loadExtractor(fixUrl(videoUrl), data, subtitleCallback, callback)
            found = true
        }

        return found
    }
}
