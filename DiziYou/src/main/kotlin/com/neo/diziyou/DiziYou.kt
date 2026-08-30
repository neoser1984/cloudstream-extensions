// ! Bu araç NeO tarafından yazılmıştır.
// ! Kekik-cloudstream/DiziYou tabanlı, domains.json ile adres yönetimi ve güncel Kotlin/CloudStream
// ! API'lerine (Score) uyarlanmıştır. 2026-08 itibarıyla canlı site üzerinde doğrulanmış değişiklikler:
// ! - IMDB puanı artık "span.dizimeta:contains(IMDB)" değil, "div.imdb-logo" içinde "IMDb: 8.8" olarak geliyor.
// ! - Arama sonuçları "div.single-item" değil "div#list-series" kullanıyor (iç yapı aynı).
// ! - Bölüm oynatıcı/altyazı/m3u8 mantığı canlı sitede birebir doğrulandı, değişmedi.

package com.neo.diziyou

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class DiziYou : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("diziyou", "https://www.diziyou.one")
    override var name                 = "DiziYou"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Aile"        to "Aile",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Aksiyon"      to "Aksiyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Animasyon"    to "Animasyon",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Belgesel"     to "Belgesel",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Bilim+Kurgu"  to "Bilim Kurgu",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Dram"         to "Dram",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Fantazi"      to "Fantazi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Gerilim"      to "Gerilim",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Gizem"        to "Gizem",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Komedi"       to "Komedi",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Korku"        to "Korku",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Macera"       to "Macera",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Sava%C5%9F"   to "Savaş",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Su%C3%A7"     to "Suç",
        "${mainUrl}/dizi-arsivi/page/SAYFA/?tur=Vah%C5%9Fi+Bat%C4%B1" to "Vahşi Batı",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = request.data.replace("SAYFA", "$page")
        val document = app.get(url).document
        val home     = document.select("div.single-item").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div#categorytitle a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("div#categorytitle a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.incontent div#list-series").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.category_image img")?.attr("src"))
        val description = document.selectFirst("div.diziyou_desc")?.ownText()?.trim()

        val yearMeta   = document.select("span.dizimeta").firstOrNull { it.text().contains("Yapım Yılı") }
        val year       = yearMeta?.nextSibling()?.toString()?.trim()?.toIntOrNull()

        val score      = document.selectFirst("div.imdb-logo")?.text()?.substringAfter(":")?.trim()?.toDoubleOrNull()?.let { Score.from10(it) }

        val actorsMeta = document.select("span.dizimeta").firstOrNull { it.text().contains("Oyuncular") }
        val actorsText = actorsMeta?.nextSibling()?.toString()?.trim()
        val actors     = actorsText?.split(", ")?.mapNotNull { if (it.isBlank()) null else Actor(it.trim()) } ?: emptyList()

        val tags    = document.select("div.genres a").map { it.text() }
        val trailer = document.selectFirst("iframe.trailer-video")?.attr("src")

        val episodes = document.select("div.bolumust").mapNotNull {
            val epName    = it.selectFirst("div.baslik")?.ownText()?.trim() ?: return@mapNotNull null
            val epAnchor  = it.closest("a")
            val epHref    = fixUrlNull(epAnchor?.attr("href")) ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+)\. Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
            val epSeason  = Regex("""(\d+)\. Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epTitleEl = it.selectFirst("div.bolumismi")?.text()?.trim()?.replace(Regex("""[()]"""), "")?.trim()

            newEpisode(epHref) {
                this.name    = if (epTitleEl.isNullOrBlank()) epName else epTitleEl
                this.season  = epSeason
                this.episode = epEpisode
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = score
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZY", "data » $data")
        val document = app.get(data).document

        val playerSrc = document.selectFirst("iframe#diziyouPlayer")?.attr("src") ?: return false
        val itemId    = playerSrc.substringAfterLast("/").substringBefore(".html")
        if (itemId.isBlank()) return false
        Log.d("DZY", "itemId » $itemId")

        val storage = mainUrl.replace("www", "storage")

        val subTitles  = mutableListOf<Pair<String, String>>()
        val streamUrls = mutableListOf<Pair<String, String>>()

        document.select("span.diziyouOption").forEach {
            when (it.attr("id")) {
                "turkceAltyazili" -> {
                    subTitles.add("Turkish" to "${storage}/subtitles/${itemId}/tr.vtt")
                    streamUrls.add("Orjinal Dil" to "${storage}/episodes/${itemId}/play.m3u8")
                }
                "ingilizceAltyazili" -> {
                    subTitles.add("English" to "${storage}/subtitles/${itemId}/en.vtt")
                    streamUrls.add("Orjinal Dil" to "${storage}/episodes/${itemId}/play.m3u8")
                }
                "turkceDublaj" -> {
                    streamUrls.add("Türkçe Dublaj" to "${storage}/episodes/${itemId}_tr/play.m3u8")
                }
            }
        }

        for ((subName, subUrl) in subTitles) {
            subtitleCallback.invoke(
                SubtitleFile(lang = subName, url = fixUrl(subUrl))
            )
        }

        for ((streamName, streamUrl) in streamUrls) {
            callback.invoke(
                newExtractorLink(
                    source = streamName,
                    name   = streamName,
                    url    = fixUrl(streamUrl),
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.referer = "${mainUrl}/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return streamUrls.isNotEmpty()
    }
}
