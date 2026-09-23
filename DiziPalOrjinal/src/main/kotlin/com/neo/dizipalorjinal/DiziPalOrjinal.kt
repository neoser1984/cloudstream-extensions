package com.neo.dizipalorjinal

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class DiziPalOrjinal : MainAPI() {
    override var mainUrl              = "https://dizipalorjinal9.com"
    override var name                 = "DiziPal Orjinal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler"                     to "Son Eklenen Diziler",
        "${mainUrl}/filmler"                     to "Yeni Filmler",
        "${mainUrl}/populer"                     to "Popüler Filmler",
        "${mainUrl}/bolumler"                    to "Yeni Bölümler",
        "${mainUrl}/diziler?sort=popular"         to "Popüler Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}${if (request.data.contains("?")) "&" else "?"}page=${page}" else request.data
        val document = app.get(url).document

        val home = document.select("a[href*='/diziler/'], a[href*='/filmler/']").mapNotNull { el ->
            val href  = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val title = el.selectFirst("h3, h4, span.title, strong")?.text()?.trim()
                ?: el.attr("title")?.replace(" izle", "")?.trim()
                ?: el.text().trim().takeIf { it.length in 2..80 }
                ?: return@mapNotNull null
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            if (href.contains("/filmler/")) {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/diziler?search=${query}").document
        val results = mutableListOf<SearchResponse>()

        document.select("a[href*='/diziler/'], a[href*='/filmler/']").forEach { el ->
            val href  = fixUrlNull(el.attr("href")) ?: return@forEach
            val title = el.selectFirst("h3, h4, span.title, strong")?.text()?.trim()
                ?: el.attr("title")?.replace(" izle", "")?.trim()
                ?: return@forEach
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            if (href.contains("/filmler/")) {
                results.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
            } else {
                results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
            }
        }

        if (results.isEmpty()) {
            val doc2 = app.get("${mainUrl}/filmler?search=${query}").document
            doc2.select("a[href*='/filmler/']").forEach { el ->
                val href  = fixUrlNull(el.attr("href")) ?: return@forEach
                val title = el.selectFirst("h3, h4, span.title, strong")?.text()?.trim()
                    ?: el.attr("title")?.replace(" izle", "")?.trim()
                    ?: return@forEach
                val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))
                results.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("meta[name='description']")?.attr("content")

        val year = Regex("""(\d{4})""").find(
            document.select("td, span, div").firstOrNull { it.text().matches(Regex(".*\\b(19|20)\\d{2}\\b.*")) }?.text() ?: ""
        )?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }

        return if (url.contains("/diziler/")) {
            val episodes = document.select("a[href*='/bolumler/']").mapNotNull { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epText = el.text().trim()
                val season = Regex("""(\d+)\.\s*Sezon""").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(\d+)\.\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val epName = Regex("""Bölüm(.+)""").find(epText)?.groupValues?.get(1)?.trim()

                newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DPO", "data » $data")
        val document = app.get(data).document

        // meta-og:video tag'ından player URL'sini çek (en güvenilir yöntem)
        val playerUrl = document.selectFirst("meta[property='og:video']")?.attr("content")
            ?: document.selectFirst("meta[property='og:video:secure_url']")?.attr("content")

        if (playerUrl != null && playerUrl.isNotBlank()) {
            Log.d("DPO", "playerUrl (og:video) » $playerUrl")
            // streamcorecdn iframe'ini aç ve m3u8/mp4 linkini çıkar
            try {
                val playerDoc = app.get(playerUrl, referer = "${mainUrl}/").text
                val m3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(playerDoc)?.groupValues?.get(1)
                val mp4 = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""").find(playerDoc)?.groupValues?.get(1)
                val file = Regex("""file\s*:\s*["']([^"']+)""").find(playerDoc)?.groupValues?.get(1)
                val videoUrl = m3u8 ?: file ?: mp4

                if (videoUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = playerUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                Log.d("DPO", "player hatası » ${e.message}")
            }

            // Doğrudan loadExtractor dene
            loadExtractor(playerUrl, "${mainUrl}/", subtitleCallback, callback)
            return true
        }

        // Yedek: sayfadaki iframe'leri tara
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() && !it.contains("about:blank") } ?: return@forEach
            Log.d("DPO", "iframe » $src")
            loadExtractor(fixUrl(src), "${mainUrl}/", subtitleCallback, callback)
        }

        return true
    }
}
