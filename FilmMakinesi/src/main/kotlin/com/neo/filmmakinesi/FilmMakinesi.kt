// ! Bu araç NeO tarafından yazılmıştır.
// ! Kekik-cloudstream/FilmMakinesi tabanlı, domains.json ile adres yönetimi eklenmiştir.
// ! 2026-08 itibarıyla site tamamen yenilenmiş; kart/detay yapısı ve oynatıcı sistemi buna göre yeniden yazıldı.

package com.neo.filmmakinesi

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class FilmMakinesi : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("filmmakinesi", "https://filmmakinesi.to")
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        "${mainUrl}/"                                                       to "Son Filmler",
        "${mainUrl}/filmler-1/"                                             to "Filmler",
        "${mainUrl}/film-izle/olmeden-izlenmesi-gerekenler-fm1/"            to "Ölmeden İzle",
        "${mainUrl}/seri-filmler-izle-1/"                                   to "Seri Filmler",
        "${mainUrl}/ulke/turkiye-fm4/"                                      to "Yerli Filmler",
        "${mainUrl}/tur/aksiyon-fmy54y/film/"                               to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu-fm3/film/"                              to "Bilim Kurgu",
        "${mainUrl}/tur/komedi-fm1/film/"                                   to "Komedi",
        "${mainUrl}/tur/korku-fm2/film/"                                    to "Korku",
        "${mainUrl}/tur/romantik-fm1/film/"                                 to "Romantik",
        "${mainUrl}/tur/gerilim-fm1/film/"                                  to "Gerilim",
        "${mainUrl}/tur/belgesel/film/"                                     to "Belgesel",
    )

    // ! Sayfa 1 doğrudan taban URL, 2+ için "sayfa/N/" ekleniyor (hem anasayfa hem tür/liste sayfaları için geçerli).
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = if (page <= 1) request.data else "${request.data}sayfa/${page}/"
        val document = app.get(url).document
        val home     = document.select("div.film-list a.item:not(.soon)").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    // ! Kart yapısı: <a class="item" href="/film/slug/"><div class="thumbnail-outer"><img></div>
    // !               <div class="item-footer"><div class="title">Ad</div><div class="info">Yıl Süre</div></div></a>
    private fun Element.toSearchResult(): SearchResponse? {
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val title     = this.selectFirst("div.item-footer div.title")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    // ! Arama: /arama/?s=... , sonuçlar da aynı "div.film-list a.item" yapısında geliyor.
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/", params = mapOf("s" to query)).document

        return document.select("div.film-list a.item:not(.soon)").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val infoDiv = document.selectFirst("div.info:has(div.type)")
        val score   = infoDiv?.selectFirst("div.imdb b")?.text()?.trim()?.toDoubleOrNull()?.let { Score.from10(it) }
        val tags    = infoDiv?.select("div.type a")?.map { it.text().trim() }?.filter { it.isNotBlank() }
        val year    = document.select("a[href*='/yil/']").lastOrNull { it.text().trim().matches(Regex("""\d{4}""")) }?.text()?.trim()?.toIntOrNull()

        val actors = document.select("a.cast").mapNotNull { cast ->
            val name = cast.selectFirst("div.cast-name")?.text()?.trim() ?: return@mapNotNull null
            val img  = fixUrlNull(cast.selectFirst("img.cast-img")?.attr("src"))
            ActorData(Actor(name, img))
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = score
            this.actors    = actors
        }
    }

    // ! Oynatıcı: sayfada 2 kaynak var -> "Rapid" (rapid.<domain>/embed-...) ve "Close" (closeload.<domain>).
    // ! Rapid, CloudStream'in RapidVid extractor'ının hedeflediği aynı oynatıcı yazılımını kullanıyor
    // ! ("var played = 0;" imzası eşleşiyor) - o extractor'ın algoritmasını burada uyguluyoruz.
    // ! Close kaynağı ise her sayfa yüklemesinde rastgele değişen (rot miktarları/sırası, XOR sabitleri)
    // ! bir obfuscation kullanıyor - statik olarak güvenilir şekilde çözülemiyor, bu yüzden atlanıyor.
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FLMM", "data » $data")
        val document = app.get(data).document

        val rapidUrl = document.selectFirst("a[data-video_url]")?.attr("data-video_url") ?: return false

        return try {
            extractFromRapid(fixUrl(rapidUrl), subtitleCallback, callback)
        } catch (e: Exception) {
            Log.d("FLMM", "rapid hatası » ${e.message}")
            false
        }
    }

    // ! CloudStream'in resmi RapidVidExtractor.kt dosyasındaki algoritmanın uyarlaması:
    // ! önce düz "\xNN" hex-escaped "file" alanı deneniyor, yoksa jwplayer setup'ı taşıyan
    // ! packed (eval(function(p,a,c,k,e,d))) script iki kez unpack edilip içinden çekiliyor.
    private suspend fun extractFromRapid(
        rapidUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(rapidUrl, referer = "${mainUrl}/").text

        Regex(""""captions","file":"([^"]+)","label":"([^"]+)"""").findAll(html).forEach { m ->
            subtitleCallback.invoke(SubtitleFile(lang = m.groupValues[2], url = fixUrl(m.groupValues[1].replace("\\", ""))))
        }

        var extractedValue = Regex(""""file":\s*"((?:\\x[0-9a-fA-F]{2})+)"""").find(html)?.groupValues?.get(1)
        var decoded: String?

        if (extractedValue != null) {
            val bytes = extractedValue.split("\\x").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            decoded   = bytes.decodeToString()
        } else {
            val evalJWSsetup = Regex("""\};\s*(eval\(function[\s\S]*?)var played\s*=\s*\d+;""").find(html)?.groupValues?.get(1)
                ?: return false
            val jwsSetup   = getAndUnpack(getAndUnpack(evalJWSsetup)).replace("\\\\", "\\")
            extractedValue = Regex(""""file":"(.*?)","label""").find(jwsSetup)?.groupValues?.get(1)?.replace("\\x", "")
                ?: return false

            val bytes = extractedValue.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
            decoded   = bytes.decodeToString()
        }

        if (decoded.isNullOrBlank()) return false
        Log.d("FLMM", "decoded » $decoded")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = decoded,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = rapidUrl
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}
