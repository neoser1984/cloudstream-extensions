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

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/son-bolumler"                          to "Son Bölümler",
        "${mainUrl}/diziler"                                       to "Yeni Diziler",
        "${mainUrl}/filmler"                                       to "Yeni Filmler",
        "${mainUrl}/koleksiyon/netflix"                            to "Netflix",
        "${mainUrl}/koleksiyon/exxen"                              to "Exxen",
        "${mainUrl}/koleksiyon/blutv"                              to "BluTV",
        "${mainUrl}/koleksiyon/disney"                             to "Disney+",
        "${mainUrl}/koleksiyon/amazon-prime"                       to "Amazon Prime",
        "${mainUrl}/tur/bilimkurgu"                                to "Bilimkurgu Filmleri",
        "${mainUrl}/tur/komedi"                                    to "Komedi Filmleri",
        "${mainUrl}/tur/belgesel"                                  to "Belgesel Filmleri",
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

    private fun SearchItem.toPostSearchResult(): SearchResponse {
        val title     = this.title
        val href      = "${mainUrl}${this.url}"
        val posterUrl = this.poster

        return if (this.type == "series") {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val responseRaw = app.post(
                "${mainUrl}/api/search-autocomplete",
                headers = mapOf(
                    "Accept"           to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = "${mainUrl}/",
                data    = mapOf("query" to query)
            )

            val searchItemsMap = jacksonObjectMapper().readValue<Map<String, SearchItem>>(responseRaw.text)
            searchItemsMap.values.map { it.toPostSearchResult() }
        } catch (e: Exception) {
            // ! API adresi değişmiş/kapanmış olabilir - sessizce boş liste dön
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

data class SearchItem(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("tr_title") val trTitle: String,
    @JsonProperty("poster") val poster: String,
    @JsonProperty("genres") val genres: String,
    @JsonProperty("imdb") val imdb: String,
    @JsonProperty("duration") val duration: String,
    @JsonProperty("year") val year: String,
    @JsonProperty("view") val view: Int,
    @JsonProperty("type") val type: String = "defaultType",
    @JsonProperty("url") val url: String
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
