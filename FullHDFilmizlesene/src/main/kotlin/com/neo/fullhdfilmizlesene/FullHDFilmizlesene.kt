// ! Bu araç NeO tarafından yazılmıştır.
// ! Kekik-cloudstream/FullHDFilmizlesene tabanlı, domains.json ile adres yönetimi ve güncel
// ! Kotlin/CloudStream API'lerine (Score, newExtractorLink) uyarlanmıştır. 2026-08 itibarıyla
// ! canlı site üzerinde doğrulanmış değişiklikler:
// ! - Kategori adresleri artık "-izle-N" son ekini taşımıyor (örn: "/filmizle/aksiyon-filmleri").
// ! - Video kaynağı hâlâ "scx" adlı rot13+base64 ile şifrelenmiş bir JSON haritasından geliyor;
// !   bu kısım referans koddaki gibi çalışıyor ve doğrudan korundu.
// ! - "atom" (RapidVid), "tr"/"en" (TRsTX), "proton" (VidMoxy), "advid" (Sobreatsesuyp) gibi
// !   sağlayıcılar CloudStream çekirdeğine yerleşik hâle geldiği için ayrıca yazılmadı; sadece
// !   çekirdekte bulunmayan TurboImgz için özel extractor eklendi.

package com.neo.fullhdfilmizlesene

import android.util.Log
import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("fullhdfilmizlesene", "https://www.fullhdfilmizlesene.now")
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmizle/aile-filmleri"        to "Aile Filmleri",
        "${mainUrl}/filmizle/aksiyon-filmleri"      to "Aksiyon Filmleri",
        "${mainUrl}/filmizle/animasyon-filmleri"    to "Animasyon Filmleri",
        "${mainUrl}/filmizle/belgesel-filmleri"     to "Belgeseller",
        "${mainUrl}/filmizle/bilim-kurgu-filmleri"  to "Bilim Kurgu Filmleri",
        "${mainUrl}/filmizle/bluray-filmler"        to "Blu Ray Filmler",
        "${mainUrl}/filmizle/cizgi-filmler"         to "Çizgi Filmler",
        "${mainUrl}/filmizle/dram-filmler-izle"     to "Dram Filmleri",
        "${mainUrl}/filmizle/fantastik-filmler"     to "Fantastik Filmler",
        "${mainUrl}/filmizle/gerilim-filmleri"      to "Gerilim Filmleri",
        "${mainUrl}/filmizle/gizem-filmleri"        to "Gizem Filmleri",
        "${mainUrl}/filmizle/hint-filmleri"         to "Hint Filmleri",
        "${mainUrl}/filmizle/komedi-filmleri"       to "Komedi Filmleri",
        "${mainUrl}/filmizle/korku-filmleri"        to "Korku Filmleri",
        "${mainUrl}/filmizle/macera-filmleri"       to "Macera Filmleri",
        "${mainUrl}/filmizle/muzikal-filmler"       to "Müzikal Filmler",
        "${mainUrl}/filmizle/polisiye-filmleri"     to "Polisiye Filmleri",
        "${mainUrl}/filmizle/psikolojik-filmler"    to "Psikolojik Filmler",
        "${mainUrl}/filmizle/romantik-filmler"      to "Romantik Filmler",
        "${mainUrl}/filmizle/savas-filmleri"        to "Savaş Filmleri",
        "${mainUrl}/filmizle/suc-filmleri"          to "Suç Filmleri",
        "${mainUrl}/filmizle/tarih-filmleri"        to "Tarih Filmleri",
        "${mainUrl}/filmizle/western-filmler"       to "Western Filmler",
        "${mainUrl}/filmizle/yerli-filmler"         to "Yerli Filmler",
        "${mainUrl}/filmizle/yabanci-filmler"       to "Yabancı Filmler",
        "${mainUrl}/filmizle/imdb-puani-yuksek-filmler" to "IMDB Puanı Yüksek Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/${page}").document
        val home     = document.select("li.film").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span.film-title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/${query}").document

        return document.select("li.film").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div[class=izle-titles]")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div img")?.attr("data-src"))
        val year        = document.selectFirst("div.dd a.category")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val description = document.selectFirst("div.ozet-ic > p")?.text()?.trim()
        val tags        = document.select("a[rel='category tag']").map { it.text() }
        val score       = document.selectFirst("div.puanx-puan")?.text()?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toDoubleOrNull()?.let { Score.from10(it) }
        val duration    = document.selectFirst("span.sure")?.text()?.split(" ")?.get(0)?.trim()?.toIntOrNull()
        val trailer     = Regex("""embedUrl": "(.*)"""").find(document.html())?.groupValues?.get(1)
        val actors      = document.select("div.film-info ul li:nth-child(2) a > span").map { Actor(it.text()) }

        val recommendations = document.selectXpath("//div[span[text()='Benzer Filmler']]/following-sibling::section/ul/li").mapNotNull {
            val recName      = it.selectFirst("span.film-title")?.text() ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))

            newMovieSearchResponse(recName, recHref, TvType.Movie) { this.posterUrl = recPosterUrl }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl        = poster
            this.year             = year
            this.plot             = description
            this.tags             = tags
            this.score            = score
            this.duration         = duration
            this.recommendations  = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun atob(s: String): String {
        return String(Base64.decode(s, Base64.DEFAULT))
    }

    private fun rtt(s: String): String {
        fun rot13Char(c: Char): Char {
            return when (c) {
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else        -> c
            }
        }

        return s.map { rot13Char(it) }.joinToString("")
    }

    private fun getVideoLinks(document: Document): List<Map<String, String>> {
        val scriptElement = document.select("script").firstOrNull { it.data().contains("scx") }
        val scriptContent = scriptElement?.data()?.trim() ?: return emptyList()

        val scxData = Regex("""scx = (.*?);""").find(scriptContent)?.groupValues?.get(1) ?: return emptyList()
        val scxMap: SCXData = jacksonObjectMapper().readValue(scxData)
        val keys = listOf("atom", "advid", "advidprox", "proton", "fast", "fastly", "tr", "en")

        val linkList = mutableListOf<Map<String, String>>()

        for (key in keys) {
            val t = when (key) {
                "atom"      -> scxMap.atom?.sx?.t
                "advid"     -> scxMap.advid?.sx?.t
                "advidprox" -> scxMap.advidprox?.sx?.t
                "proton"    -> scxMap.proton?.sx?.t
                "fast"      -> scxMap.fast?.sx?.t
                "fastly"    -> scxMap.fastly?.sx?.t
                "tr"        -> scxMap.tr?.sx?.t
                "en"        -> scxMap.en?.sx?.t
                else        -> null
            }

            when (t) {
                is List<*> -> {
                    val links = t.filterIsInstance<String>().map { link -> atob(rtt(link)) }
                    linkList.add(mapOf(key to links.joinToString(",")))
                }
                is Map<*, *> -> {
                    val links = t.mapValues { (_, value) ->
                        if (value is String) atob(rtt(value)) else ""
                    }
                    val safeLinks = links.mapKeys { (mapKey, _) ->
                        mapKey?.toString() ?: "Unknown"
                    }
                    linkList.add(safeLinks)
                }
            }
        }

        return linkList
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FHD", "data » $data")
        val document   = app.get(data).document
        val videoLinks = getVideoLinks(document)
        Log.d("FHD", "videoLinks » $videoLinks")
        if (videoLinks.isEmpty()) return false

        for (videoMap in videoLinks) {
            for ((key, value) in videoMap) {
                val videoUrl = fixUrlNull(value) ?: continue
                if (videoUrl.contains("turbo.imgz.me")) {
                    loadExtractor("${key}||${videoUrl}", "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SCXData(
        @JsonProperty("atom")      val atom: AtomData?      = null,
        @JsonProperty("advid")     val advid: AtomData?     = null,
        @JsonProperty("advidprox") val advidprox: AtomData? = null,
        @JsonProperty("proton")    val proton: AtomData?    = null,
        @JsonProperty("fast")      val fast: AtomData?      = null,
        @JsonProperty("fastly")    val fastly: AtomData?    = null,
        @JsonProperty("tr")        val tr: AtomData?        = null,
        @JsonProperty("en")        val en: AtomData?        = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AtomData(
        @JsonProperty("sx") var sx: SXData
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SXData(
        @JsonProperty("t") var t: Any
    )
}
