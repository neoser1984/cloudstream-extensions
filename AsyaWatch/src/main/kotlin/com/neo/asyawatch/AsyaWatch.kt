// ! AsyaWatch (asyawatch.com) eklentisi.
// !
// ! Site notları:
// ! - Web Drama Turkey ekibinin (bkz. WebDramaTurkey.kt) reklam/kalite şikayetleri üzerine kurduğu
// !   "daha iyi bir asya platformu" - Next.js (Pages Router) tabanlı, "Macellan" adlı bir platform
// !   altyapısı kullanıyor (resim adresleri "images.macellan.online" / "file.macellan.online").
// ! - Her sayfanın HTML'inde "__NEXT_DATA__" script etiketi içinde, "props.pageProps.secureData"
// !   alanında BASE64 ile kodlanmış (şifrelenmemiş, sadece base64) tam bir JSON veri yapısı
// !   bulunuyor - dizi/film meta verisi, sezon/bölüm listesi ve video kaynakları dahil HER ŞEY
// !   burada. Bu sayede ayrı bir arama/liste API'sine gerek kalmadan detay sayfaları tek istekle
// !   tamamen parse edilebiliyor.
// ! - Ana sayfadaki "/dizi-izle" ve "/film-izle" sayfaları sabit (statik, sayfalanmayan) bir
// !   vitrin gösteriyor; gerçek, sayfalanabilir katalog site içi bir JSON API üzerinden geliyor:
// !   POST /api/bg/findSeries?...&currentPage={n}&currentPageCount=24  (diziler)
// !   POST /api/bg/findMovies?...&currentPage={n}&currentPageCount=24  (filmler)
// !   Yanıt yine {"response":"<base64 JSON>"} şeklinde - decode edilince {"pagination":{"hasMore":
// !   bool},"result":[{...}]} formatında geliyor. Bu eklenti ana sayfa listelemesi için doğrudan bu
// !   API'yi kullanıyor (HTML kazımaya gerek yok).
// ! - Arama da aynı base64 zarfını kullanan temiz bir JSON API: POST /api/bg/searchContent?searchterm={q}
// !   -> [{"used_slug":..,"used_type":"Series"|"Movie","object_name":..,"object_poster_url":..}]
// ! - Detay sayfası URL'leri: "/dizi/<slug>" (dizi), "/film/<slug>" (film, aynı sayfa izleme
// !   sayfası olarak da kullanılıyor). Bölüm URL'leri: "/dizi/<slug>/sezon-<N>/bolum-<N>".
// ! - Sezon/bölüm listesi decode edilen JSON'da "RelatedResults.getSerieSeasonAndEpisodes.result"
// !   altında, her bölümün "used_slug" alanı doğrudan tam bölüm yolunu veriyor.
// ! - VİDEO KAYNAKLARI: Hem bölüm hem film (parça/dublaj varyantı) sayfalarının decode edilmiş
// !   JSON'unda, "source_content" adlı alan(lar) altında düz bir "<iframe src=\"...\">" HTML'i
// !   bulunuyor (anahtar ismi içeriğe göre değişebiliyor: "getEpisodeSources", "getMovieSourcesById",
// !   "getMoviePartSourcesById_<id>" gibi - bu yüzden JSON ağacı "source_content" alanı için
// !   TAMAMEN taranıyor, belirli bir anahtar yoluna bağlı kalınmıyor). Gözlemlenen tüm örneklerde
// !   kaynak, sitenin kendi barındırdığı "pichive.online" adresine işaret eden bir iframe - bu adres
// !   Cloudflare bot koruması ile korunuyor ve otomatikleştirilmiş isteklerde "Sorry, you have been
// !   blocked" hatası dönebiliyor. loadLinks() yine de bulunan TÜM iframe adreslerini loadExtractor()
// !   ile dener (try/catch içinde, best-effort) - ağ koşullarına/CloudStream istemcisine göre
// !   çalışabilir ya da çalışmayabilir; bu, projedeki WebDramaTurkey ile aynı sınıf bir kısıt ama en
// !   azından burada JS ile üretilen bir hash değil, doğrudan statik/parse edilebilir bir URL var.

package com.neo.asyawatch

import android.util.Base64
import java.net.URLEncoder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AsyaWatch : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("asyawatch", "https://asyawatch.com")
    override var name                 = "AsyaWatch"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "series" to "Diziler",
        "movies" to "Filmler",
    )

    private val iframeSrcRegex = Regex("src=\"([^\"]+)\"")

    private fun decodeSecureResponse(raw: String): String? {
        return try {
            val root = jacksonObjectMapper().readTree(raw)
            val b64 = root.get("response")?.asText().takeUnless { it.isNullOrBlank() } ?: return null
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSecureDataFromNextData(nextDataText: String): String? {
        return try {
            val root = jacksonObjectMapper().readTree(nextDataText)
            val b64 = root.at("/props/pageProps/secureData").asText()
            if (b64.isBlank()) return null
            String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun collectSourceContents(node: JsonNode, out: MutableList<String>) {
        if (node.isObject) {
            node.fields().forEach { entry ->
                if (entry.key == "source_content" && entry.value.isTextual) {
                    out.add(entry.value.asText())
                } else {
                    collectSourceContents(entry.value, out)
                }
            }
        } else if (node.isArray) {
            node.forEach { collectSourceContents(it, out) }
        }
    }

    private fun JsonNode.toSearchResult(mainUrl: String, isMovie: Boolean): SearchResponse? {
        val slug = this.get("used_slug")?.asText().takeUnless { it.isNullOrBlank() } ?: return null
        val title = this.get("original_title")?.asText()?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        val href = "$mainUrl/$slug"
        val posterUrl = fixUrlNull(
            this.get("square_url")?.asText().takeUnless { it.isNullOrBlank() }
                ?: this.get("poster_url")?.asText()
        )

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isSeries = request.data == "series"
        val endpoint = if (isSeries) "findSeries" else "findMovies"
        val url = "$mainUrl/api/bg/$endpoint" +
            "?releaseYearStart=1900&releaseYearEnd=2100&imdbPointMin=0&imdbPointMax=10" +
            "&categoryIdsComma=&countryIdsComma=&orderType=date_desc&languageId=-1" +
            "&currentPage=$page&currentPageCount=24&queryStr=&categorySlugsComma=&countryCodesComma="

        val raw = try {
            app.post(url).text
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }

        val decodedJson = decodeSecureResponse(raw) ?: return newHomePageResponse(request.name, emptyList())
        val root = try { jacksonObjectMapper().readTree(decodedJson) } catch (e: Exception) { return newHomePageResponse(request.name, emptyList()) }

        val results = root.get("result")
        val items = if (results != null && results.isArray) {
            results.mapNotNull { it.toSearchResult(mainUrl, !isSeries) }
        } else {
            emptyList()
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val raw = try {
            app.post("$mainUrl/api/bg/searchContent?searchterm=$encodedQuery").text
        } catch (e: Exception) {
            return emptyList()
        }

        val decodedJson = decodeSecureResponse(raw) ?: return emptyList()
        val root = try { jacksonObjectMapper().readTree(decodedJson) } catch (e: Exception) { return emptyList() }
        val results = root.get("result") ?: return emptyList()
        if (!results.isArray) return emptyList()

        return results.mapNotNull { item ->
            val slug = item.get("used_slug")?.asText().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            val title = item.get("object_name")?.asText()?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            val href = "$mainUrl/$slug"
            val posterUrl = fixUrlNull(item.get("object_poster_url")?.asText())
            val isMovie = item.get("used_type")?.asText() == "Movie"

            if (isMovie) {
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

        val nextDataText = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val decodedJson = decodeSecureDataFromNextData(nextDataText) ?: return null
        val root = try { jacksonObjectMapper().readTree(decodedJson) } catch (e: Exception) { return null }

        val contentItem = root.get("contentItem") ?: return null
        val title = contentItem.get("original_title")?.asText()?.trim().takeUnless { it.isNullOrBlank() } ?: return null

        val poster = fixUrlNull(
            contentItem.get("square_url")?.asText().takeUnless { it.isNullOrBlank() }
                ?: contentItem.get("poster_url")?.asText()
        )
        val plot = contentItem.get("description")?.asText()?.trim().takeUnless { it.isNullOrBlank() }
        val year = contentItem.get("release_year")?.takeIf { it.isInt }?.asInt()
        val tags = contentItem.get("categories")?.asText()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.tags      = tags
            }
        }

        val seasons = root.at("/RelatedResults/getSerieSeasonAndEpisodes/result")
        val episodes = mutableListOf<Episode>()

        if (seasons.isArray) {
            seasons.forEach { season ->
                val seasonNo = season.get("season_no")?.takeIf { it.isInt }?.asInt() ?: 1
                val eps = season.get("episodes")
                if (eps != null && eps.isArray) {
                    eps.forEach { ep ->
                        val epSlug = ep.get("used_slug")?.asText().takeUnless { it.isNullOrBlank() } ?: return@forEach
                        val epNo = ep.get("episode_no")?.takeIf { it.isInt }?.asInt()

                        episodes.add(
                            newEpisode("$mainUrl/$epSlug") {
                                this.season  = seasonNo
                                this.episode = epNo
                            }
                        )
                    }
                }
            }
        }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = plot
            this.year      = year
            this.tags      = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            return false
        }

        val nextDataText = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return false
        val decodedJson = decodeSecureDataFromNextData(nextDataText) ?: return false
        val root = try { jacksonObjectMapper().readTree(decodedJson) } catch (e: Exception) { return false }

        val sourceContents = mutableListOf<String>()
        collectSourceContents(root, sourceContents)

        val urls = sourceContents
            .mapNotNull { html -> iframeSrcRegex.find(html)?.groupValues?.get(1) }
            .distinct()

        if (urls.isEmpty()) return false

        var found = false
        for (rawUrl in urls) {
            val fixedUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
            try {
                if (loadExtractor(fixedUrl, data, subtitleCallback, callback)) found = true
            } catch (e: Exception) {
                // Desteklenmeyen/erişilemeyen kaynak (ör. Cloudflare engeli) - sıradaki URL'e geç.
            }
        }

        return found
    }
}
