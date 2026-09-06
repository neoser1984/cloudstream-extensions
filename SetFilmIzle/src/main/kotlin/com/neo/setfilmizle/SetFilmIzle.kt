// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.
// ! Yeni tema (wp-theme-setfilm) uyumluluğu NeO tarafından eklenmiştir.

package com.neo.setfilmizle

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    /**
     * ! Yeni temada (wp-theme-setfilm) video adresi, sayfaya gömülü "window.STF_AJAX" nesnesindeki
     * ! nonce kullanılarak "wp-admin/admin-ajax.php" adresine "action=get_video_url" ile atılan
     * ! düz bir POST isteğiyle alınıyor (eskisiyle aynı mantık, sadece nonce'un kaynağı değişmiş).
     * ! Bu yüzden WebView'e ya da JS çalıştırmaya hiç gerek yok — TV kutuları/Android TV gibi
     * ! WebView bileşeni sağlıklı çalışmayan cihazlarda da sorunsuz çalışır.
     */
    /**
     * ! TANI (debug) modu: TV/kutu cihazlarda logcat'e erişimimiz olmadığı ve CloudStream'in bazı
     * ! sürümleri ErrorLoadingException mesajını ekrana yansıtmadığı için (kullanıcı hâlâ sadece
     * ! genel "Bağlantı bulunamadı" görüyor), asıl sebebi CloudStream'in KENDİ kaynak seçim
     * ! listesinde, seçilebilir (ama tıklanınca oynamayan) sahte bir "kaynak" adı olarak
     * ! gösteriyoruz — bu liste zaten normalde çalışan bir ekran olduğu için mesaj kesin görünür.
     * ! Sorun çözülünce bu yardımcı fonksiyon ve çağrıları kaldırılacak.
     */
    private fun tanılinki(callback: (ExtractorLink) -> Unit, mesaj: String) {
        callback(
            ExtractorLink(
                source  = "STF-TANI",
                name    = "TANI» " + mesaj.take(180),
                url     = "$mainUrl/#hata-tani",
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type    = ExtractorLinkType.M3U8
            )
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val errors = mutableListOf<String>()

        val response = try {
            app.get(data, referer = mainUrl)
        } catch (e: Throwable) {
            tanılinki(callback, "sayfa alınamadı » ${e::class.simpleName}: ${e.message}")
            return true
        }
        val document = response.document
        val html     = response.text

        val postId = document.selectFirst("#stfPlayer")?.attr("data-post-id")
        val nonce  = Regex("""video\s*:\s*"([0-9a-f]+)"""").find(html)?.groupValues?.get(1)
        val ajaxUrl = Regex("""url\s*:\s*"([^"]*admin-ajax\.php)"""").find(html)?.groupValues?.get(1)
            ?: "$mainUrl/wp-admin/admin-ajax.php"

        if (postId.isNullOrBlank() || nonce.isNullOrBlank()) {
            tanılinki(
                callback,
                "postId/nonce yok » kod=${response.code}, postId=$postId, " +
                "nonce=${if (nonce.isNullOrBlank()) "yok" else "var"}, uzunluk=${html.length}"
            )
            return true
        }

        val sources = document.select("#stfPlayer .fsrc.src-tab").map {
            it.attr("data-player-name") to it.attr("data-part-key")
        }.ifEmpty { listOf("SetPlay" to "") }

        Log.d("STF", "loadLinks » postId=$postId, kaynak sayısı=${sources.size}")

        var anyLinkFound = false

        for ((playerName, partKey) in sources) {
            if (playerName.isBlank()) continue

            try {
                val suffix = when {
                    partKey.contains("turkcedublaj", ignoreCase = true)  -> "Dublaj"
                    partKey.contains("turkcealtyazi", ignoreCase = true) -> "Altyazı"
                    partKey.isNotBlank()                                 -> partKey
                    else                                                 -> null
                }

                val ajaxResponse = app.post(
                    url     = ajaxUrl,
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    data    = mapOf(
                        "action"      to "get_video_url",
                        "nonce"       to nonce,
                        "post_id"     to postId,
                        "player_name" to playerName,
                        "part_key"    to partKey
                    )
                )

                val json = try {
                    JSONObject(ajaxResponse.text)
                } catch (e: Throwable) {
                    errors += "$playerName» ajax cevabı JSON değil » kod=${ajaxResponse.code}, gövde=${ajaxResponse.text.take(120)}"
                    continue
                }

                if (!json.optBoolean("success")) {
                    errors += "$playerName» ajax success=false » kod=${ajaxResponse.code}, gövde=${ajaxResponse.text.take(150)}"
                    continue
                }

                val dataObj = json.optJSONObject("data")
                val stream  = dataObj?.optJSONObject("stream")
                val resolvedUrl = when {
                    stream != null && stream.optString("url").isNotBlank()  -> stream.optString("url")
                    dataObj?.optString("src")?.isNotBlank() == true         -> dataObj.optString("src")
                    dataObj?.optString("url")?.isNotBlank() == true         -> dataObj.optString("url")
                    else                                                     -> null
                }
                val provider = stream?.optString("provider")?.takeIf { it.isNotBlank() }

                if (resolvedUrl.isNullOrBlank()) {
                    errors += "$playerName» ajax success=true ama oynatma adresi yok » gövde=${ajaxResponse.text.take(150)}"
                    continue
                }

                Log.d("STF", "Çözümlenen oynatıcı adresi » $resolvedUrl (provider=$provider)")

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

                try {
                    when {
                        provider.equals("setplay", ignoreCase = true) || resolvedUrl.contains("setplay.shop") ->
                            SetPlay().getUrl(resolvedUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                        provider.equals("fastplay", ignoreCase = true) || resolvedUrl.contains("fastplay.mom") ->
                            FastPlay().getUrl(resolvedUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                        else ->
                            loadExtractor(resolvedUrl, "$mainUrl/", subtitleCallback, wrappedCallback)
                    }
                } catch (e: Throwable) {
                    errors += "$playerName» çözümleyici (${provider ?: "?"}) hata » ${e::class.simpleName}: ${e.message}"
                }
            } catch (e: Throwable) {
                errors += "$playerName» genel hata » ${e::class.simpleName}: ${e.message}"
            }
        }

        Log.d("STF", "loadLinks » toplam bağlantı bulundu mu: $anyLinkFound » hatalar: $errors")

        if (!anyLinkFound) {
            tanılinki(
                callback,
                if (errors.isNotEmpty()) errors.joinToString(" || ")
                else "kaynak listesi boş (sources=${sources.size})"
            )
        }

        return true
    }
}
