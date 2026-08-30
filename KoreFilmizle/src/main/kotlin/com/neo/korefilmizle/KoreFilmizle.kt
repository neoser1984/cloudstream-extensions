// ! KoreFilmizle (korefilmizle.com) eklentisi.
// !
// ! Site notları:
// ! - DiziGom (dizigom.biz) ile AYNI WordPress teması ("diziplus") kullanılıyor; birçok
// !   seçici (span.dizimeta, div.genres, .bolum-ismi, admin-ajax arama mekanizması) birebir
// !   aynı. Farkı: bu site hem dizi ("/diziler/", "/dizi/<slug>/") hem film
// !   ("/filmler/", "/film/<slug>/") içeriyor ve listeleme kartları "div.dizi-box"
// !   (grid görünüm) kullanıyor; DiziGom'daki "div.single-item" (liste görünümü) burada yok.
// ! - Film sayfası ("/film/<slug>/") doğrudan izleme sayfasıdır (ayrı bölüm/detay ayrımı
// !   yok); dizi sayfası ("/dizi/<slug>/") ise DiziGom'daki gibi tüm sezon/bölümleri tek
// !   seferde statik HTML içinde listeler.
// ! - Video kaynağı, dizi bölümlerinde "ksdpictures.site/dzembed.php?id=..." adresli,
// !   filmlerde ise "yabancidizim.com/rplayer/<hex>.html" adresli 3. parti oynatıcılar
// !   üzerinden geliyor (ikisi de farklı, kayıtlı bir CloudStream extractor'ı olmayan özel
// !   servisler). Bu yüzden iframe adresi genel loadExtractor() akışına bırakılıyor.
// ! - Arama (admin-ajax.php, action=data_fetch) DiziGom ile aynı şekilde uygulandı; ancak
// !   test sırasında bu sitede sonuç dönmediği (site tarafı bir sorun olabilir) gözlemlendi.
// !   Mekanizma doğru olduğu için olduğu gibi bırakıldı; site düzeltilirse çalışacaktır.

package com.neo.korefilmizle

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class KoreFilmizle : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("korefilmizle", "https://korefilmizle.com")
    override var name                 = "KoreFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler/" to "Diziler",
        "$mainUrl/filmler/" to "Filmler",
    )

    private val seasonEpisodeRegex = Regex("""-(\d+)-sezon-(\d+)-bolum""")
    private val yearSuffixRegex    = Regex("""\s+\d{4}$""")
    private val nonceRegex = Regex(""""admin_ajax_nonce"\s*:\s*"([a-f0-9]+)"""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val home = document.select("div.dizi-box").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("div.serie-name a") ?: return null
        val href = fixUrlNull(titleEl.attr("href")) ?: return null
        val title = titleEl.text().trim().ifBlank { null } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.poster img")?.attr("src"))

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val nonceDoc = try {
            app.get(mainUrl).text
        } catch (e: Exception) {
            return emptyList()
        }

        val nonce = nonceRegex.find(nonceDoc)?.groupValues?.get(1) ?: return emptyList()

        val params = mapOf(
            "action" to "data_fetch",
            "keyword" to query,
            "_wpnonce" to nonce,
        )

        val response = try {
            app.post("$mainUrl/wp-admin/admin-ajax.php", data = params).document
        } catch (e: Exception) {
            return emptyList()
        }

        return response.select("div.searchelement").mapNotNull { el ->
            val titleEl = el.select("a[href]").firstOrNull { it.text().isNotBlank() } ?: return@mapNotNull null
            val href = fixUrlNull(titleEl.attr("href")) ?: return@mapNotNull null
            val title = titleEl.text().trim().ifBlank { null } ?: return@mapNotNull null
            val posterUrl = fixUrlNull(el.selectFirst("img")?.attr("src"))

            if (href.contains("/film/")) {
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

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        if (isMovie) {
            val title = document.selectFirst("h1")?.text()?.trim() ?: return null
            val year = document.selectFirst("span.yil")?.text()?.trim()?.removeSurrounding("(", ")")?.toIntOrNull()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
            }
        }

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title = rawTitle.substringBefore(" - Kore Dizi izle").replace(yearSuffixRegex, "").trim().ifBlank { rawTitle }

        val infoMap = mutableMapOf<String, String>()
        document.select("span.dizimeta").forEach { span ->
            val div = span.parent() ?: return@forEach
            val label = span.text().trim().trimEnd(':').trim()
            val value = div.ownText().trim()
            if (label.isNotBlank()) infoMap[label] = value
        }
        val year  = infoMap["Yapım Yılı"]?.toIntOrNull()
        val score = infoMap["IMDB"]?.toDoubleOrNull()?.let { Score.from10(it) }

        val tags = document.select("div.genres a").map { it.text().trim() }.filter { it.isNotBlank() }

        val episodes = document.select("a[href*='-sezon-'][href*='-bolum']").mapNotNull { epLink ->
            val epHref = fixUrlNull(epLink.attr("href")) ?: return@mapNotNull null
            val match = seasonEpisodeRegex.find(epHref) ?: return@mapNotNull null
            val season = match.groupValues[1].toIntOrNull() ?: 1
            val epNum = match.groupValues[2].toIntOrNull()
            val epName = epLink.selectFirst(".bolum-ismi")?.text()?.trim()?.removeSurrounding("(", ")")?.ifBlank { null }

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
            this.tags      = tags
            this.score     = score
            this.year      = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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
