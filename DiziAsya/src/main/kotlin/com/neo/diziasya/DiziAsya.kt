// ! DiziAsya (diziasya.com) eklentisi.
// !
// ! Site notları:
// ! - Modern bir Next.js (App Router, Server Components/SSR) uygulaması. React SPA olmasına rağmen
// !   sayfa kaynağı (fetch ile alınan ham HTML) Jsoup ile parse edilebiliyor çünkü içerik sunucu
// !   tarafında render ediliyor (RSC payload olarak <script> içine de gömülü).
// ! - Dört içerik türü/öneki var: "/diziler" (dizi, "/dizi/<slug>"), "/filmler" (film, "/film/<slug>"),
// !   "/animeler" (anime, "/anime/<slug>"), "/programlar" (program/reality show, "/program/<slug>").
// ! - Listeleme kartları "a[href^='/dizi/'|'/film/'|'/anime/'|'/program/']" şeklinde; başlık kartın
// !   içindeki "h3" etiketinde, poster ise "img" öğesinin "src"si Next.js image-proxy'si
// !   ("/_next/image?url=<encoded>&w=..&q=..") olduğundan "url=" parametresi decode edilerek alınıyor.
// !   Sayfalama "?page={n}" parametresiyle.
// ! - Arama, temiz bir JSON API üzerinden çalışıyor: GET https://api.diziasya.com/v2/contents/instant-search?q={sorgu}
// !   -> [{"type":"MOVIE"|"SERIES"|"ANIME"|"SHOW","title":..,"slug":..,"cover_image":..,"thumb_image":..}]
// ! - Detay sayfasında başlık "h1" etiketinin DOĞRUDAN metni (ownText) - çünkü h1 içinde ayrıca bir
// !   "Film"/"Dizi" rozeti (span) da bulunuyor ve normal text() bunları birleştiriyor. Poster
// !   "meta[property=og:image]" içinde. Türler "a[href*='?category=']" içinde. Özet/konu metni,
// !   60 karakterden uzun "p" etiketlerinin İKİNCİSİ (ilki alternatif başlıklar listesi oluyor) -
// !   sağlam bir class/id olmadığından bu sezgisel (heuristic) yöntemle alınıyor, best-effort.
// ! - Dizi/anime/program bölüm linkleri "a[href*='-bolum-']" şeklinde, sezon/bölüm numarası
// !   "/sezon-(\d+)-bolum-(\d+)" regex'i ile URL'den çıkarılıyor. Film sayfası ("/film/<slug>")
// !   doğrudan izleme sayfası (ayrı bir "izle" linkine gerek yok).
// ! - VİDEO KAYNAKLARI: Hem bölüm hem film sayfalarının ham HTML'inde (RSC payload içinde,
// !   backslash-escaped JSON olarak) bir alternatif-kaynaklar dizisi bulunuyor. Bölüm sayfalarında
// !   anahtar "alternativeLinks" (camelCase), film sayfalarında ise "alternative_links" (snake_case) -
// !   ikisi de regex ile yakalanıp birleştiriliyor. Bu dizi genelde şunları içeriyor: kendi özel
// !   "video.diziasya.com/embed?i=<obfuscated>" adresleri (desteklenmiyor, atlanıyor), "ok.ru",
// !   "vidmoly.org", "dzen.ru" gibi CloudStream'in yerleşik extractor'larının muhtemelen zaten
// !   desteklediği YAYGIN video barındırma adresleri, ve bazen ilgisiz linkler (ör. "uns.bio" kısa
// !   linkleri). Her URL sırayla loadExtractor() ile deneniyor (try/catch içinde) - eşleşen
// !   extractor bulunursa video oynatılabiliyor. Bu proje boyunca video kaynağı gerçekten
// !   çalışabilecek ilk sitelerden biri.

package com.neo.diziasya

import java.net.URLDecoder
import java.net.URLEncoder
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DiziAsya : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("diziasya", "https://diziasya.com")
    override var name                 = "DiziAsya"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/animeler" to "Animeler",
        "$mainUrl/programlar" to "Programlar",
    )

    private val episodeRegex = Regex("""/sezon-(\d+)-bolum-(\d+)""")
    private val altLinksRegex = Regex(""""alternative(?:_links|Links)"\s*:\s*\[(.*?)\]""")
    private val quotedStringRegex = Regex(""""((?:\\.|[^"\\])*)"""")

    data class SearchItem(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("cover_image") val coverImage: String? = null,
        @JsonProperty("thumb_image") val thumbImage: String? = null,
    )

    private fun extractNextImage(src: String?): String? {
        if (src.isNullOrBlank()) return null
        if (src.contains("url=")) {
            val raw = src.substringAfter("url=").substringBefore("&")
            return try {
                fixUrlNull(URLDecoder.decode(raw, "UTF-8"))
            } catch (e: Exception) {
                fixUrlNull(src)
            }
        }
        return fixUrlNull(src)
    }

    private fun typeFromHref(href: String): TvType = when {
        href.contains("/film/")  -> TvType.Movie
        href.contains("/anime/") -> TvType.Anime
        else                     -> TvType.TvSeries
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document

        val home = document
            .select("a[href^='/dizi/'], a[href^='/film/'], a[href^='/anime/'], a[href^='/program/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val posterUrl = extractNextImage(this.selectFirst("img")?.attr("src"))
        val type = typeFromHref(href)

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val response = try {
            app.get("https://api.diziasya.com/v2/contents/instant-search?q=$encodedQuery").text
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = try {
            jacksonObjectMapper().readValue<List<SearchItem>>(response)
        } catch (e: Exception) {
            return emptyList()
        }

        return parsed.mapNotNull { item ->
            val slug = item.slug?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            val title = item.title?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null

            val prefix = when (item.type) {
                "MOVIE" -> "/film/"
                "ANIME" -> "/anime/"
                "SHOW"  -> "/program/"
                else    -> "/dizi/"
            }
            val href = "$mainUrl$prefix$slug"
            val posterUrl = fixUrlNull(item.coverImage ?: item.thumbImage)

            when (item.type) {
                "MOVIE" -> newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
                "ANIME" -> newTvSeriesSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
                else    -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie = url.contains("/film/")
        val isAnime = url.contains("/anime/")

        val h1 = document.selectFirst("h1") ?: return null
        val title = h1.ownText().trim().ifBlank { h1.text().trim() }.ifBlank { return null }

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val tags = document.select("a[href*='?category=']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val longParagraphs = document.select("p").filter { it.text().trim().length > 60 }
        val plot = longParagraphs.getOrNull(1)?.text()?.trim()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.tags      = tags
            }
        }

        val episodes = document.select("a[href*='-bolum-']").mapNotNull { epLink ->
            val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
            val match = episodeRegex.find(epHref) ?: return@mapNotNull null
            val season = match.groupValues[1].toIntOrNull() ?: 1
            val epNum = match.groupValues[2].toIntOrNull()

            newEpisode(epHref) {
                this.season  = season
                this.episode = epNum
            }
        }.distinctBy { it.data }

        if (episodes.isEmpty()) return null

        val tvType = if (isAnime) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = try {
            app.get(data).text
        } catch (e: Exception) {
            return false
        }

        val arrayContents = altLinksRegex.find(html)?.groupValues?.get(1) ?: return false

        val urls = quotedStringRegex.findAll(arrayContents)
            .map { it.groupValues[1].replace("\\/", "/").replace("\\u0026", "&") }
            .filter { it.startsWith("http") || it.startsWith("//") }
            .toList()

        if (urls.isEmpty()) return false

        var found = false
        for (rawUrl in urls) {
            val fixedUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
            try {
                if (loadExtractor(fixedUrl, data, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
                // Desteklenmeyen/eşleşmeyen kaynak - sıradaki URL'e geç.
            }
        }

        return found
    }
}
