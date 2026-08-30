// ! Bu araç NeO tarafından yazılmıştır.
// ! yabancidizi.news canlı sitesi üzerinde 2026-08 itibarıyla doğrulanmıştır. Hazır referans
// ! eklenti bulunmadığından seçiciler canlı site incelemesiyle sıfırdan çıkarılmıştır.
// ! - Ana kategori/arşiv sayfaları: "/dizi-izle-hd" (tüm diziler, A-Z, TEK sayfada binlerce kayıt)
// !   ve "/film-izle-hd" (filmler, sayfalı: "{url}/{n}" — 2. sayfadan itibaren). Kartlar
// !   "a[href^=\"dizi/\"]" / "a[href^=\"film/\"]" (img içerenler; kenar çubuğundaki "trending"
// !   linkleri img İÇERMEZ, bu yüzden filtrelenir). Poster "img[data-src]" (yoksa "img[src]"),
// !   başlık "img[alt]" (kesin görünen başlık metni yoktur, bazı film kartlarında alt orijinal
// !   dildeki başlık olabilir — bilinen küçük bir kısıt).
// ! - Dizi adresi "/dizi/{slug}/", film "/film/{slug}/".
// ! - ARAMA: sitenin görünür arama kutusu normal GET/POST navigasyonu DEĞİL, "X-Requested-With:
// !   XMLHttpRequest" header'ı ile POST "/search?qr=<sorgu>" isteği atıyor (header olmadan sunucu
// !   sıradan ana sayfa HTML'i döndürüyor, JSON DEĞİL). Doğru header ile yanıt şu JSON:
// !   {"success":1,"data":{"result":[{"s_id":...,"s_type":"0|1","s_link":"...","s_name":"...",
// !   "s_image":"...","s_year":"..."}]}} — "s_type" "0"=dizi, "1"=film. "s_link" ilgili slug
// !   ("dizi/" veya "film/" öneki YOKTUR, eklenmesi gerekir). Poster "s_image" değeri
// !   "/uploads/series/{s_image}" altında (dikkat: ana arşiv sayfalarındaki
// !   "/uploads/series/cover/{...}" yolundan FARKLI, "cover" alt klasörü yok).
// ! - Dizi/film DETAY sayfası "article.series-summary" içinde: başlık "h1" (sonunda " (YIL)"
// !   eki temizlenir), açıklama ".series-summary-wrapper p", tür etiketleri
// !   "article.series-summary a[href*=\"/tur/\"]" (kenar çubuğu ile karışmaması için "article.
// !   series-summary" ile sınırlanır), meta bilgi tablosu "table.ui.unstackable.single.line.
// !   celled.table" içindeki her "td" iki "div" barındırır (İLK div etiket: "Ülke"/"Süre"/
// !   "Takipçiler"/"IMDb Puanı"/"Yapım Yılı", İKİNCİ div değer). Poster "meta[property=og:image]".
// ! - Dizi KÖK sayfası varsayılan olarak TEK bir sezonu (sekme ile) gösterir GİBİ görünse de HAM
// !   SUNUCU HTML'İNDE TÜM sezonların TÜM bölümleri zaten mevcuttur (Diziyo eklentisinde
// !   keşfedilen örüntüyle aynı) — "td.table-episodes-title a" ile TÜM bölüm linkleri tek
// !   istekte toplanır, ayrı sezon sayfası gezmeye GEREK YOKTUR. Bölüm href örüntüsü
// !   "/dizi/{slug}/sezon-{n}/bolum-{m}/". Bölüm adı doğrudan bu "a" elemanının metni, tarih
// !   kardeş hücre "td.episode-date" içindedir.
// ! - VİDEO OYNATICI: film/bölüm sayfasında "Videoyu Başlat" butonuna basmak normalde bir iframe
// !   enjekte ediyor GİBİ görünse de, gerçek kaynak adresleri HAM HTML'DE ZATEN MEVCUT —
// !   ".item[data-link]" elemanları (etiket metni sağlayıcı adı: "Mac", "VidMoly", "Okru", vb.)
// !   "data-link" attribute'unda bir token taşıyor. Bu token "/api/{endpoint}/{token}" adresine
// !   GET isteğiyle (tıklama SİMÜLE ETMEYE gerek yok) gönderildiğinde, sunucu üçüncü parti
// !   video barındırıcıya işaret eden küçük bir HTML parçası (tek bir "<iframe src=...>")
// !   döndürüyor. "endpoint" değeri sağlayıcı adından TÜRETİLEMİYOR (keyfi dahili kod): canlı
// !   testte doğrulanan eşleşmeler: "Mac" -> "drives" (popcornvakti.net barındırıcısına gidiyor,
// !   CloudStream'de yerleşik bir extractor'ı yok — en iyi çaba, muhtemelen başarısız olur),
// !   "VidMoly" -> "moly" (vidmoly.biz — CloudStream'in YERLEŞİK VidMoly extractor'ı destekliyor),
// !   "Okru" -> "ruplay" (ok.ru — CloudStream'in YERLEŞİK Okru extractor'ı destekliyor). Bilinmeyen
// !   sağlayıcı adları (bu üç eşleşmeden biriyle eşleşmeyen) atlanır.

package com.neo.yabancidizi

import java.net.URLEncoder
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@JsonIgnoreProperties(ignoreUnknown = true)
data class YbdSearchItem(
    @JsonProperty("s_id")    val sId: String?    = null,
    @JsonProperty("s_type")  val sType: String?   = null,
    @JsonProperty("s_link")  val sLink: String?   = null,
    @JsonProperty("s_name")  val sName: String?   = null,
    @JsonProperty("s_image") val sImage: String?  = null,
    @JsonProperty("s_year")  val sYear: String?   = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YbdSearchData(
    val result: List<YbdSearchItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YbdSearchResponse(
    val success: Int?      = null,
    val data: YbdSearchData? = null,
)

class YabanciDizi : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("yabancidizi", "https://yabancidizi.news")
    override var name                 = "YabanciDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle-hd" to "Diziler",
        "$mainUrl/film-izle-hd" to "Filmler",
    )

    private val seasonEpisodeRegex = Regex("""sezon-(\d+)/bolum-(\d+)""")
    private val yearSuffixRegex    = Regex("""\s*\(\d{4}\)\s*$""")

    private val providerEndpoints = mapOf(
        "vidmoly" to "moly",
        "mac"     to "drives",
        "okru"    to "ruplay",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url    = if (page <= 1) request.data else "${request.data}/$page"
        val prefix = if (request.data.contains("dizi-izle-hd")) "dizi/" else "film/"

        val document = app.get(url).document

        val home = document.select("a[href]")
            .filter { it.attr("href").startsWith(prefix) && it.selectFirst("img") != null }
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val img  = this.selectFirst("img") ?: return null

        val posterUrl = fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
        val title = img.attr("alt").trim().removeSuffix("izle").trim().ifBlank { null } ?: return null

        return when {
            href.contains("/film/") -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
            else -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val response = try {
            app.post(
                "$mainUrl/search?qr=$encodedQuery",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
        } catch (e: Exception) {
            return emptyList()
        }

        val wrapper = try {
            jacksonObjectMapper().readValue<YbdSearchResponse>(response)
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val results = wrapper.data?.result ?: return emptyList()

        return results.mapNotNull { item ->
            val slug  = item.sLink ?: return@mapNotNull null
            val title = item.sName ?: return@mapNotNull null
            val isMovie = item.sType == "1"
            val url     = if (isMovie) "$mainUrl/film/$slug" else "$mainUrl/dizi/$slug"
            val poster  = item.sImage?.ifBlank { null }?.let { "$mainUrl/uploads/series/$it" }

            if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie  = url.contains("/film/")

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title    = rawTitle.replace(yearSuffixRegex, "").trim().ifBlank { rawTitle }

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val infoMap = mutableMapOf<String, String>()
        document.selectFirst("table.ui.unstackable.single.line.celled.table")?.select("td")?.forEach { td ->
            val divs = td.select("div")
            if (divs.size >= 2) infoMap[divs[0].text().trim()] = divs[1].text().trim()
        }
        val year  = infoMap["Yapım Yılı"]?.toIntOrNull()
        val score = infoMap["IMDb Puanı"]?.replace(",", ".")?.toDoubleOrNull()?.let { Score.from10(it) }

        val tags = document.select("article.series-summary a[href*='/tur/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }

        val description = document.selectFirst(".series-summary-wrapper p")?.text()?.trim()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.score     = score
            }
        }

        val episodes = document.select("td.table-episodes-title a").mapNotNull { epLink ->
            val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
            val match  = seasonEpisodeRegex.find(epHref) ?: return@mapNotNull null
            val season = match.groupValues[1].toIntOrNull() ?: 1
            val epNum  = match.groupValues[2].toIntOrNull()
            val epName = epLink.text().trim().ifBlank { null }

            newEpisode(epHref) {
                this.season  = season
                this.episode = epNum
                this.name    = epName
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = score
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        var found = false

        document.select(".item[data-link]").forEach { item ->
            val label = item.text().trim().lowercase()
            val token = item.attr("data-link").ifBlank { null } ?: return@forEach
            val endpoint = providerEndpoints.entries.firstOrNull { (key, _) -> label.contains(key) }?.value
                ?: return@forEach

            try {
                val apiDoc = app.get("$mainUrl/api/$endpoint/$token", referer = data).document
                val iframeSrc = apiDoc.selectFirst("iframe")?.attr("src")?.ifBlank { null } ?: return@forEach
                loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                // Bu sağlayıcı başarısız oldu, diğerleriyle devam edilir.
            }
        }

        return found
    }
}
