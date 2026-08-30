// ! Bu araç NeO tarafından yazılmıştır.
// ! tvdiziler.tv canlı sitesi üzerinde 2026-08 itibarıyla doğrulanmıştır. Hazır referans eklenti
// ! bulunmadığından seçiciler canlı site incelemesiyle sıfırdan çıkarılmıştır.
// ! - Ana sayfa/Keşfet/Dizi Arşivi kartları ya doğrudan BÖLÜM sayfasına ya da "dizi/..." dizi
// !   sayfasına gider. Bölüm sayfasından dizi sayfasına "a[href^=dizi/]" (tur linkleri hariç) ile ulaşılır.
// ! - Dizi sayfasında bölümler schema.org microdata ("[itemprop=episode]") ile, sezon sekmeleri
// !   "li[data-season]" + "#{data-season}" alanlarıyla listelenir.
// ! - Bölüm izleme sayfasında ".series-watch-alternatives button[data-hhs]" içinde kaynak seçenekleri
// !   var; "Fragman" olmayan seçenek gerçek bölüm videosudur. Kaynak ya "/vid/kapat/?git=<url>"
// !   (harici - örn. YouTube) ya da "/vid/ply/<hash>" (dahili JWPlayer, "sources:[{file:"..."}]"
// !   içinde düz mp4/m3u8 linki barındırır).
// ! - Arama: POST "/search?qr=<sorgu>" (header: X-Requested-With: XMLHttpRequest) JSON
// !   {success, data:"<html>"} döner.

package com.neo.tvdiziler

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TvDizilerSearchResponse(
    val success: Int?    = null,
    val data: String?    = null,
)

class TvDiziler : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("tvdiziler", "https://tvdiziler.tv")
    override var name                 = "TvDiziler"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl                      to "Son Bölümler",
        "${mainUrl}/kesfet"          to "Keşfet",
        "${mainUrl}/dizi-izle"       to "Tüm Diziler",
    )

    private fun isContentLink(el: Element): Boolean {
        val href = el.attr("href")
        if (href.isBlank() || href == "/") return false
        if (href.startsWith("profile/") || href.startsWith("tartisma/") || href.startsWith("dizi/tur/")) return false
        return el.selectFirst("img") != null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isPaginated = request.data == "${mainUrl}/kesfet"

        if (!isPaginated && page > 1) {
            return newHomePageResponse(request.name, emptyList())
        }

        val url = if (isPaginated && page > 1) "${request.data}/${page}" else request.data
        val document = app.get(url).document

        val home = document.select("a[data-navigo]").filter { isContentLink(it) }.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val nameText  = this.selectFirst("h2[itemprop=name]")?.text()?.trim()
        val altText   = img?.attr("alt")?.trim()
        val title     = (nameText?.ifBlank { null } ?: altText?.ifBlank { null } ?: this.text().trim())

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "${mainUrl}/search",
            params  = mapOf("qr" to query),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val parsed = AppUtils.tryParseJson<TvDizilerSearchResponse>(response) ?: return emptyList()
        val doc    = Jsoup.parse(parsed.data ?: "")

        return doc.select("a[data-navigo]").filter { isContentLink(it) }.mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var seriesUrl = url
        var document  = app.get(url).document

        if (!url.contains("/dizi/")) {
            val resolved = document.select("a[href^=dizi/]").firstOrNull { !it.attr("href").startsWith("dizi/tur/") }
            val resolvedUrl = resolved?.let { fixUrlNull(it.attr("href")) }
            if (resolvedUrl != null) {
                seriesUrl = resolvedUrl
                document  = app.get(seriesUrl).document
            }
        }

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title    = rawTitle.replace(Regex("""\(\d{4}\)\s*$"""), "").trim().ifBlank { rawTitle }
        val year     = Regex("""\((\d{4})\)\s*$""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val poster      = fixUrlNull(document.selectFirst(".series-profile-image img")?.attr("data-src")?.ifBlank { null }
            ?: document.selectFirst(".series-profile-image img")?.attr("src"))
        val description = document.selectFirst("[itemprop=description]")?.text()?.trim()

        val tags = document.select("span.block a[href^=dizi/tur/]").map { it.text().trim() }

        val seasonTabs  = document.select("li[data-season]")
        val seasonAreas = if (seasonTabs.isNotEmpty()) {
            seasonTabs.mapNotNull { tab ->
                val num    = tab.attr("data-num").toIntOrNull() ?: 1
                val areaId = tab.attr("data-season")
                val area   = if (areaId.isNotBlank()) document.selectFirst("#${areaId}") else null
                if (area != null) num to area else null
            }
        } else {
            listOf(1 to document)
        }

        val episodes = seasonAreas.flatMap { (seasonNum, area) ->
            area.select("[itemprop=episode]").mapNotNull { epEl ->
                val nameLink = epEl.selectFirst("a[itemprop=name]") ?: return@mapNotNull null
                val epHref   = fixUrlNull(nameLink.attr("href")) ?: return@mapNotNull null
                val epNum    = epEl.selectFirst("[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = nameLink.text().trim()
                    this.season  = seasonNum
                    this.episode = epNum
                }
            }
        }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        val candidates = document.select(".series-watch-alternatives button[data-hhs]")
            .filter { !it.text().trim().equals("Fragman", ignoreCase = true) }

        if (candidates.isEmpty()) return false

        var found = false

        for (btn in candidates) {
            val hhs = btn.attr("data-hhs")
            if (hhs.isBlank()) continue

            if (hhs.contains("/vid/kapat/")) {
                val externalUrl = hhs.substringAfter("git=", "")
                if (externalUrl.isNotBlank()) {
                    loadExtractor(externalUrl, data, subtitleCallback, callback)
                    found = true
                }
                continue
            }

            val playerUrl  = fixUrl(hhs)
            val playerHtml = app.get(playerUrl, referer = fixUrl(data)).text
            val fileUrl    = Regex("""sources:\s*\[\{file:"([^"]+)""").find(playerHtml)?.groupValues?.get(1) ?: continue

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = fileUrl,
                    type   = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = playerUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        return found
    }
}
