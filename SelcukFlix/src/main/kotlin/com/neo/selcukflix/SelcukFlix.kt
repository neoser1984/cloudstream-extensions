// ! Bu araç NeO tarafından yazılmıştır.
// !
// ! SelcukFlix, sayfalarını Next.js ile sunucu tarafında (SSR) render eder ama asıl veriyi
// ! (dizi/film detayları, bölüm listesi, oynatıcı kaynakları, arama sonuçları) `secureData`
// ! adlı alanda AES ile şifreli gönderir. Bu eklenti o veriyi SelcukCrypto.kt ile çözüp
// ! doğrudan temiz JSON üzerinden çalışır - bu sayede CSS/Tailwind sınıfları değişse bile
// ! kırılma ihtimali çok daha düşüktür.
// !
// ! "Ad-free": Video, sitenin kendi reklamlı oynatıcı arayüzü hiç yüklenmeden, kaynak
// ! iframe'inden (pichive.online) doğrudan m3u8 linki çekilerek oynatılır.

package com.neo.selcukflix

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SelcukFlix : MainAPI() {
    override var mainUrl        = RemoteConfig.getDomain("selcukflix", "https://selcukflix.co")
    override var name           = "SelcukFlix"
    override val hasMainPage    = true
    override var lang           = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val jsonMapper = jacksonObjectMapper()

    // request.data burada gerçek bir URL değil, anasayfanın çözülmüş JSON'undaki alan adıdır.
    override val mainPage = mainPageOf(
        "getTrendSeries"         to "Trend Diziler",
        "getTrendMovies"         to "Trend Filmler",
        "getEpisodesOnNewSeries" to "Güncel Bölümler",
        "getLastMovies"          to "Son Eklenen Filmler",
        "allPopularSeries"       to "Popüler Diziler",
    )

    // ! Anasayfa tek seferde indirilip çözülür, mainPage satırları arasında kısa süreliğine önbelleklenir.
    private var homeCache: JsonNode? = null
    private var homeCacheAt: Long = 0

    private suspend fun fetchHomeDecrypted(): JsonNode? {
        val now = System.currentTimeMillis()
        homeCache?.let { if (now - homeCacheAt < 60_000) return it }

        val decrypted = fetchDecrypted("${mainUrl}/")
        if (decrypted != null) {
            homeCache   = decrypted
            homeCacheAt = now
        }
        return decrypted
    }

    /** Bir sayfayı indirir, __NEXT_DATA__ içindeki secureData'yı çözer ve JSON ağacı olarak döner. */
    private suspend fun fetchDecrypted(url: String): JsonNode? {
        return try {
            val document   = app.get(url, referer = "${mainUrl}/").document
            val rawNextData = document.getElementById("__NEXT_DATA__")?.data() ?: return null
            val nextData    = jsonMapper.readTree(rawNextData)
            val secureNode  = nextData.path("props").path("pageProps").path("secureData")
            if (!secureNode.isTextual) return null

            val plain = SelcukCrypto.decrypt(secureNode.asText()) ?: return null
            jsonMapper.readTree(plain)
        } catch (e: Exception) {
            null
        }
    }

    private fun abs(slug: String): String {
        if (slug.startsWith("http")) return slug
        return "${mainUrl}/${slug.removePrefix("/")}"
    }

    private fun JsonNode.textOrNull(field: String): String? =
        this.path(field).takeIf { it.isTextual }?.asText()?.ifBlank { null }

    private fun JsonNode.intOrNull(field: String): Int? =
        this.path(field).takeIf { it.isNumber }?.asInt()

    private fun JsonNode.scoreOrNull(): Score? =
        this.path("imdb_point").takeIf { it.isNumber }?.let { Score.from10(it.asDouble()) }

    private fun JsonNode.tagsOrNull(): List<String>? =
        this.textOrNull("categories")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

    private fun JsonNode.toSeriesResult(): SearchResponse? {
        val slug  = this.textOrNull("used_slug") ?: return null
        val title = this.textOrNull("original_title") ?: return null
        val poster = this.textOrNull("poster_url") ?: this.textOrNull("face_url")

        return newTvSeriesSearchResponse(title, abs(slug), TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun JsonNode.toMovieResult(): SearchResponse? {
        val slug  = this.textOrNull("used_slug") ?: return null
        val title = this.textOrNull("original_title") ?: return null
        val poster = this.textOrNull("poster_url") ?: this.textOrNull("face_url")

        return newMovieSearchResponse(title, abs(slug), TvType.Movie) { this.posterUrl = poster }
    }

    // ! "Güncel Bölümler" listesindeki bir bölüm, dizinin kendisine bağlanır (DiziPal'daki
    // ! "sonBolumler" mantığının aynısı): .../sezon-X/bolum-Y -> ...
    private fun JsonNode.toEpisodeAsSeriesResult(): SearchResponse? {
        val epSlug     = this.textOrNull("episode_used_slug") ?: return null
        val seriesSlug = epSlug.substringBefore("/sezon")
        val title      = this.textOrNull("original_title") ?: return null
        val poster     = this.textOrNull("poster_url") ?: this.textOrNull("face_url")
        val seasonNo   = this.intOrNull("season_no")
        val episodeNo  = this.intOrNull("episode_no")
        val display    = if (seasonNo != null && episodeNo != null) "$title (${seasonNo}x${episodeNo})" else title

        return newTvSeriesSearchResponse(display, abs(seriesSlug), TvType.TvSeries) { this.posterUrl = poster }
    }

    private fun JsonNode.toSearchApiResult(): SearchResponse? {
        val slug  = this.textOrNull("used_slug") ?: return null
        val title = this.textOrNull("object_name") ?: return null
        val poster = this.textOrNull("object_poster_url")
        val type   = this.textOrNull("used_type")

        return if (type == "Movies") {
            newMovieSearchResponse(title, abs(slug), TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, abs(slug), TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val decrypted = fetchHomeDecrypted()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val arr = decrypted.path(request.data)
        val items: List<SearchResponse> = when (request.data) {
            "getTrendMovies", "getLastMovies" -> arr.mapNotNull { it.toMovieResult() }
            "getEpisodesOnNewSeries"          -> arr.mapNotNull { it.toEpisodeAsSeriesResult() }
            else                              -> arr.mapNotNull { it.toSeriesResult() }
        }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val cipherText = app.post(
                "${mainUrl}/api/bg/searchContent",
                params  = mapOf("searchterm" to query),
                referer = "${mainUrl}/"
            ).text

            val plain = SelcukCrypto.decrypt(cipherText) ?: return emptyList()
            jsonMapper.readTree(plain).path("result").mapNotNull { it.toSearchApiResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val decrypted   = fetchDecrypted(url) ?: return null
        val contentItem = decrypted.path("contentItem")
        val related     = decrypted.path("content").path("result").path("RelatedResults")

        return if (url.contains("/film/")) {
            val title = contentItem.textOrNull("original_title") ?: return null
            val poster = contentItem.textOrNull("poster_url") ?: contentItem.textOrNull("face_url")
            val plot   = contentItem.textOrNull("description")
            val year   = contentItem.intOrNull("release_year")
            val score  = contentItem.scoreOrNull()
            val tags   = contentItem.tagsOrNull()
            val duration = contentItem.intOrNull("total_minutes")

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = plot
                this.year      = year
                this.score     = score
                this.tags      = tags
                this.duration  = duration
            }
        } else {
            val title = contentItem.textOrNull("series_title")
                ?: contentItem.textOrNull("original_title")
                ?: return null
            val poster = contentItem.textOrNull("poster_url") ?: contentItem.textOrNull("face_url")
            val plot   = contentItem.textOrNull("series_description_content")
                ?: contentItem.textOrNull("description")
            val score  = contentItem.scoreOrNull()
            val tags   = contentItem.tagsOrNull()

            val episodes = mutableListOf<Episode>()
            val seasons  = related.path("getSerieSeasonAndEpisodes").path("result")
            if (seasons.isArray) {
                for (season in seasons) {
                    val seasonNo = season.intOrNull("season_no")
                    val epList   = season.path("episodes")
                    if (!epList.isArray) continue

                    for (ep in epList) {
                        val slug = ep.textOrNull("used_slug") ?: continue
                        val epNo = ep.intOrNull("episode_no")
                        val epSubtitle = ep.textOrNull("episode_subtitle")
                        val epText     = ep.textOrNull("episode_text")
                        val epName     = (if (!epSubtitle.isNullOrBlank() && epSubtitle != epText) epSubtitle else null)
                            ?: listOfNotNull(seasonNo?.let { "$it. Sezon" }, epNo?.let { "$it. Bölüm" })
                                .joinToString(" ")
                                .ifBlank { "Bölüm" }

                        episodes.add(
                            newEpisode(abs(slug)) {
                                this.name    = epName
                                this.season  = seasonNo
                                this.episode = epNo
                            }
                        )
                    }
                }
            }
            if (episodes.isEmpty()) return null

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = plot
                this.score     = score
                this.tags      = tags
            }
        }
    }

    /** secureData JSON ağacında "source_content" alanı taşıyan tüm düğümleri (film/dizi fark etmeksizin) toplar. */
    private fun collectSources(node: JsonNode, out: MutableList<JsonNode>) {
        when {
            node.isObject -> {
                if (node.has("source_content")) out.add(node)
                val fieldIterator = node.fields()
                while (fieldIterator.hasNext()) {
                    collectSources(fieldIterator.next().value, out)
                }
            }
            node.isArray -> node.forEach { collectSources(it, out) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val decrypted = fetchDecrypted(data) ?: return false

        val sourceNodes = mutableListOf<JsonNode>()
        collectSources(decrypted, sourceNodes)
        if (sourceNodes.isEmpty()) return false

        var success = false
        for (node in sourceNodes) {
            val html = node.textOrNull("source_content") ?: continue
            var src  = Regex("""src=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: continue
            src = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("/")  -> "${mainUrl}$src"
                else                 -> src
            }

            val langName    = node.textOrNull("language_name") ?: ""
            val qualityName = node.textOrNull("quality_name") ?: ""
            val label       = "$langName $qualityName".trim()

            if (extractFromIframe(src, label, data, subtitleCallback, callback)) success = true
        }

        return success
    }

    /** DiziPal'daki gibi önce ham "file:" imzasını dener, olmazsa CloudStream'in genel çözücüsüne düşer. */
    private suspend fun extractFromIframe(
        iframeUrl: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val res = app.get(iframeUrl, referer = referer).text
            val m3u = Regex("""file\s*:\s*"([^"]+)"""").find(res)?.groupValues?.get(1)
                ?: Regex("""(https?:[^"'\s]+\.m3u8[^"'\s]*)""").find(res)?.groupValues?.get(1)

            if (m3u != null) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = if (label.isNotBlank()) "${this.name} $label" else this.name,
                        url    = m3u,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            } else {
                loadExtractor(iframeUrl, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            false
        }
    }
}
