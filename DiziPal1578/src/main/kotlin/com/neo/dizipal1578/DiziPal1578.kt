// ! Bu araç NeO tarafından yazılmıştır.
// ! Kekik-cloudstream/DiziPal (keyiflerolsun) tabanlı, domains.json ile adres yönetimi eklenmiştir.

package com.neo.dizipal1578

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DiziPal1578 : MainAPI() {
    override var mainUrl              = RemoteConfig.getDomain("dizipal1578", "https://dizipal1578.com")
    override var name                 = "DiziPal 1578"
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = if (request.data.contains("/diziler/son-bolumler")) {
            document.select("div.episode-item").mapNotNull { it.sonBolumler() }
        } else {
            document.select("article.type2 ul li").mapNotNull { it.diziler() }
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.sonBolumler(): SearchResponse? {
        val name    = this.selectFirst("div.name")?.text() ?: return null
        val episode = this.selectFirst("div.episode")?.text()?.trim()?.replace(". Sezon ", "x")?.replace(". Bölüm", "") ?: return null
        val title   = "$name $episode"

        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href.substringBefore("/sezon"), TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.diziler(): SearchResponse? {
        val title     = this.selectFirst("span.title")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
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
            Log.d("DZP1578", "search hatası » ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val year        = document.selectXpath("//div[text()='Yapım Yılı']//following-sibling::div").text().trim().toIntOrNull()
        val description = document.selectFirst("div.summary p")?.text()?.trim()
        val tags        = document.selectXpath("//div[text()='Türler']//following-sibling::div").text().trim().split(" ").map { it.trim() }
        val score       = Score.from10(document.selectXpath("//div[text()='IMDB Puanı']//following-sibling::div").text().trim())
        val duration    = Regex("(\\d+)").find(document.selectXpath("//div[text()='Ortalama Süre']//following-sibling::div").text())?.value?.toIntOrNull()

        if (url.contains("/dizi/")) {
            val title    = document.selectFirst("div.cover h5")?.text() ?: return null
            val episodes = document.select("div.episode-item").mapNotNull {
                val epName    = it.selectFirst("div.name")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epEpisode = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(2)?.replace(".", "")?.toIntOrNull()
                val epSeason  = it.selectFirst("div.episode")?.text()?.trim()?.split(" ")?.get(0)?.replace(".", "")?.toIntOrNull()

                newEpisode(epHref) {
                    this.name    = epName
                    this.episode = epEpisode
                    this.season  = epSeason
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = score
                this.duration  = duration
            }
        } else {
            val title = document.selectXpath("//div[@class='g-title'][2]/div").text().trim()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.score     = score
                this.duration  = duration
            }
        }
    }

    // ! Bu domain (dizipal1578.com), player iframe adresini artık şifreli (AES-256-CBC) olarak
    // ! gömüyor: sayfada boş src'li bir <iframe> ve yanında [data-rm-k] içinde
    // !   {"ciphertext":"<base64>","iv":"<hex>","salt":"<hex>"}
    // ! bulunuyor. Anahtar, sabit bir parola üzerinden PBKDF2-HMAC-SHA512 (999 iterasyon, 256bit)
    // ! ile türetiliyor (site JS bundle'ından çalışma zamanında çıkarıldı ve doğrulandı).
    // ! Çözülen değer gerçek player iframe adresi (ör. sn.dplayer82.site/iframe.php?v=...) oluyor.
    // ! O sayfa da içeride openPlayer('<token>', ...) çağrısıyla asıl playlist token'ını taşıyor;
    // ! bu token source2.php?v=<token> adresine POST/GET edilerek gerçek m3u8 linkine ulaşılıyor.
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZP1578", "data » $data")
        val document = app.get(data).document

        val plainIframeSrc = document.select("iframe").map { it.attr("src") }.firstOrNull { it.isNotBlank() }
        val iframeSrc = if (!plainIframeSrc.isNullOrBlank()) {
            fixUrl(plainIframeSrc)
        } else {
            val encEl = document.selectFirst("[data-rm-k]") ?: return false
            val encRaw = encEl.data().takeIf { it.isNotBlank() } ?: encEl.text()

            val encPayload = try {
                jacksonObjectMapper().readValue<RmKPayload>(encRaw)
            } catch (e: Exception) {
                Log.d("DZP1578", "rm-k json parse hatası » ${e.message}")
                return false
            }

            val decrypted = try {
                decryptRmK(encPayload.ciphertext, encPayload.iv, encPayload.salt)
            } catch (e: Exception) {
                Log.d("DZP1578", "rm-k decrypt hatası » ${e.message}")
                return false
            }

            fixUrl(decrypted)
        }
        Log.d("DZP1578", "iframeSrc » $iframeSrc")

        val iframeHtml = app.get(iframeSrc, referer = "${mainUrl}/").text
        val playListToken = Regex("""openPlayer\('([^']+)'""").find(iframeHtml)?.groupValues?.get(1)
            ?: return loadExtractor(iframeSrc, "${mainUrl}/", subtitleCallback, callback)

        val playerOrigin = Regex("""^(https?://[^/]+)""").find(iframeSrc)?.groupValues?.get(1)
            ?: return false

        val source2Raw = app.get(
            "${playerOrigin}/source2.php?v=${URLEncoder.encode(playListToken, "UTF-8")}",
            referer = iframeSrc
        ).text

        val source2 = try {
            jacksonObjectMapper().readValue<Source2Response>(source2Raw)
        } catch (e: Exception) {
            Log.d("DZP1578", "source2 parse hatası » ${e.message}")
            return false
        }

        val rawFile = source2.playlist?.firstOrNull()?.sources?.firstOrNull()?.file
        if (rawFile == null) {
            Log.d("DZP1578", "source2Raw » $source2Raw")
            return false
        }
        val masterUrl = rawFile.replace("m.php", "master.m3u8")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = masterUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = "${playerOrigin}/"
                this.quality = Qualities.Unknown.value
            }
        )

        // ! Altyazılar openPlayer(...) çağrısı içine gömülü geliyor (captions listesi).
        Regex(""""file":"([^"]+)"\s*,\s*"label":"([^"]+)"[^}]*?"lang":"([^"]+)"""").findAll(iframeHtml).forEach { m ->
            val subUrl  = m.groupValues[1].replace("\\/", "/")
            val subLang = m.groupValues[3]

            subtitleCallback.invoke(SubtitleFile(lang = subLang, url = subUrl))
        }

        return true
    }

    private fun decryptRmK(ciphertextB64: String, ivHex: String, saltHex: String): String {
        val salt       = hexToBytes(saltHex)
        val iv         = hexToBytes(ivHex)
        val ciphertext = Base64.decode(ciphertextB64, Base64.DEFAULT)

        val keyBytes  = pbkdf2HmacSha512(RM_K_PASSPHRASE.toCharArray(), salt, 999, 32)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

        return out
    }

    // ! Manuel PBKDF2-HMAC-SHA512 (CryptoJS.PBKDF2 ile aynı algoritma) - SecretKeyFactory'nin
    // ! "PBKDF2WithHmacSHA512" seçeneği eski Android sürümlerinde bulunmayabildiği için elle yazıldı.
    private fun pbkdf2HmacSha512(password: CharArray, salt: ByteArray, iterations: Int, keyLenBytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(String(password).toByteArray(Charsets.UTF_8), "HmacSHA512"))

        val hLen       = mac.macLength
        val numBlocks  = (keyLenBytes + hLen - 1) / hLen
        val output     = ByteArray(numBlocks * hLen)
        var offset     = 0

        for (blockIndex in 1..numBlocks) {
            val block = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, block, 0, salt.size)
            block[salt.size]     = (blockIndex ushr 24).toByte()
            block[salt.size + 1] = (blockIndex ushr 16).toByte()
            block[salt.size + 2] = (blockIndex ushr 8).toByte()
            block[salt.size + 3] = blockIndex.toByte()

            var u = mac.doFinal(block)
            val t = u.copyOf()
            for (i in 1 until iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }

            System.arraycopy(t, 0, output, offset, hLen)
            offset += hLen
        }

        return output.copyOf(keyLenBytes)
    }

    companion object {
        // ! dizipal1578.com'un app-dizipals.js paketinde sabit gömülü olan parola.
        private const val RM_K_PASSPHRASE = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2k"
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class RmKPayload(
    @JsonProperty("ciphertext") val ciphertext: String,
    @JsonProperty("iv") val iv: String,
    @JsonProperty("salt") val salt: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Source2Response(
    @JsonProperty("state") val state: Boolean? = null,
    @JsonProperty("expired") val expired: Boolean? = null,
    @JsonProperty("playlist") val playlist: List<Source2PlaylistItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Source2PlaylistItem(
    @JsonProperty("sources") val sources: List<Source2Source>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Source2Source(
    @JsonProperty("file") val file: String? = null
)
