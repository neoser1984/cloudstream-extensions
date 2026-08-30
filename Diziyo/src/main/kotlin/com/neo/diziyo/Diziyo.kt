// ! Bu araç NeO tarafından yazılmıştır.
// ! diziyo.so canlı sitesi üzerinde 2026-08 itibarıyla doğrulanmıştır. Hazır referans eklenti
// ! bulunmadığından seçiciler canlı site incelemesiyle sıfırdan çıkarılmıştır.
// ! - Kartlar "article.dzy-title-card" > "a.dzy-title-card__link" ile bulunur; başlık SADECE
// !   "img[alt]" içindedir (" posteri" son eki kırpılır), ayrı bir başlık metni yoktur. Poster
// !   "img[src]" zaten tam URL, lazy-load "data-src" YOKTUR.
// ! - Film adresi "/film/{slug}/", dizi "/dizi/{slug}/", anime "/anime/{slug}/".
// ! - Sayfalama: "{kategoriUrl}?sayfa={n}" (query-string, "sayfa=1" ilk sayfa).
// ! - Arama: GET "/arama/?q=<sorgu>" — sonuçlar aynı "a.dzy-title-card__link" kart yapısını kullanır
// !   (film/dizi/anime karışık).
// ! - Dizi/anime KÖK sayfası ("/dizi/{slug}/" veya "/anime/{slug}/") varsayılan olarak yalnızca TEK
// !   bir sezonu gösterir (genelde son/güncel sezon; "Önceki Sezon"/"Sonraki Sezon" gezinme linkleri
// !   ile değişir) ANCAK sunucudan gelen HAM HTML'de TÜM sezonların bölüm linkleri zaten mevcuttur
// !   (JS ile sadece görünüm/sekme değişiyor, veri baştan yüklü). Bu yüzden ayrı sezon sayfalarını
// !   ("/sezon-{n}/") gezmeye GEREK YOKTUR — kök sayfadaki TÜM "a.dzy-episode-row" elemanları
// !   toplanır. Dizilerde href "/dizi/{slug}/sezon-{n}/bolum-{m}/", anime'lerde SEZON YOKTUR, düz
// !   "/anime/{slug}/bolum-{m}/" şeklindedir (tümü 1. sezon kabul edilir).
// ! - Bölüm satırı yapısı: "span.dzy-imdb-rating-badge" (puan), "span.dzy-episode-row__label" (metin:
// !   "N. Sezon M. Bölüm (Bölüm Adı)" ya da anime'de "M. Bölüm (Bölüm Adı)"); bölüm adı ayrıca
// !   ayrıştırılabilir "em" etiketinde de bulunur.
// ! - Detay meta bilgisi ".dzy-detail__fact--year/--genre/--rating" sınıflarıyla, açıklama
// !   ".dzy-summary__text" (yoksa ".dzy-season-summary") içindedir. Poster "meta[property=og:image]".
// ! - VİDEO OYNATICI (ÖNEMLİ KISIT): "Oynat" butonuna tıklandığında bir "dzy-player__gate" katmanı
// !   açığa çıkıyor ve içinde "dzy-player__turnstile" (Cloudflare Turnstile bot doğrulaması) var. Bu
// !   doğrulama gerçek bir tarayıcıda JS çalıştırmayı gerektirir; doğrulama geçtikten SONRA aynı
// !   origin'den ("www.diziyo.so/player/video/<token>/watch/<token2>") bir <iframe> DOM'a ekleniyor.
// !   Bu token HAM SUNUCU HTML'İNDE YOKTUR (yalnızca istemci tarafında, Turnstile doğrulamasından
// !   sonra üretiliyor) ve token URL'i kimlik bilgisi olmadan (credentials: omit) 403 dönüyor, tekrar
// !   fetch ile de (credentials dahil) başarısız oluyor (muhtemelen tek kullanımlık / oturuma bağlı).
// !   Yani CloudStream'in Jsoup/OkHttp istemcisiyle bu siteden video linki ÇIKARILAMIYOR — ham HTML'de
// !   hiçbir token/iframe/AJAX endpoint izi bulunmuyor. loadLinks() yine de olası bir iframe için
// !   sayfayı tarar (ileride site tarafında bir değişiklik olursa çalışabilir), ama pratikte false
// !   dönmesi beklenir. Bu risk NeO'nun önceki eklentilerinde (HDFilmizle, HDFilmDiziIzle) belgelenen
// !   üçüncü parti "iframe-only" kısıtlarından daha katı bir örnektir.

package com.neo.diziyo

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Diziyo : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("diziyo", "https://www.diziyo.so")
    override var name                 = "Diziyo"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler/"  to "Filmler",
        "$mainUrl/diziler/"  to "Diziler",
        "$mainUrl/animeler/" to "Animeler",
    )

    private val seasonEpisodeRegex = Regex("""/sezon-(\d+)/bolum-(\d+)/?$""")
    private val flatEpisodeRegex   = Regex("""/bolum-(\d+)/?$""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/?sayfa=$page"
        val document = app.get(url).document

        val home = document.select("a.dzy-title-card__link").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val img  = this.selectFirst("img")

        val posterUrl = fixUrlNull(img?.attr("src"))
        val title = img?.attr("alt")?.trim()?.removeSuffix("posteri")?.trim()?.ifBlank { null } ?: return null

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
        val document = app.get("$mainUrl/arama/", params = mapOf("q" to query)).document

        return document.select("a.dzy-title-card__link").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isMovie  = url.contains("/film/")

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val year = document.selectFirst(".dzy-detail__fact--year")?.text()?.trim()?.toIntOrNull()
        val tags = document.select(".dzy-detail__fact--genre").map { it.text().trim() }.filter { it.isNotBlank() }
        val score = document.selectFirst(".dzy-detail__fact--rating")?.text()
            ?.replace(Regex("[^0-9,.]"), "")?.replace(",", ".")?.toDoubleOrNull()
            ?.let { Score.from10(it) }

        val description = (document.selectFirst(".dzy-summary__text") ?: document.selectFirst(".dzy-season-summary"))
            ?.text()?.trim()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.score     = score
            }
        }

        val episodes = document.select("a.dzy-episode-row").mapNotNull { epEl ->
            val epHref = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null

            val seasonMatch = seasonEpisodeRegex.find(epHref)
            val season: Int
            val epNum: Int?

            if (seasonMatch != null) {
                season = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                epNum  = seasonMatch.groupValues[2].toIntOrNull()
            } else {
                val flatMatch = flatEpisodeRegex.find(epHref) ?: return@mapNotNull null
                season = 1
                epNum  = flatMatch.groupValues[1].toIntOrNull()
            }

            val epName = epEl.selectFirst(".dzy-episode-row__label em")?.text()?.trim()
                ?.removePrefix("(")?.removeSuffix(")")?.trim()?.ifBlank { null }
            val epScore = epEl.selectFirst(".dzy-imdb-rating-badge")?.text()?.trim()
                ?.replace(",", ".")?.toDoubleOrNull()?.let { Score.from10(it) }

            newEpisode(epHref) {
                this.season  = season
                this.episode = epNum
                this.name    = epName
                this.score   = epScore
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
        // ! Bkz. dosya başındaki not: video oynatıcı Cloudflare Turnstile doğrulaması arkasında ve
        // ! token istemci tarafında (JS ile) üretiliyor; ham HTML'de hiçbir iz bulunmuyor. Yine de
        // ! olası bir sunucu tarafı iframe için sayfa taranır.
        val document = app.get(data).document

        var found = false

        document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }.forEach { videoUrl ->
            loadExtractor(fixUrl(videoUrl), data, subtitleCallback, callback)
            found = true
        }

        return found
    }
}
