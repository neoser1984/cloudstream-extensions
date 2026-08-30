// ! Bu araç NeO tarafından yazılmıştır.
// ! hdfilmdiziizle.com canlı sitesi üzerinde 2026-08 itibarıyla doğrulanmıştır. Hazır referans
// ! eklenti bulunmadığından seçiciler canlı site incelemesiyle sıfırdan çıkarılmıştır.
// ! - Kartlar "a.phd-card.phd-card-vertical" ile bulunur; başlık için önce "title" özniteliği,
// !   yoksa "img[alt]" kullanılır (her ikisinde de mevcut ve daha güvenilir). Poster "img[data-src]"
// !   ya da "img[src]".
// ! - Film adresi "/film/{slug}/", dizi (kök) adresi "/dizi/{slug}/", bölüm adresi ise dizi kökünün
// !   ALTINDA DEĞİL düz "/bolum/{slug}-{sezon}-sezon-{bölüm}-bolum/" şeklindedir.
// ! - Sayfalama: "{kategoriUrl}page/{n}/" (WordPress standardı).
// ! - Arama: GET "/?s=<sorgu>" (WordPress arama). Sonuçlar film ("/film/"), dizi ("/dizi/") ve
// !   BÖLÜM ("/bolum/") kartlarının karışımı olabilir; bölüm sonuçları "{taban-slug}-{sezon}-sezon-
// !   {bölüm}-bolum" düzenli ifadesiyle taban slug'a indirgenip "/dizi/{taban-slug}/" dizi adresine
// !   çevrilir (aynı diziden gelen tekrarlar distinctBy ile elenir).
// ! - Dizi sayfasında TÜM sezon/bölümler tek sayfada önceden render edilmiş halde bulunur:
// !   "a.phd-episode-card" (üst kapsayıcılar: "div.phd-episode-grid" > "div.phd-season-content-area
// !   .phd-scrollable-box" > "div.phd-dizi-section"); bölüm adı "img[alt]" içinde temiz haliyle gelir
// !   (örn. "Lucifer 1. Sezon 1. Bölüm"). Sekmeler "button.phd-season-btn[data-season]" sadece görsel
// !   filtrelemedir, veri zaten DOM'dadır.
// ! - Başlık/puan/yıl/tür meta bilgisi film ve dizi sayfalarında FARKLI CSS sınıfları kullanır (film:
// !   ".phd-single-meta"/".phd-imdb-badge", dizi: ".phd-hero-meta"); bu yüzden ikisi de aday olarak
// !   denenip ortak metin üzerinden regex ile ayrıştırılır ("IMDb X.Y", 4 haneli yıl). Poster için her
// !   iki sayfa türünde de ortak ve güvenilir olan "meta[property=og:image]" kullanılır. Açıklama
// !   film sayfasında ".phd-overview-text p", dizi sayfasında ".phd-hero-desc" içindedir.
// ! - VİDEO KAYNAĞI (reverse-engineer edildi): bölüm/film sayfasındaki "button[data-source]"
// !   butonlarının her biri bir kaynağı temsil eder (örn. "vidrame_url", "kaynak4_url"). Sayfanın
// !   "postid-<ID>" gövde sınıfından post ID çıkarılır, ardından
// !   POST "/wp-admin/admin-ajax.php" (action=get_source_url, post_id=<ID>, source=<data-source>,
// !   header: X-Requested-With: XMLHttpRequest) çağrılır. Yanıt (düz metin, trim edilmiş) BASE64 +
// !   AES-256-CBC şifrelidir: anahtar "0123456789abcdef0123456789abcdef" (UTF8, 32 bayt), IV
// !   "abcdef9876543210" (UTF8, 16 bayt), PKCS7/PKCS5 dolgu. Çözülen düz metin site-içi (aynı origin)
// !   bir yönlendirici URL'dir ("/wp-content/plugins/playhd/...php?token=..."); bu URL iki farklı
// !   şekilde davranabilir:
// !     (a) HTTP 302 ile doğrudan üçüncü parti video sunucusuna yönlenir (örn. vidrame.pro/vr/<hash>),
// !     (b) 200 OK ile aynı origin'den küçük bir HTML sayfası döner, içinde tek bir
// !         "<iframe src=\"...\">" ile üçüncü parti oynatıcıya (örn. p.playturka.space) işaret eder.
// !   Bu yüzden yönlendirici URL'e istek atıldıktan sonra: son URL orijinal URL'den FARKLIYSA (gerçek
// !   yönlendirme olmuşsa) o URL, değilse gövdedeki ilk "<iframe>" src'si video URL'i olarak alınır ve
// !   loadExtractor() ile işlenir.
// ! - NOT: "vidrame.pro/vr/{hash}" gibi bazı kaynak URL'leri sunucu tarafında yalnızca gerçek iframe
// !   navigasyonlarını kabul ediyor gibi görünüyor (düz fetch/XHR isteği 404 döndü, hdfilmizle.vip
// !   eklentisinde de aynı davranış gözlemlendi); CloudStream'in HTTP istemcisiyle bu kaynaklardan
// !   bazıları çalışmayabilir. Diğer kaynaklar (örn. "kaynak4_url" -> jetizle-stream.php) düz fetch ile
// !   200 döndürdüğü için birden çok kaynak denenir, en az biri çalışırsa yeterlidir.

package com.neo.hdfilmdiziizle

import android.util.Base64
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HDFilmDiziIzle : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("hdfilmdiziizle", "https://www.hdfilmdiziizle.com")
    override var name                 = "HDFilmDiziIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/film/"                    to "Filmler",
        "$mainUrl/dizi/"                    to "Diziler",
        "$mainUrl/film-tur/aksiyon/"         to "Aksiyon Filmleri",
        "$mainUrl/film-tur/bilim-kurgu/"     to "Bilim Kurgu Filmleri",
        "$mainUrl/film-tur/komedi/"          to "Komedi Filmleri",
        "$mainUrl/film-tur/dram/"            to "Dram Filmleri",
        "$mainUrl/film-tur/gerilim/"         to "Gerilim Filmleri",
    )

    private val episodeSlugRegex     = Regex("""-(\d+)-sezon-(\d+)-bolum$""")
    private val episodeTitleRegex    = Regex("""^(.*?)\s+\d+\.\s*Sezon""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/${page}/"
        val document = app.get(url).document

        val home = document.select("a.phd-card.phd-card-vertical").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val img  = this.selectFirst("img")

        val posterUrl = fixUrlNull(img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))
        val altTitle  = img?.attr("alt")?.trim()
        val cardTitle = this.attr("title").ifBlank { null } ?: altTitle
        if (cardTitle.isNullOrBlank()) return null

        return when {
            href.contains("/dizi/") -> newTvSeriesSearchResponse(cardTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }

            href.contains("/bolum/") -> {
                val slug     = href.trimEnd('/').substringAfterLast('/')
                val match    = episodeSlugRegex.find(slug) ?: return null
                val baseSlug = slug.substring(0, match.range.first)
                if (baseSlug.isBlank()) return null

                val seriesUrl   = "$mainUrl/dizi/$baseSlug/"
                val seriesTitle = episodeTitleRegex.find(cardTitle)?.groupValues?.get(1)?.trim()
                    ?.ifBlank { null } ?: cardTitle

                newTvSeriesSearchResponse(seriesTitle, seriesUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }

            else -> newMovieSearchResponse(cardTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/", params = mapOf("s" to query)).document

        return document.select("a.phd-card.phd-card-vertical")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val isSeries = url.contains("/dizi/")

        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val title    = rawTitle.removeSuffix("izle").trim().ifBlank { rawTitle }

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val metaText = (document.selectFirst(".phd-hero-meta") ?: document.selectFirst(".phd-single-meta"))
            ?.text().orEmpty()
        val score = Regex("""IMDb\s*([\d.]+)""").find(metaText)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { Score.from10(it) }
        val year = Regex("""\b(19|20)\d{2}\b""").find(metaText)?.value?.toIntOrNull()

        val tags = document.select(".phd-genre-tag").map { it.text().trim() }.filter { it.isNotBlank() }

        val description = (document.selectFirst(".phd-overview-text p") ?: document.selectFirst(".phd-hero-desc"))
            ?.text()?.trim()

        if (isSeries) {
            val episodes = document.select("a.phd-episode-card").mapNotNull { epEl ->
                val epHref = fixUrlNull(epEl.attr("href")) ?: return@mapNotNull null
                val epSlug = epHref.trimEnd('/').substringAfterLast('/')
                val match  = episodeSlugRegex.find(epSlug) ?: return@mapNotNull null
                val season = match.groupValues[1].toIntOrNull() ?: 1
                val epNum  = match.groupValues[2].toIntOrNull()
                val epName = epEl.selectFirst("img")?.attr("alt")?.trim()?.ifBlank { null }

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
                this.year      = year
                this.tags      = tags
                this.score     = score
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.score     = score
        }
    }

    // ! AES-256-CBC çözme: anahtar/IV site genelinde sabittir (bkz. dosya başındaki not).
    private fun decryptSource(base64CipherText: String): String? {
        return try {
            val keyBytes = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
            val ivBytes  = "abcdef9876543210".toByteArray(Charsets.UTF_8)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))

            val decoded = Base64.decode(base64CipherText, Base64.DEFAULT)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val postId = Regex("""postid-(\d+)""").find(document.body().className())?.groupValues?.get(1) ?: return false

        val sources = document.select("[data-source]").mapNotNull { it.attr("data-source").ifBlank { null } }.distinct()
        if (sources.isEmpty()) return false

        var found = false

        for (source in sources) {
            try {
                val cipherText = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data    = mapOf("action" to "get_source_url", "post_id" to postId, "source" to source),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text.trim()
                if (cipherText.isBlank()) continue

                val decryptedUrl = decryptSource(cipherText)?.trim()?.takeIf { it.startsWith("http") } ?: continue

                val res      = app.get(decryptedUrl, referer = data)
                val finalUrl = res.url

                val videoUrl = if (finalUrl != decryptedUrl && !finalUrl.contains("/wp-content/plugins/")) {
                    finalUrl
                } else {
                    res.document.selectFirst("iframe")?.attr("src")
                }
                if (videoUrl.isNullOrBlank()) continue

                loadExtractor(fixUrl(videoUrl), data, subtitleCallback, callback)
                found = true
            } catch (e: Exception) {
                continue
            }
        }

        return found
    }
}
