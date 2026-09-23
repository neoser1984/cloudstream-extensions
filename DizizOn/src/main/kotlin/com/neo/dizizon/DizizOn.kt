package com.neo.dizizon

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class DizizOn : MainAPI() {
    override var mainUrl              = "https://www.dizizon.com"
    override var name                 = "DizizOn"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/"      to "Son Eklenen Bölümler",
    )

    // Ana sayfa: son eklenen bölümlerin dizi linklerini çek
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val home = document.select("a[href*='/dizi/'][href*='/bolum']").mapNotNull { el ->
            val href  = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            // Bölüm linkinden dizi linkini türet: /dizi/slug/sezon-X/bolum-Y -> /dizi/slug
            val diziHref = Regex("""(/dizi/[^/]+)""").find(href)?.groupValues?.get(1)
                ?.let { "${mainUrl}${it}" } ?: return@mapNotNull null
            val title = el.selectFirst("span.title")?.text()?.trim() ?: return@mapNotNull null
            val subtitle = el.selectFirst("span.alt-title")?.text()?.trim() ?: ""
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse("$title - $subtitle", diziHref, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // DizizOn arama: /arsiv sayfasındaki tüm dizi linkleri arasında filtrele
        val document = app.get("${mainUrl}/arsiv").document

        return document.select("a[href*='/dizi/']").mapNotNull { el ->
            val href  = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            if (href.contains("/bolum") || href.contains("/sezon")) return@mapNotNull null
            val title = el.attr("title")?.replace(" izle", "")?.trim()
                ?: el.text().trim()
            if (title.isBlank() || title.length < 2) return@mapNotNull null
            if (!title.contains(query, ignoreCase = true)) return@mapNotNull null

            newTvSeriesSearchResponse(title, href, TvType.TvSeries)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("img[src*='_cover.png']")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("meta[name='description']")?.attr("content")

        val episodes = document.select("a.episode").mapNotNull { el ->
            val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epText = el.text().trim()
            val epNum  = Regex("""(\d+)\.\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
            val seasonNum = Regex("""/sezon-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epText
                this.season  = seasonNum
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    // Video player JS ile dinamik yükleniyor - en iyi çaba ile dene
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZZN", "data » $data")
        val document = app.get(data).document

        // 1. meta-og:video tag'ından player URL
        val ogVideo = document.selectFirst("meta[property='og:video']")?.attr("content")
            ?: document.selectFirst("meta[property='og:video:secure_url']")?.attr("content")

        if (ogVideo != null && ogVideo.isNotBlank()) {
            Log.d("DZZN", "og:video » $ogVideo")
            loadExtractor(ogVideo, "${mainUrl}/", subtitleCallback, callback)
            return true
        }

        // 2. iframe#episode_player
        val iframeSrc = document.selectFirst("iframe#episode_player")?.attr("src")
            ?.takeIf { it.isNotBlank() && it != "about:blank" }

        if (iframeSrc != null) {
            Log.d("DZZN", "iframe » $iframeSrc")
            loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)
            return true
        }

        // 3. Sayfada gömülü m3u8/mp4
        val pageHtml = document.html()
        val m3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(pageHtml)?.groupValues?.get(1)
        val mp4  = Regex("""(https?://[^\s"']+\.mp4[^\s"']*)""").find(pageHtml)?.groupValues?.get(1)
        val videoUrl = m3u8 ?: mp4

        if (videoUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = videoUrl,
                    type   = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "${mainUrl}/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        Log.d("DZZN", "video linki bulunamadı")
        return false
    }
}
