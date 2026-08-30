// ! DiziGom (dizigom.biz) eklentisi.
// !
// ! Site notları:
// ! - Sadece dizi içeriği var, film bölümü yok (nav: Anasayfa / Dizi izle / İletişim).
// ! - Listeleme sayfası (dizi-izle) statik HTML, "div.single-item" kartları içeriyor;
// !   sayfalama "dizi-izle/page/{n}/" şeklinde.
// ! - Arama, WordPress admin-ajax.php üzerinden çalışıyor (action=data_fetch). İstek için
// !   sayfa HTML'inde gömülü "live_search_ajax.admin_ajax_nonce" değeri gerekiyor; bu yüzden
// !   önce ana sayfa çekilip nonce regex ile çıkarılıyor, sonra POST atılıyor.
// ! - Dizi detay sayfasında TÜM sezonların bölümleri tek seferde statik HTML içinde geliyor
// !   (sezon sekmeleri sadece görsel/JS ile gizleniyor), ayrı istek gerekmiyor.
// ! - Bölüm sayfasında video kaynağı tek bir <iframe src="..."> olarak statik HTML'de geliyor
// !   (play2.pilavyerplay.top gibi 3. parti bir oynatıcı). Bu oynatıcı, klasik bir HLS/m3u8
// !   sunmuyor; bunun yerine sıralı numaralandırılmış, çoklu CDN'e (round-robin) dağılmış,
// !   ".txt" uzantılı özel parçalanmış (chunked) ses/video akışları kullanıyor ve bunları
// !   tarayıcıda MediaSource Extensions ile birleştiriyor. CloudStream'in standart
// !   extractor/ExoPlayer altyapısı bu özel protokolü doğrudan oynatamaz; bu yüzden iframe
// !   adresi genel loadExtractor() akışına bırakılıyor (bilinen bir extractor eşleşirse
// !   oynatılır, eşleşmezse bu tek kaynak çalışmayabilir).

package com.neo.dizigom

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class DiziGom : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("dizigom", "https://www.dizigom.biz")
    override var name                 = "DiziGom"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/dizi-izle/" to "Diziler",
    )

    private val seasonEpisodeRegex = Regex("""-(\d+)-sezon-(\d+)-bolum""")
    private val nonceRegex = Regex(""""admin_ajax_nonce"\s*:\s*"([a-f0-9]+)"""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document

        val home = document.select("div.single-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("div.categorytitle a") ?: return null
        val href = fixUrlNull(titleEl.attr("href")) ?: return null
        val title = titleEl.text().trim().ifBlank { null } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("div.cat-img img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
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

            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title = rawTitle.substringBefore(" izle -").trim().ifBlank { rawTitle }

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val infoMap = mutableMapOf<String, String>()
        document.select("span.dizimeta").forEach { span ->
            val div = span.parent() ?: return@forEach
            val label = span.text().trim().trimEnd(':').trim()
            val value = div.ownText().trim()
            if (label.isNotBlank()) infoMap[label] = value
        }
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
