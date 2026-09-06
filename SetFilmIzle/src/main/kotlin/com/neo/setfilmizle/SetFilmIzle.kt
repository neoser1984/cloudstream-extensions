// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.
// ! Yeni tema (wp-theme-setfilm) uyumluluğu ve WebView tabanlı oynatıcı çözümü NeO tarafından eklenmiştir.

package com.neo.setfilmizle

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONObject
import org.jsoup.Jsoup

class SetFilmIzle : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("setfilmizle", "https://www.setfilmizle.ltd")
    override var name                 = "SetFilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/"        to "Aile",
        "${mainUrl}/tur/aksiyon/"     to "Aksiyon",
        "${mainUrl}/tur/animasyon/"   to "Animasyon",
        "${mainUrl}/tur/belgesel/"    to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/"   to "Biyografi",
        "${mainUrl}/tur/dini/"        to "Dini",
        "${mainUrl}/tur/dram/"        to "Dram",
        "${mainUrl}/tur/fantastik/"   to "Fantastik",
        "${mainUrl}/tur/genclik/"     to "Gençlik",
        "${mainUrl}/tur/gerilim/"     to "Gerilim",
        "${mainUrl}/tur/gizem/"       to "Gizem",
        "${mainUrl}/tur/komedi/"      to "Komedi",
        "${mainUrl}/tur/korku/"       to "Korku",
        "${mainUrl}/tur/macera/"      to "Macera",
        "${mainUrl}/tur/mini-dizi/"   to "Mini Dizi",
        "${mainUrl}/tur/muzik/"       to "Müzik",
        "${mainUrl}/tur/program/"     to "Program",
        "${mainUrl}/tur/romantik/"    to "Romantik",
        "${mainUrl}/tur/savas/"       to "Savaş",
        "${mainUrl}/tur/spor/"        to "Spor",
        "${mainUrl}/tur/suc/"         to "Suç",
        "${mainUrl}/tur/tarih/"       to "Tarih",
        "${mainUrl}/tur/western/"     to "Western"
    )

    // ! wp-theme-setfilm » kart yapısı: div.fgrid > a.card-link > article.card
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = document.select("div.fgrid a.card-link").mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("span.hcard-title")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mainPage = app.get(mainUrl).document
        val nonce    = Regex("""nonce: '(.*)'""").find(mainPage.html())?.groupValues?.get(1) ?: ""
        val search   = app.post(
            url     = "${mainUrl}/wp-admin/admin-ajax.php",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data    = mapOf(
                "action" to "ajax_search",
                "nonce"  to nonce,
                "search" to query
            )
        )
        val document = Jsoup.parse(JSONObject(search.text).getString("html"))

        return document.select("a.card-link").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ! wp-theme-setfilm » detay yapısı: div.fbox (poster: img.fbox-cover-img, özet: div.fbox-desc,
    // ! tür: a[href*="/tur/"], yıl: a[href*="/yil/"], süre: ":contains(Süre:)", oyuncu: div.fbox-kadro span.fk-t b)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val fbox     = document.selectFirst("div.fbox")

        val title = document.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            ?.substringBefore(" izle")
            ?.trim()
            ?: return null

        val poster = fixUrlNull(
            fbox?.selectFirst("img.fbox-cover-img")?.attr("src")
                ?: fbox?.selectFirst("img")?.attr("src")
        )
        val description     = fbox?.selectFirst("div.fbox-desc")?.text()?.trim()
        val year             = fbox?.selectFirst("a[href*=\"/yil/\"]")?.text()?.trim()?.toIntOrNull()
        val tags             = fbox?.select("a[href*=\"/tur/\"]")?.map { it.text() } ?: emptyList()
        val duration         = fbox?.select("span")
            ?.firstOrNull { it.text().contains("Süre:") }
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        val recommendations  = document.select("div.fgrid a.card-link").mapNotNull { it.toMainPageResult() }
        val actors           = fbox?.select("div.fbox-kadro span.fk-t b")?.map { Actor(it.text()) } ?: emptyList()
        val trailer          = Regex("""embed/(.*)\?rel""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }

        if (url.contains("/dizi/")) {
            // ! Bölümler » div.tv-seasons > div.season-panel[data-season] > div.fep-grid > a.fep
            // ! Bölüm adresleri artık /bolum/<slug>-<sezon>-sezon-<bolum>-bolum/ biçiminde.
            val episodes = document.select("div.tv-seasons a.fep").mapNotNull { ep ->
                val epHref   = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val epTitle  = ep.selectFirst("div.fep-title")?.text()?.trim()
                val epSeason = ep.closest("div.season-panel")?.attr("data-season")?.toIntOrNull()
                    ?: Regex("""-(\d+)-sezon-""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                val epEpisode = Regex("""-sezon-(\d+)-bolum""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epTitle
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.plot            = description
                this.year            = year
                this.tags            = tags
                this.duration        = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.duration        = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun String.toJsStringLiteral(): String =
        "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * ! Yeni temada (wp-theme-setfilm) video adresi artık sunucudan AJAX ile alınmıyor;
     * ! sayfadaki JS (movie.js) tarayıcıda çalışırken imzalı bir token üretip
     * ! "https://setplay.shop/player/stfplay.php?t=...&p=...&a=...&av=..." adresini
     * ! doğrudan bir <iframe> olarak sayfaya basıyor. Bu token JS çalıştırmadan
     * ! (yani düz Jsoup/Regex ile) elde edilemiyor, bu yüzden burada gerçek bir
     * ! WebView açıp ilgili "SetPlay" / "FastPlay" sekmesine tıklayarak
     * ! oluşan isteği yakalıyoruz (bkz: WebViewResolver).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val browserHeaders = mapOf(
            "User-Agent"      to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )

        val document = try {
            app.get(data, headers = browserHeaders, referer = mainUrl).document
        } catch (e: Throwable) {
            Log.e("STF", "loadLinks » sayfa alınamadı » ${e.message}")
            null
        }

        val stfPlayerFound = document?.selectFirst("#stfPlayer") != null
        val sources = document?.select("#stfPlayer .fsrc.src-tab")?.map {
            it.attr("data-player-name") to it.attr("data-part-key")
        }?.ifEmpty { listOf("" to "") } ?: listOf("" to "")

        Log.d("STF", "loadLinks » #stfPlayer bulundu mu: $stfPlayerFound, kaynak sayısı: ${sources.size}")

        var anyLinkFound = false

        for ((playerName, partKey) in sources) {
            try {
                val suffix = when {
                    partKey.contains("turkcedublaj", ignoreCase = true)  -> "Dublaj"
                    partKey.contains("turkcealtyazi", ignoreCase = true) -> "Altyazı"
                    partKey.isNotBlank()                                 -> partKey
                    else                                                 -> null
                }

                val clickScript = """
                    (function(){
                        if (window.__stfClicked) return;
                        var btns = document.querySelectorAll('.fsrc.src-tab');
                        var target = null;
                        for (var i = 0; i < btns.length; i++) {
                            var b = btns[i];
                            if (b.getAttribute('data-player-name') === ${playerName.toJsStringLiteral()} &&
                                b.getAttribute('data-part-key') === ${partKey.toJsStringLiteral()}) {
                                target = b;
                                break;
                            }
                        }
                        if (!target) target = document.querySelector('.fsrc.src-tab, .fplayer-before');
                        if (target) { window.__stfClicked = true; target.click(); }
                    })();
                """.trimIndent()

                val resolver = WebViewResolver(
                    interceptUrl   = Regex("""setplay\.shop/player/stfplay\.php|fastplay\.mom"""),
                    additionalUrls = listOf(Regex("""setplay\.shop/player/stfplay\.php|fastplay\.mom""")),
                    useOkhttp      = false,
                    userAgent      = browserHeaders["User-Agent"],
                    script         = clickScript,
                    timeout        = 25_000L
                )

                val resolvedUrl = try {
                    app.get(data, referer = mainUrl, interceptor = resolver).url
                } catch (e: Throwable) {
                    Log.e("STF", "WebView çözümleme hatası ($playerName/$partKey) » ${e::class.simpleName}: ${e.message}")
                    ""
                }

                if (resolvedUrl.isBlank()) {
                    Log.d("STF", "WebView » ($playerName/$partKey) için adres bulunamadı (zaman aşımı olabilir)")
                    continue
                }

                Log.d("STF", "Çözümlenen oynatıcı adresi » $resolvedUrl")

                val wrappedCallback: (ExtractorLink) -> Unit = { link ->
                    anyLinkFound = true
                    @Suppress("DEPRECATION")
                    callback(
                        ExtractorLink(
                            source        = link.source,
                            name          = if (suffix != null) "${link.source} - $suffix" else link.name,
                            url           = link.url,
                            referer       = link.referer,
                            quality       = link.quality,
                            headers       = link.headers,
                            extractorData = link.extractorData,
                            type          = link.type,
                            audioTracks   = link.audioTracks
                        )
                    )
                }

                when {
                    resolvedUrl.contains("setplay.shop")  -> SetPlay().getUrl(resolvedUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                    resolvedUrl.contains("fastplay.mom")  -> FastPlay().getUrl(resolvedUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                    else                                   -> loadExtractor(resolvedUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                }
            } catch (e: Throwable) {
                Log.e("STF", "loadLinks » kaynak işlenirken hata ($playerName/$partKey) » ${e::class.simpleName}: ${e.message}")
            }
        }

        Log.d("STF", "loadLinks » toplam bağlantı bulundu mu: $anyLinkFound")

        return true
    }
}
