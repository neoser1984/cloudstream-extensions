// ! Bu araç NeO tarafından yazılmıştır.
// ! Kekik-cloudstream/DiziPal (keyiflerolsun) tabanlı, domains.json ile adres yönetimi eklenmiştir.

package com.neo.dizipal2121

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class DiziPal2121 : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("dizipal2121", "https://dizipal2121.com")
    override var name                 = "DiziPal 2121"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    // ! 2026-08 site yenilemesiyle "/koleksiyon/*" -> "/platform/*" ve "/tur/*" -> "/kategori/*" oldu,
    // ! ayrıca bazı platform slug'ları değişti (disney -> disney-plus, amazon-prime -> prime-video).
    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/son-bolumler"                          to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/platform/netflix"                              to "Netflix",
        "${mainUrl}/platform/exxen"                                to "Exxen",
        "${mainUrl}/platform/blutv"                                to "BluTV",
        "${mainUrl}/platform/disney-plus"                          to "Disney+",
        "${mainUrl}/platform/prime-video"                          to "Amazon Prime",
        "${mainUrl}/platform/max"                                  to "HBO Max",
        "${mainUrl}/platform/tabii"                                to "Tabii",
        "${mainUrl}/kategori/bilim-kurgu"                          to "Bilim Kurgu",
        "${mainUrl}/kategori/komedi"                                to "Komedi",
        "${mainUrl}/kategori/belgesel"                              to "Belgesel",
    )

    // ! Site 2026-08 civarında tamamen yenilendi, kartlar artık tek tip "li.content-card" kullanıyor.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = document.select("li.content-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href  = fixUrlNull(this.selectFirst("a.card-link")?.attr("href")) ?: return null
        val title = this.selectFirst("h3.card-title")?.text()?.trim() ?: return null
        val img   = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        )

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    private fun AjaxSearchItem.toSearchResult(): SearchResponse? {
        val title = this.title ?: return null
        val href  = this.url ?: return null

        return if (this.type == "Film") {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    // ! 2026-08 yenilemesiyle eski "/api/search-autocomplete" (POST) kapandı; yerine
    // ! "/ajax-search?q=..." (GET) geldi. Yanıt: {"success":true,"results":[{"title","type":"Dizi"|"Film","poster","url"},...]}
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val responseRaw = app.get(
                "${mainUrl}/ajax-search",
                params  = mapOf("q" to query),
                referer = "${mainUrl}/"
            ).text

            val parsed = jacksonObjectMapper().readValue<AjaxSearchResponse>(responseRaw)
            parsed.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
        } catch (e: Exception) {
            Log.d("DZP2121", "search hatası » ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // ! Sayfanın schema.org JSON-LD bloğu; CSS seçicilerinden çok daha kararlı.
        val ldItem = document.selectFirst("script[type=\"application/ld+json\"]")?.data()?.let {
            try {
                jacksonObjectMapper().readValue<LdJsonRoot>(it).graph
                    ?.firstOrNull { item -> item.type == "TVSeries" || item.type == "Movie" }
            } catch (e: Exception) {
                Log.d("DZP2121", "ld+json parse hatası » ${e.message}")
                null
            }
        }

        val title = ldItem?.name?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(ldItem?.image)
            ?: fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))

        val year        = ldItem?.datePublished
        val description = ldItem?.description?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("[name='description']")?.attr("content")
        val tags        = document.select("a[href*=\"/kategori/\"]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val score       = ldItem?.aggregateRating?.ratingValue?.let { Score.from10(it) }

        return if (url.contains("/dizi/")) {
            val episodes = document.select("a.detail-episode-item").mapNotNull {
                val epHref  = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epName  = it.selectFirst("div.detail-episode-title")?.text()?.trim()
                val subInfo = it.selectFirst("div.detail-episode-subtitle")?.text()?.trim() ?: ""
                val nums    = Regex("""(\d+)""").findAll(subInfo).map { m -> m.value.toIntOrNull() }.toList()

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = nums.getOrNull(0)
                    this.episode = nums.getOrNull(1)
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = score
            }
        }
    }

    // ! 2026-08 site yenilemesiyle player artık base64 + JSON "data-cfg" üzerinden geliyor:
    // !   #videoContainer[data-cfg] -> base64 -> {"v":"<embed/iframe/mp4/m3u8 url>","t":"embed|iframe|mp4|m3u8","p":"<poster>"}
    // ! "m3u8"/"mp4" ise v doğrudan oynatılabilir link; aksi halde v bir reklamlı embed sayfasıdır ve içinden
    // ! jwplayer/vast tarzı `file:"..."` linki regex ile çekilir (reklamsız oynatım).
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP2121", "data » $data")
        val document = app.get(data).document

        val cfgB64 = document.selectFirst("#videoContainer")?.attr("data-cfg")
        if (cfgB64.isNullOrBlank()) {
            // ! Yedek: olası eski/alternatif şablonlarda doğrudan iframe seçicileri
            val iframe = document.selectFirst(".series-player-container iframe")?.attr("src")
                ?: document.selectFirst("div#vast_new iframe")?.attr("src")
                ?: return false

            return extractFromEmbed(iframe, subtitleCallback, callback)
        }

        val cfgJson = try {
            String(Base64.decode(cfgB64, Base64.DEFAULT))
        } catch (e: Exception) {
            Log.d("DZP2121", "cfg base64 decode hatası » ${e.message}")
            return false
        }

        val cfg = try {
            jacksonObjectMapper().readValue<PlayerCfg>(cfgJson)
        } catch (e: Exception) {
            Log.d("DZP2121", "cfg json parse hatası » ${e.message}")
            return false
        }
        Log.d("DZP2121", "cfg » type=${cfg.t} v=${cfg.v}")

        if (cfg.t == "m3u8" || cfg.t == "mp4") {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = fixUrl(cfg.v),
                    type   = if (cfg.t == "m3u8") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "${mainUrl}/"
                    this.quality = Qualities.Unknown.value
                }
            )

            return true
        }

        return extractFromEmbed(cfg.v, subtitleCallback, callback)
    }

    private suspend fun extractFromEmbed(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iSource = app.get(embedUrl, referer = "${mainUrl}/").text
        val m3uLink = Regex("""file:"([^"]+)""").find(iSource)?.groupValues?.get(1)
        if (m3uLink == null) {
            Log.d("DZP2121", "iSource » $iSource")
            return loadExtractor(embedUrl, "${mainUrl}/", subtitleCallback, callback)
        }

        val subtitles = Regex(""""subtitle":"([^"]+)""").find(iSource)?.groupValues?.get(1)
        if (subtitles != null) {
            subtitles.split(",").forEach {
                val subLang = it.substringAfter("[").substringBefore("]")
                val subUrl  = it.replace("[${subLang}]", "")

                subtitleCallback.invoke(SubtitleFile(lang = subLang, url = fixUrl(subUrl)))
            }
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = m3uLink,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "${mainUrl}/"
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AjaxSearchResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("results") val results: List<AjaxSearchItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AjaxSearchItem(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("type") val type: String? = null, // "Dizi" | "Film"
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("rating") val rating: String? = null
)

data class PlayerCfg(
    @JsonProperty("v") val v: String,
    @JsonProperty("t") val t: String,
    @JsonProperty("p") val p: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LdJsonRoot(
    @JsonProperty("@graph") val graph: List<LdJsonItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LdJsonItem(
    @JsonProperty("@type") val type: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("datePublished") val datePublished: Int? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("aggregateRating") val aggregateRating: LdJsonRating? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LdJsonRating(
    @JsonProperty("ratingValue") val ratingValue: String? = null
)
