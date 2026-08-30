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

    // ! 2026-08 civarında site tamamen yeni bir şablona geçti: eski "/diziler", "/tur/*",
    // ! "/koleksiyon/*" yolları artık yok. Yeni yapı: "/yabanci-dizi-izle", "/hd-film-izle",
    // ! "/anime", "/kanal/{platform}". Dizi detay: "/series/{slug}", film detay: "/movies/{slug}".
    override val mainPage = mainPageOf(
        "${mainUrl}/yabanci-dizi-izle"                             to "Diziler",
        "${mainUrl}/hd-film-izle"                                  to "Filmler",
        "${mainUrl}/anime"                                         to "Anime",
        "${mainUrl}/kanal/netflix"                                 to "Netflix",
        "${mainUrl}/kanal/exxen"                                   to "Exxen",
        "${mainUrl}/kanal/disney"                                  to "Disney+",
        "${mainUrl}/kanal/amazon"                                  to "Amazon Prime",
        "${mainUrl}/kanal/max"                                     to "Max",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = document.select("a[href*='/series/'], a[href*='/movies/']").mapNotNull { it.toCardResult() }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    // ! Kart yapısı: <a href=".../series/slug veya .../movies/slug"><div class=img><img></div><h2>Başlık</h2></a>
    private fun Element.toCardResult(): SearchResponse? {
        val href  = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h2")?.text()?.trim() ?: return null
        val img   = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
        )

        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    private fun BgSearchItem.toSearchResult(): SearchResponse? {
        val slug  = this.usedSlug ?: return null
        val title = this.objectName ?: return null
        val href  = fixUrl(slug)
        val poster = this.posterUrl ?: this.faceUrl

        return if (slug.startsWith("movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    // ! Yeni arama uç noktası: POST /bg/searchcontent (düz JSON, şifresiz) -> {"data":{"result":[...]}}
    // ! (Eski "/api/search-autocomplete" artık 404 dönüyordu.)
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val responseRaw = app.post(
                "${mainUrl}/bg/searchcontent",
                data    = mapOf("searchterm" to query),
                referer = "${mainUrl}/"
            ).text

            val parsed = jacksonObjectMapper().readValue<BgSearchResponse>(responseRaw)
            parsed.data?.result?.mapNotNull { it.toSearchResult() } ?: emptyList()
        } catch (e: Exception) {
            Log.d("DZP1578", "search hatası » ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("img[src*='cdnhipter']")?.attr("src"))
        val description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        return if (url.contains("/series/")) {
            val episodes = document.select("a[href*='/bolum/']").mapNotNull {
                val epHref = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val m      = Regex("""-(\d+)x(\d+)""").find(epHref) ?: return@mapNotNull null
                val season = m.groupValues[1].toIntOrNull()
                val episode = m.groupValues[2].toIntOrNull()

                newEpisode(epHref) {
                    this.name    = if (season != null && episode != null) "${season}. Sezon ${episode}. Bölüm" else null
                    this.season  = season
                    this.episode = episode
                }
            }.distinctBy { it.data }

            if (episodes.isEmpty()) return null

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
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
        // ! 2026-08 yenilemesiyle sona bir karakter ("v") eklendi - site JS bundle'ından
        // ! çalışma zamanında yeniden doğrulandı.
        private const val RM_K_PASSPHRASE = "3hPn4uCjTVtfYWcjIcoJQ4cL1WWk1qxXI39egLYOmNv6IblA7eKJz68uU3eLzux1biZLCms0quEjTYniGv5z1JcKbNIsDQFSeIZOBZJz4is6pD7UyWDggWWzTLBQbHcQFpBQdClnuQaMNUHtLHTpzCvZy33p6I7wFBvL4fnXBYH84aUIyWGTRvM2G5cfoNf4705tO2kv"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BgSearchResponse(
    @JsonProperty("data") val data: BgSearchData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BgSearchData(
    @JsonProperty("result") val result: List<BgSearchItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BgSearchItem(
    @JsonProperty("used_slug") val usedSlug: String? = null,
    @JsonProperty("used_type") val usedType: String? = null,
    @JsonProperty("object_name") val objectName: String? = null,
    @JsonProperty("object_poster_url") val posterUrl: String? = null,
    @JsonProperty("object_face_url") val faceUrl: String? = null
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
