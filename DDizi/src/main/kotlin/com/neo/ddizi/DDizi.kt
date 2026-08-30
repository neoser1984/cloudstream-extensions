// ! Bu araç NeO tarafından yazılmıştır.
// ! ddizi.im canlı sitesi üzerinde 2026-08 itibarıyla doğrulanmıştır. Hazır referans eklenti
// ! bulunmadığından seçiciler canlı site incelemesiyle sıfırdan çıkarılmıştır.
// ! - Ana sayfa "son bölümler" akışıdır ve 2.sayfadan itibaren "/l.php?sayfa=N" AJAX uç noktasından gelir.
// ! - Diğer kategori sayfaları (yabancı diziler, eski diziler, yeni eklenenler) doğrudan HTML döner.
// ! - Ana sayfa/kategori kartları doğrudan BÖLÜM (izle) sayfasına gider; dizi sayfasına ulaşmak için
// !   bölüm sayfasındaki "ul.breadcrumbX" içindeki 2.linke gidilir.
// ! - Dizi sayfasında bölümler "div.dizi-boxpost-cat" ile listelenir, çok sayfalıysa "{url}/sayfa-{n}"
// !   ile devam eder (n=0 ilk sayfa).
// ! - Video, bölüm sayfasındaki iframe'e (youtube olmayan) gidilince JWPlayer kurulum scriptindeki
// !   "sources: [{file:"..."}]" içinde düz m3u8 (hls) linki olarak geliyor.

package com.neo.ddizi

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DDizi : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("ddizi", "https://www.ddizi.im")
    override var name                 = "DDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    private val episodeRegex = Regex("""^(.*?)\s+(?:(\d+)\.Sezon\s+)?(\d+)\.Bölüm""")

    override val mainPage = mainPageOf(
        mainUrl                              to "Son Bölümler",
        "${mainUrl}/yabanci-dizi-izle"       to "Yabancı Diziler",
        "${mainUrl}/eski.diziler"            to "Eski Diziler",
        "${mainUrl}/yeni-eklenenler7"        to "Yeni Eklenenler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = if (request.data == mainUrl) {
            if (page == 1) app.get(mainUrl).document else app.get("${mainUrl}/l.php?sayfa=${page - 1}").document
        } else {
            val url = if (page == 1) request.data else "${request.data}/${page}"
            app.get(url).document
        }

        val home = document.select("div.dizi-boxpost, div.dizi-boxpost-cat").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a         = this.selectFirst("a") ?: return null
        val href      = fixUrlNull(a.attr("href")) ?: return null
        val rawTitle  = a.text().trim()
        val title     = episodeRegex.find(rawTitle)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: rawTitle
        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))

        if (title.isBlank()) return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post("${mainUrl}/arama/", data = mapOf("arama" to query)).document

        return document.select("div.dizi-boxpost-cat, div.dizi-boxpost").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var seriesUrl = url
        var document  = app.get(url).document

        if (url.contains("/izle/")) {
            val resolved = fixUrlNull(document.selectFirst("ul.breadcrumbX li:nth-child(2) a")?.attr("href"))
            if (resolved != null) {
                seriesUrl = resolved
                document  = app.get(seriesUrl).document
            }
        }

        val episodeElements = mutableListOf<Element>()
        episodeElements.addAll(document.select("div.dizi-boxpost-cat"))

        var hasNext = document.selectFirst("ul.pagination a[aria-label=Next]") != null
        var pageIndex = 1
        while (hasNext && pageIndex < 30) {
            val pageDoc = app.get("${seriesUrl}/sayfa-${pageIndex}").document
            val pageEls = pageDoc.select("div.dizi-boxpost-cat")
            if (pageEls.isEmpty()) break
            episodeElements.addAll(pageEls)
            hasNext = pageDoc.selectFirst("ul.pagination a[aria-label=Next]") != null
            pageIndex++
        }

        if (episodeElements.isEmpty()) return null

        val firstText = episodeElements.first().selectFirst("a")?.text()?.trim()
        val title = firstText?.let { episodeRegex.find(it)?.groupValues?.get(1)?.trim()?.ifBlank { null } }
            ?: document.title().substringBefore("|").trim().ifBlank { "DDizi" }

        val firstImg  = episodeElements.first().selectFirst("img")
        val posterUrl = fixUrlNull(firstImg?.attr("data-src")?.ifBlank { null } ?: firstImg?.attr("src"))

        val episodes = episodeElements.mapNotNull { el ->
            val a      = el.selectFirst("a") ?: return@mapNotNull null
            val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val epText = a.text().trim()
            val match  = episodeRegex.find(epText)

            val epSeason  = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
            val epEpisode = match?.groupValues?.get(3)?.toIntOrNull()

            newEpisode(epHref) {
                this.name    = epText
                this.season  = epSeason
                this.episode = epEpisode
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document  = app.get(data).document
        val iframeSrc = document.select("iframe").map { it.attr("src") }.firstOrNull { !it.contains("youtube.com") } ?: return false
        val playerUrl = fixUrl(iframeSrc)

        val playerHtml = app.get(playerUrl, referer = data).text
        val fileUrl    = Regex("""sources:\s*\[\{file:"([^"]+)""").find(playerHtml)?.groupValues?.get(1) ?: return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = name,
                url    = fileUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
