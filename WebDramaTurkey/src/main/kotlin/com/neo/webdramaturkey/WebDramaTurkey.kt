// ! WebDramaTurkey (webdramaturkey2.com) eklentisi.
// !
// ! Site notları:
// ! - Diğer eklentilerdeki WordPress temalı sitelerden FARKLI, özel (custom) bir CMS kullanıyor.
// ! - Hem dizi ("/diziler", "/dizi/<slug>") hem film ("/filmler", "/film/<slug>") içeriği var.
// ! - Listeleme kartları "div.list-movie" (a.list-media = poster/link, a.list-title = başlık,
// !   a.list-category = tür), sayfalama "?page={n}" parametresiyle.
// ! - Arama, temiz bir JSON API üzerinden çalışıyor: GET /ajax/posts?q={sorgu} ->
// !   {"data":[{"id":..,"name":..,"image":..,"url":..,"type":"Dizi"|"Film"}]}.
// ! - Detay sayfasında bilgiler ".attr" (etiket) + bir sonraki kardeş ".text" (değer) ikilileri
// !   şeklinde geliyor (örn. "Yayın yılı" / "2023"); bu yüzden tüm ".attr" elemanları taranıp
// !   bir infoMap oluşturuluyor. Türler dizi sayfasında "div.categories a", film sayfasında
// !   "div.category a" içinde (ikisi de aynı yöntemle taranıyor).
// ! - Dizi bölüm linkleri "a[href*='-sezon/'][href*='-bolum']" şeklinde, sezon/bölüm numarası
// !   URL'den regex ile çıkarılıyor. Film sayfası ("/film/<slug>") doğrudan izleme sayfası.
// ! - ÖNEMLİ KISIT: Video oynatıcı, statik HTML'de HİÇBİR iframe/URL barındırmıyor. "Kaynak: WDT"
// !   butonuna tıklanınca (data-embed özniteliğinde sadece küçük bir sayısal ID var, örn. "120224")
// !   sayfa JavaScript'i, ağ isteği YAPMADAN (XHR/fetch gözlemlenmedi) istemci tarafında bir
// !   "video.php?hash=<128 karakter hex>" adresi üretip iframe'e yerleştiriyor. Bu hash'in nasıl
// !   üretildiği (muhtemelen sayfaya gömülü, minify/obfuscate edilmiş bir JS fonksiyonuyla) tespit
// !   edilemedi; jQuery click event delegasyonunda da ilgili bir handler bulunamadı. Bu yüzden
// !   Jsoup/OkHttp tabanlı bu eklenti video kaynağına ulaşamıyor - loadLinks() en iyi çaba (best
// !   effort) ile statik HTML'de bir iframe arıyor, bulamazsa false dönüyor. Site JS'i olmadan
// !   (CloudStream'in çalışma modeli) bu kısıt aşılamıyor.

package com.neo.webdramaturkey

import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class WebDramaTurkey : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("webdramaturkey", "https://webdramaturkey2.com")
    override var name                 = "WebDramaTurkey"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
    )

    private val seasonEpisodeRegex = Regex("""/(\d+)-sezon/(\d+)-bolum""")

    data class SearchItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class SearchResult(
        @JsonProperty("data") val data: List<SearchItem>? = null,
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document

        val home = document.select("div.list-movie").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("a.list-title") ?: return null
        val href = fixUrlNull(titleEl.attr("href")) ?: return null
        val title = titleEl.text().trim().ifBlank { null } ?: return null

        val mediaEl = this.selectFirst(".media")
        val posterUrl = if (mediaEl != null) {
            val style = mediaEl.attr("style")
            val match = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
            fixUrlNull(match?.groupValues?.get(2))
        } else null

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = try {
            app.get("$mainUrl/ajax/posts?q=$query").text
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = try {
            jacksonObjectMapper().readValue<SearchResult>(response)
        } catch (e: Exception) {
            return emptyList()
        }

        return parsed.data.orEmpty().mapNotNull { item ->
            val href = fixUrlNull(item.url) ?: return@mapNotNull null
            val title = item.name?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            val posterUrl = fixUrlNull(item.image)

            if (item.type == "Film") {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie = url.contains("/film/")

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val ogImageRaw = document.selectFirst("meta[property=og:image]")?.attr("content")
        val poster = fixUrlNull(ogImageRaw?.substringBefore("?"))

        val infoMap = mutableMapOf<String, String>()
        document.select(".attr").forEach { attrEl ->
            val valueEl = attrEl.nextElementSibling()
            if (valueEl != null && valueEl.hasClass("text")) {
                val label = attrEl.text().trim()
                val value = valueEl.text().trim()
                if (label.isNotBlank()) infoMap[label] = value
            }
        }

        val description = infoMap["Genel Bakış"]
        val year = infoMap["Yayın yılı"]?.take(4)?.toIntOrNull()

        val tags = document.select("div.categories a, div.category a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
            }
        }

        val episodes = document.select("a[href*='-sezon/'][href*='-bolum']").mapNotNull { epLink ->
            val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
            val match = seasonEpisodeRegex.find(epHref) ?: return@mapNotNull null
            val season = match.groupValues[1].toIntOrNull() ?: 1
            val epNum = match.groupValues[2].toIntOrNull()

            newEpisode(epHref) {
                this.season  = season
                this.episode = epNum
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
            this.year      = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // Bkz. dosya başındaki not: video oynatıcı adresi statik HTML'de bulunmuyor, JavaScript ile
        // istemci tarafında üretiliyor. Burada yalnızca (olası bir site güncellemesiyle statik hale
        // gelmesi ihtimaline karşı) en iyi çaba ile bir iframe aranıyor.
        val document = app.get(data).document
        val iframeSrc = document.selectFirst("iframe")?.attr("src")?.ifBlank { null } ?: return false

        return try {
            loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
            true
        } catch (e: Exception) {
            false
        }
    }
}
