package com.neo.dizizon

import android.util.Log
import org.jsoup.nodes.Element
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
        "${mainUrl}/arsiv"  to "Tüm Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val home = document.select("a[href*='/dizi/']").mapNotNull { el ->
            val href  = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            if (!href.contains("/dizi/") || href.contains("/bolum")) return@mapNotNull null
            val title = el.attr("title")?.replace(" izle", "")?.trim()
                ?: el.selectFirst("span.title")?.text()?.trim()
                ?: el.text().trim()
            if (title.isBlank() || title.length < 2) return@mapNotNull null
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arsiv?q=${query}").document

        return document.select("a[href*='/dizi/']").mapNotNull { el ->
            val href  = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            if (!href.contains("/dizi/") || href.contains("/bolum")) return@mapNotNull null
            val title = el.attr("title")?.replace(" izle", "")?.trim()
                ?: el.selectFirst("span.title")?.text()?.trim()
                ?: el.text().trim()
            if (title.isBlank() || title.length < 2) return@mapNotNull null
            val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("img[src*='_cover.png']")?.attr("src")
                ?: document.selectFirst("img[src*='_poster.png']")?.attr("src")
        )
        val description = document.selectFirst("p")?.text()?.takeIf { it.length > 30 }
        val year = Regex("""(\d{4})""").find(
            document.selectFirst(".year, .yapim-yili")?.text() ?: ""
        )?.groupValues?.get(1)?.toIntOrNull()
        val tags = document.select("a[href*='/tur/'], a[href*='/kategori/']").map { it.text().trim() }

        val episodes = document.select("a.episode").mapNotNull { el ->
            val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
            val epText = el.text().trim()
            val epNum  = Regex("""(\d+)\.\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()

            // Sezon numarasını URL'den çıkar
            val seasonNum = Regex("""/sezon-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epText
                this.season  = seasonNum
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
        }
    }

    // ! DizizOn'un player'ı JS ile dinamik yükleniyor (iframe#episode_player boş geliyor,
    // ! "Videoyu Başlat" tıklanınca client-side JS ile dolduruluyor). CloudStream'in Jsoup/OkHttp
    // ! mimarisi JS çalıştıramadığı için şu an video linki çıkarılamıyor.
    // ! İleride network analizi ile API endpoint'i bulunursa burası güncellenecek.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZZN", "data » $data")
        val document = app.get(data).document

        // iframe#episode_player src'si JS ile dolduruluyor, statik HTML'de boş
        val iframeSrc = document.selectFirst("iframe#episode_player")?.attr("src")
            ?.takeIf { it.isNotBlank() && it != "about:blank" }

        if (iframeSrc != null) {
            Log.d("DZZN", "iframe » $iframeSrc")
            loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)
            return true
        }

        // Yedek: sayfada gömülü m3u8/mp4 linki arama
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

        Log.d("DZZN", "video linki bulunamadı - player JS ile dinamik yükleniyor")
        return false
    }
}
