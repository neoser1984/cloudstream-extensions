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
        val results = mutableListOf<SearchResponse>()

        for (section in listOf("diziler", "filmler")) {
            val document = app.get("${mainUrl}/${section}?search=${query}").document
            document.select("a[href*='/${section}/']").forEach { el ->
                val href  = fixUrlNull(el.attr("href")) ?: return@forEach
                val title = el.selectFirst("h3, h4, span.title, strong")?.text()?.trim()
                    ?: el.attr("title")?.replace(" izle", "")?.trim()
                    ?: return@forEach
                val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

                if (section == "filmler") {
                    results.add(newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
                } else {
                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster })
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.replace(" izle", "")?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
        val tags = document.select("a[href*='/tur/']").map { it.text().trim() }

        return if (url.contains("/diziler/")) {
            val episodes = document.select("a[href*='/bolumler/']").mapNotNull { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epText = el.text().trim()
                val season = Regex("""(\d+)\.\s*Sezon""").find(epText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(\d+)\.\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                val epName = Regex("""Bölüm\s*(.+?)(?:\s*\d+\s*\w+\s*\d+)?$""").find(epText)?.groupValues?.get(1)?.trim()

                newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DPO", "data » $data")
        val document = app.get(data).document

        // 1. meta-og:video — streamcorecdn embed URL'sini doğrudan al
        val embedUrl = document.selectFirst("meta[property='og:video']")?.attr("content")
            ?: document.selectFirst("meta[property='og:video:secure_url']")?.attr("content")

        if (embedUrl.isNullOrBlank()) {
            Log.d("DPO", "og:video bulunamadı")
            return false
        }

        Log.d("DPO", "embedUrl » $embedUrl")

        // 2. Embed sayfasını tarayıcı gibi header'larla çek
        try {
            val embedResp = app.get(
                embedUrl,
                referer = "${mainUrl}/",
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
                    "Sec-Fetch-Dest" to "iframe",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "cross-site"
                )
            ).text

            Log.d("DPO", "embed response length » ${embedResp.length}")

            // m3u8 linkleri ara
            val m3u8 = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""").find(embedResp)?.groupValues?.get(1)
            // file:"..." deseni (JWPlayer)
            val fileField = Regex("""file\s*:\s*["']([^"']+)""").find(embedResp)?.groupValues?.get(1)
            // source src="..." deseni
            val sourceSrc = Regex("""source\s+src=["']([^"']+)""").find(embedResp)?.groupValues?.get(1)
            // mp4 linki
            val mp4 = Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""").find(embedResp)?.groupValues?.get(1)

            val videoUrl = m3u8 ?: fileField ?: sourceSrc ?: mp4

            if (videoUrl != null) {
                Log.d("DPO", "videoUrl » $videoUrl")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {
            Log.d("DPO", "embed fetch hatası » ${e.message}")
        }

        // 3. loadExtractor dene (belki CloudStream tanır)
        try {
            loadExtractor(embedUrl, "${mainUrl}/", subtitleCallback, callback)
        } catch (e: Exception) {
            Log.d("DPO", "loadExtractor hatası » ${e.message}")
        }

        // 4. Son çare — embed URL'yi doğrudan video olarak ver
        // CloudStream'in player'ı yönlendirmeleri takip edebilir
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "${this.name} (Embed)",
                url = embedUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "${mainUrl}/"
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
