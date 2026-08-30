// ! Bu araç NeO tarafından yazılmıştır.
// ! Kekik-cloudstream/HDFilmCehennemi (asıl kaynağı: hexated/cloudstream-extensions-hexated) tabanlı,
// ! domains.json ile adres yönetimi ve güncel Kotlin/CloudStream API'lerine (Score, newExtractorLink) uyarlanmıştır.
// ! 2026-08 itibarıyla canlı site üzerinde seçiciler (selectors) doğrulanmıştır.

package com.neo.hdfilmcehennemi

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("hdfilmcehennemi", "https://www.hdfilmcehennemi.nl")
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl                                             to "Yeni Eklenen Filmler",
        "${mainUrl}/yabancidiziizle-2"                      to "Yeni Eklenen Diziler",
        "${mainUrl}/category/tavsiye-filmler-izle2"         to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmler"              to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar-1"                 to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle"         to "En Çok Beğenilenler",
        "${mainUrl}/tur/aile-filmleri-izleyin-6"            to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-3"         to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-4"     to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel-filmlerini-izle-1"         to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-2"   to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/komedi-filmlerini-izleyin-1"        to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmlerini-izle-2/"           to "Korku Filmleri",
        "${mainUrl}/tur/romantik-filmleri-izle-1"           to "Romantik Filmleri",
    )

    // ! Kart yapısı: <a class="poster" href="/film-slug/"><img data-src="..."><strong class="poster-title">Ad</strong></a>
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = document.select("div.section-content a.poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("strong.poster-title")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ! Arama: /search?q=... , "X-Requested-With: fetch" header'ı ile JSON döner - "results" alanı
    // ! her biri ayrı bir HTML parçası (<a class="search-result">...) olan string listesidir.
    override suspend fun search(query: String): List<SearchResponse> {
        val response      = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val score       = document.selectFirst("div.post-info-imdb-rating span")?.text()?.substringBefore("(")?.trim()?.toDoubleOrNull()?.let { Score.from10(it) }
        val actors      = document.select("div.post-info-cast a").mapNotNull {
            val name = it.selectFirst("strong")?.text() ?: return@mapNotNull null
            Actor(name, it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/")?.let { "https://www.youtube.com/embed/$it" }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = score
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.score           = score
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // ! Oynatıcı: sayfada "div.alternative-links" bloklarında (dil bazlı, data-lang) birden çok
    // ! "button.alternative-link" kaynağı var (data-video -> id). O id ile /video/{id}/ AJAX çağrısı
    // ! yapılıp dönen HTML'den iframe (rplayer/rapidrame) adresi çekiliyor; iframe sayfasındaki
    // ! packed (eval(function(p,a,c,k,e,d))) jwplayer script'i unpack edilip video adresi decodeDcPayload
    // ! ile çözülüyor (aşağıdaki extractFromRapid/decodeDcPayload'a bakınız).
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("HDCH", "data » $data")
        val document = app.get(data).document

        document.select("div.alternative-links").map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                try {
                    val apiGet = app.get(
                        "${mainUrl}/video/$videoID/",
                        headers = mapOf(
                            "Content-Type"     to "application/json",
                            "X-Requested-With" to "fetch"
                        ),
                        referer = data
                    ).text

                    var iframe = Regex("""data-src=\\"([^"]+)""").find(apiGet)?.groupValues?.get(1)?.replace("\\", "") ?: return@forEach
                    if (iframe.contains("?rapidrame_id=")) {
                        iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=")
                    }

                    Log.d("HDCH", "$source » $videoID » $iframe")
                    extractFromRapid(source, fixUrl(iframe), subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.d("HDCH", "kaynak hatası » ${e.message}")
                }
            }
        }

        return true
    }

    // ! Oynatıcı sayfası 2026-08 itibarıyla artık düz "file_link=\"...\";" yerine, her yüklemede
    // ! rastgele isimli bir "dc_XXX(value_parts)" fonksiyonuyla video adresini üretiyor. Fonksiyon
    // ! gövdesi (adım sırası: atob / harf-kaydırma(rotN) / ters çevirme - hangileri, kaç kez ve hangi
    // ! sırada değişebiliyor) ile son "unmix" XOR-mod adımının sabitleri (CONST, OFFSET) her video için
    // ! farklı ama HER SEFERİNDE unpack edilen script'in kendi içinde açıkça yazılı - yani rastgele
    // ! olsa da script'ten okunup uygulanabilir (FilmMakinesi'nin "Close" kaynağındaki gibi algoritmanın
    // ! kendisi rastgele DEĞİL, sadece parametreleri rastgele). decodeDcPayload bunu genel şekilde çözer.
    private suspend fun extractFromRapid(
        source: String,
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val script   = app.get(iframeUrl, referer = "${mainUrl}/").document.select("script").find { it.data().contains("sources:") }?.data() ?: return
        val unpacked = getAndUnpack(script)

        val decodedUrl = decodeDcPayload(unpacked) ?: return
        val subData     = script.substringAfter("tracks: [").substringBefore("]")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = source,
                url    = decodedUrl,
                type   = if (decodedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "${mainUrl}/"
                this.quality = Qualities.Unknown.value
            }
        )

        AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" }?.forEach {
            val subUrl = it.file?.let { f -> fixUrl(f) } ?: return@forEach
            subtitleCallback.invoke(SubtitleFile(it.label ?: "TR", subUrl))
        }
    }

    // ! "dc_XXX" fonksiyonunun gövdesini script'ten regex ile ayıklar, içindeki adımları (atob /
    // ! harf-kaydırma / ters çevirme) SIRASIYLA uygular, sonra son XOR-mod ("unmix") adımını kendi
    // ! sabitleriyle (CONST, OFFSET) çalıştırır. Adımların sırası/sayısı ve sabitler videodan videoya
    // ! değişse de yapı (üç olası adım + tek tip unmix döngüsü) hep aynı kalıpta.
    private fun decodeDcPayload(unpacked: String): String? {
        val funcMatch = Regex("""function\s+dc_\w+\(value_parts\)\{([\s\S]*?)return unmix\}""").find(unpacked)
            ?: return null
        val body = funcMatch.groupValues[1]

        val callMatch = Regex("""dc_\w+\(\[([\s\S]*?)\]\)""").find(unpacked) ?: return null
        val parts = AppUtils.tryParseJson<List<String>>("[${callMatch.groupValues[1]}]") ?: return null
        var result = parts.joinToString("")

        val opsSection = body.substringBefore("let unmix")
        val opRegex = Regex("""result\s*=\s*(atob\(result\)|result\.split\(''\)\.reverse\(\)\.join\(''\)|result\.replace\(/\[a-zA-Z\]/g[\s\S]*?\+(\d+)\)%26\+base\)\}\))""")
        opRegex.findAll(opsSection).forEach { m ->
            val stmt = m.groupValues[1]
            result = when {
                stmt.startsWith("atob(")     -> jsAtob(result)
                stmt.contains("reverse")     -> result.reversed()
                stmt.contains("replace(/[a-zA-Z]/g") -> jsRot(result, m.groupValues[2].toIntOrNull() ?: 0)
                else -> result
            }
        }

        val constMatch = Regex("""(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""").find(body) ?: return null
        val constVal   = constMatch.groupValues[1].toLongOrNull() ?: return null
        val offset     = constMatch.groupValues[2].toIntOrNull() ?: return null

        val sb = StringBuilder()
        for (i in result.indices) {
            val charCode = result[i].code
            val modResult = (constVal % (i + offset)).toInt()
            var newCode = (charCode - modResult) % 256
            if (newCode < 0) newCode += 256
            sb.append(newCode.toChar())
        }

        return sb.toString().ifBlank { null }
    }

    // ! JS'in atob()'una eşdeğer: base64 çöz, her baytı ayrı bir karakter olarak (Latin-1) tut.
    private fun jsAtob(s: String): String {
        val bytes = android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        return String(bytes, Charsets.ISO_8859_1)
    }

    // ! JS'teki (o - base + n) % 26 + base kaydırmasının birebir aynısı - sadece a-z/A-Z etkilenir.
    private fun jsRot(s: String, n: Int): String {
        return s.map { c ->
            when (c) {
                in 'a'..'z' -> 'a' + (((c - 'a') + n).mod(26))
                in 'A'..'Z' -> 'A' + (((c - 'A') + n).mod(26))
                else -> c
            }
        }.joinToString("")
    }

    private data class SubSource(
        @JsonProperty("file")  val file: String?  = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind")  val kind: String?  = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
}
