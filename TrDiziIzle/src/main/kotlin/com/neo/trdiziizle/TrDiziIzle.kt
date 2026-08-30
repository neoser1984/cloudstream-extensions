// ! Bu araç NeO tarafından yazılmıştır.
// ! trdiziizle.tv canlı sitesi üzerinde 2026-08 itibarıyla doğrulanmıştır. Hazır referans eklenti
// ! bulunmadığından seçiciler canlı site incelemesiyle sıfırdan çıkarılmıştır.
// ! - Anasayfa "/tr1/" adresinde, kart yapısı "div#list-series-main" (".cat-img-main img" +
// !   ".cat-title-main a"). Dizi arşivi "/dizi-arsivi-01/" tek sayfada ~1300 dizi linki barındırır
// !   (sayfalama yok, sadece düz "a[href*=/diziler/]" linkleri, posters içermez).
// ! - Sayfalama: "{kategoriUrl}page/{n}/" (WordPress standardı, sadece "/tr1/" için geçerli).
// ! - Arama: GET "/?s=<sorgu>" (WordPress arama). Sonuç kutusu "div.cat-container" (".cat-title a"),
// !   posteri ise bir önceki kardeş eleman olan "div.cat-img img" içindedir.
// ! - Dizi sayfasında TÜM sezon/bölümler tek sayfada "div.bolumust a[href]" içinde listelenir
// !   (href örn: "{slug}-{sezon}-sezon-{bölüm}-bolum-izle-N" veya tek sezonlu diziler için
// !   "{slug}-{bölüm}-bolum-izle").
// ! - Bazı anasayfa/arşiv dışı linkler (örn. "Son Eklenen Bölümler" widget'ları) doğrudan BÖLÜM
// !   sayfasına gidebilir; bu durumda load() içinde dizi sayfasına, bölüm slug'ının "sezon/bölüm"
// !   ekini kırpıp elde edilen taban slug'a göre "a[href*=/diziler/]" linkleri arasından eşleşen
// !   ilk linkle geri dönülür (kalıcı bir "dizi sayfasına git" linki JS ile eklenmiş görünüyor ve
// !   statik HTML'de bulunamadı).
// ! - Video kaynağı: bölüm sayfasındaki tek "<iframe src=\"/player/oynat/<hash>\">" aynı origin'den
// !   servis edilir; bu sayfa çıplak JWPlayer "sources: [{file:\"<m3u8>\"...}]" döner (obfuscation yok).

package com.neo.trdiziizle

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class TrDiziIzle : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("trdiziizle", "https://www.trdiziizle.tv")
    override var name                 = "TrDiziIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/tr1/"          to "Son Eklenenler",
        "$mainUrl/dizi-arsivi-01/" to "Dizi Arşivi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isArchive = request.data.contains("dizi-arsivi")

        if (isArchive) {
            if (page > 1) return newHomePageResponse(request.name, emptyList())

            val document = app.get(request.data).document
            val home = document.select("a[href*=/diziler/]").mapNotNull { a ->
                val href  = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val title = a.text().trim().removeSuffix("izle").trim().ifBlank { a.text().trim() }
                if (title.isBlank()) return@mapNotNull null

                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {}
            }

            return newHomePageResponse(request.name, home)
        }

        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/${page}/"
        val document = app.get(url).document

        val home = document.select("div#list-series-main").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst(".cat-title-main a") ?: return null
        val href    = fixUrlNull(titleEl.attr("href")) ?: return null
        val title   = titleEl.text().trim()
        if (title.isBlank()) return null

        val img       = this.selectFirst(".cat-img-main img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/", params = mapOf("s" to query)).document

        return document.select("div.cat-container").mapNotNull { container ->
            val titleEl = container.selectFirst(".cat-title a") ?: return@mapNotNull null
            val href    = fixUrlNull(titleEl.attr("href")) ?: return@mapNotNull null
            val title   = titleEl.text().trim().removeSuffix("izle").trim().ifBlank { titleEl.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            val img       = container.previousElementSibling()?.selectFirst("img")
            val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var seriesUrl = url
        var document  = app.get(url).document

        if (!url.contains("/diziler/")) {
            val episodeSlug = url.trimEnd('/').substringAfterLast('/')
            val baseSlug = episodeSlug
                .replace(Regex("""-\d+-sezon-\d+-bolum-izle.*$"""), "")
                .replace(Regex("""-\d+-bolum-izle.*$"""), "")

            if (baseSlug.isNotBlank() && baseSlug != episodeSlug) {
                val resolved = document.select("a[href*=/diziler/]").firstOrNull { a ->
                    val slug = fixUrlNull(a.attr("href"))?.trimEnd('/')?.substringAfterLast('/')
                    slug != null && slug.startsWith(baseSlug)
                }?.let { fixUrlNull(it.attr("href")) }

                if (resolved != null) {
                    seriesUrl = resolved
                    document  = app.get(seriesUrl).document
                }
            }
        }

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title = rawTitle
            .substringBefore("| Trdiziizle")
            .substringBefore("Son Bölüm izle")
            .trim()
            .removeSuffix("izle")
            .trim()
            .ifBlank { rawTitle }

        val poster = fixUrlNull(
            document.selectFirst(".category_image img")?.attr("data-src")?.ifBlank { null }
                ?: document.selectFirst(".category_image img")?.attr("src")
        )

        val description = document.selectFirst("#icerikcatright")?.ownText()?.trim()?.ifBlank { null }

        val metaText = document.selectFirst("#icerikcat2")?.text() ?: ""
        val year = Regex("""Yapım Yılı\s*:\s*(\d{4})""").find(metaText)?.groupValues?.get(1)?.toIntOrNull()
        val tags = Regex("""Tür\s*:\s*([^×]+)""").find(metaText)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

        val seasonRegex = Regex("""-(\d+)-sezon-(\d+)-bolum-izle""")
        val flatRegex    = Regex("""-(\d+)-bolum-izle""")

        val episodes = document.select("div.bolumust a[href]").mapNotNull { epEl ->
            val epHref = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null

            val seasonMatch = seasonRegex.find(epHref)
            val season: Int
            val epNum: Int?

            if (seasonMatch != null) {
                season = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                epNum  = seasonMatch.groupValues[2].toIntOrNull()
            } else {
                val flatMatch = flatRegex.find(epHref) ?: return@mapNotNull null
                season = 1
                epNum  = flatMatch.groupValues[1].toIntOrNull()
            }

            newEpisode(epHref) {
                this.season  = season
                this.episode = epNum
                this.name    = epEl.text().trim().ifBlank { null }
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document   = app.get(data).document
        val iframeSrc  = document.selectFirst("iframe")?.attr("src") ?: return false
        val playerUrl  = fixUrl(iframeSrc)
        val playerHtml = app.get(playerUrl, referer = data).text
        val fileUrl    = Regex("""sources:\s*\[\{file:"([^"]+)""").find(playerHtml)?.groupValues?.get(1) ?: return false

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

        return true
    }
}
