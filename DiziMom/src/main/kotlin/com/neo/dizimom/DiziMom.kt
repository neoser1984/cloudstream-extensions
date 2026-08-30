// ! Bu araç NeO tarafından yazılmıştır.
// ! Kekik-cloudstream/DiziMom tabanlı, domains.json ile adres yönetimi ve güncel Kotlin/CloudStream
// ! API'lerine (Score) uyarlanmıştır. 2026-08 itibarıyla canlı site üzerinde doğrulanmış değişiklikler:
// ! - Oynatıcı iframe'i artık lazy-load olduğu için "src" değil "data-src" okunuyor.
// ! - Referans koddaki wp-login.php girişi artık gerekmiyor (girişsiz de içerik geliyor), kaldırıldı.
// ! - Video kaynağı CloudStream'in tanıdığı bir extractor değil, "FirePlayer" adlı bir oynatıcı sistemi
// !   (peacemakerst.com, hdplayersystem.com gibi birden çok domain'de aynı altyapı) - özel çözücü eklendi.

package com.neo.dizimom

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.fasterxml.jackson.annotation.JsonProperty

class DiziMom : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("dizimom", "https://www.dizimom.food")
    override var name                 = "DiziMom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/"        to "Son Bölümler",
        "${mainUrl}/yerli-dizi-izle/page/"     to "Yerli Diziler",
        "${mainUrl}/yabanci-dizi-izle/page/"   to "Yabancı Diziler",
        "${mainUrl}/tv-programlari-izle/page/" to "TV Programları",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}/").document
        val home     = if (request.data.contains("/tum-bolumler/")) {
            document.select("div.episode-box").mapNotNull { it.sonBolumler() }
        } else {
            document.select("div.single-item").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home)
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name  = this.selectFirst("div.episode-name a")?.text()?.substringBefore(" izle") ?: return null
        val title = name.replace(".Sezon ", "x").replace(".Bölüm", "")

        val epHref = fixUrlNull(this.selectFirst("div.episode-name a")?.attr("href")) ?: return null
        val epDoc  = app.get(epHref).document
        val href   = epDoc.selectFirst("div#benzerli a")?.attr("href") ?: return null

        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("data-src")) ?: fixUrlNull(this.selectFirst("a img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("div.categorytitle a")?.text()?.substringBefore(" izle") ?: return null
        val href      = fixUrlNull(this.selectFirst("div.categorytitle a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.cat-img img")?.attr("data-src")) ?: fixUrlNull(this.selectFirst("div.cat-img img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.single-item").mapNotNull { it.diziler() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.title h1")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src")) ?: return null
        val year        = document.selectXpath("//div[span[contains(text(), 'Yapım Yılı')]]").text().substringAfter("Yapım Yılı : ").trim().toIntOrNull()
        val description = document.selectFirst("div.category_desc")?.text()?.trim()
        val tags        = document.select("div.genres a").mapNotNull { it.text().trim() }
        val score       = document.selectXpath("//div[span[contains(text(), 'IMDB')]]").text().substringAfter("IMDB : ").trim().toDoubleOrNull()?.let { Score.from10(it) }
        val actors      = document.selectXpath("//div[span[contains(text(), 'Oyuncular')]]").text().substringAfter("Oyuncular : ").split(", ").mapNotNull {
            if (it.isBlank()) null else Actor(it.trim())
        }

        val episodes = document.select("div.bolumust").mapNotNull {
            val epName    = it.selectFirst("div.baslik")?.text()?.trim() ?: return@mapNotNull null
            val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\.Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\.Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            newEpisode(epHref) {
                this.name    = epName.substringBefore(" izle").replace(title, "").trim()
                this.season  = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = score
            addActors(actors)
        }
    }

    // ! Ana oynatıcı "div.video p iframe" içinde "data-src" (lazy-load) olarak geliyor; "div.sources a"
    // ! altında (varsa) alternatif dublaj/altyazı kaynakları var, her biri kendi sayfasında aynı yapıda.
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZM", "data » $data")
        val document = app.get(data).document

        val iframes = mutableListOf<String>()
        val mainIframeEl = document.selectFirst("div.video p iframe")
        if (mainIframeEl != null) {
            val dataSrc: String = mainIframeEl.attr("data-src")
            val mainIframe: String = if (dataSrc.isNotBlank()) dataSrc else mainIframeEl.attr("src")
            if (mainIframe.isNotBlank() && mainIframe != "about:blank") iframes.add(mainIframe)
        }

        document.select("div.sources a").forEach {
            try {
                val subDocument = app.get(it.attr("href")).document
                val subIframeEl = subDocument.selectFirst("div.video p iframe")
                if (subIframeEl != null) {
                    val subDataSrc: String = subIframeEl.attr("data-src")
                    val subIframe: String = if (subDataSrc.isNotBlank()) subDataSrc else subIframeEl.attr("src")
                    if (subIframe.isNotBlank() && subIframe != "about:blank") iframes.add(subIframe)
                }
            } catch (e: Exception) {
                Log.d("DZM", "alt kaynak hatası » ${e.message}")
            }
        }

        if (iframes.isEmpty()) return false

        for (iframe in iframes) {
            Log.d("DZM", "iframe » $iframe")
            val fixed = fixUrl(iframe)
            try {
                extractFromFirePlayer(fixed, data, callback)
            } catch (e: Exception) {
                Log.d("DZM", "FirePlayer hatası » ${e.message}")
            }
            try {
                loadExtractor(fixed, "${mainUrl}/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.d("DZM", "loadExtractor hatası » ${e.message}")
            }
        }

        return true
    }

    // ! "FirePlayer" (Neron / firevideoplayer.com) tabanlı oynatıcı sistemi: iframe URL'sindeki son
    // ! parça video hash'i, "{iframe_base}?do=getVideo" adresine POST edilince JSON döner. Yanıt iki
    // ! farklı şekilde gelebiliyor: {"securedLink" veya "videoSource": "...m3u8"} ya da
    // ! {"videoSources":[{"file": "..."}]} - hangisi doluysa o kullanılıyor.
    private suspend fun extractFromFirePlayer(iframeUrl: String, refererUrl: String, callback: (ExtractorLink) -> Unit) {
        val hash   = iframeUrl.trimEnd('/').substringAfterLast("/").substringBefore("?")
        if (hash.isBlank()) return
        val apiUrl = iframeUrl.substringBefore("?").trimEnd('/') + "?do=getVideo"

        val resp = app.post(
            apiUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data    = mapOf("hash" to hash, "r" to refererUrl),
            referer = iframeUrl
        ).parsedSafe<FireVideoResponse>() ?: return

        val videoUrl = resp.securedLink ?: resp.videoSource ?: resp.videoSources?.firstOrNull()?.file ?: return

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = videoUrl,
                type   = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = iframeUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private data class FireVideoSource(
        @JsonProperty("file") val file: String? = null
    )

    private data class FireVideoResponse(
        @JsonProperty("hls")          val hls: Boolean?              = null,
        @JsonProperty("videoSource")  val videoSource: String?       = null,
        @JsonProperty("securedLink")  val securedLink: String?       = null,
        @JsonProperty("videoSources") val videoSources: List<FireVideoSource>? = null
    )
}
