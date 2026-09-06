package com.neo.setfilmizle

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class SetPlay : ExtractorApi() {
    override val name            = "SetPlay"
    override val mainUrl         = "https://setplay.shop"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val response = app.get(
            url = url,
            headers = mapOf(
                "User-Agent"      to userAgent,
                "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                // ! Sunucu, bu adresin YALNIZCA bir <iframe> içinden yüklendiğini doğruluyor;
                // ! doğrudan (üst seviye) istekleri 404 ile reddediyor. Tarayıcı ile doğrulandı:
                // ! aynı adres iframe içinde 200, sekmede doğrudan açılınca 404 dönüyor — tek fark
                // ! Fetch Metadata başlıkları. Bu yüzden burada iframe isteğini taklit ediyoruz.
                "Sec-Fetch-Dest"  to "iframe",
                "Sec-Fetch-Mode"  to "navigate",
                "Sec-Fetch-Site"  to "cross-site"
            ),
            referer = referer
        )
        val iSource = response.text
        val cookies = response.headers.values("Set-Cookie").joinToString("; ") { it.substringBefore(";") }

        val jsonString = Regex("""FirePlayer\([^,]+,\s*(\{.*?\})\s*,\s*(?:true|false)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(iSource)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Player konfigurasyonu bulunamadı")

        val json = AppUtils.parseJson<Map<String, Any>>(jsonString)

        val videoServer = json["videoServer"]?.toString() ?: "1"
        val videoUrl = (json["videoUrl"]?.toString() ?: "").replace("\\/", "/")

        val uri = Uri.parse(url)
        val partKey = uri.getQueryParameter("partKey") ?: ""
        
        val suffix = when {
            partKey.contains("turkcedublaj", ignoreCase = true) -> "Dublaj"
            partKey.contains("turkcealtyazi", ignoreCase = true) -> "Altyazı"
            partKey.isNotEmpty() -> partKey
            else -> {
                val title = json["title"]?.toString() ?: "Bilinmeyen"
                title.substringAfterLast(".", "Bilinmeyen")
            }
        }

        val m3uLink = "$mainUrl$videoUrl?s=$videoServer"

        Log.d("Kekik_${this.name}", "Setplay Final Link » $m3uLink")

        val manifestHeaders = mapOf(
            "Referer"         to url,
            "Cookie"          to cookies,
            "User-Agent"      to userAgent,
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept"          to "*/*",
            "Sec-Fetch-Dest"  to "empty",
            "Sec-Fetch-Mode"  to "cors",
            "Sec-Fetch-Site"  to "same-origin"
        )

        // ! TANI (debug) modu: "ERROR_CODE_PARSING_MANIFEST_MALFORMED" hatasının asıl sebebini
        // ! görebilmek için manifesti burada bir kere de biz çekip, gövdesi gerçekten m3u8'e
        // ! benziyor mu diye kontrol ediyoruz — benzemiyorsa sonucu, kaynak listesinde görünen
        // ! sahte bir "TANI" satırı olarak bildiriyoruz. Sorun çözülünce kaldırılacak.
        try {
            val manifestCheck = app.get(url = m3uLink, headers = manifestHeaders)
            val body = manifestCheck.text
            if (!body.trimStart().startsWith("#EXTM3U")) {
                callback.invoke(
                    newExtractorLink(
                        source = "STF-TANI",
                        name   = "TANI» manifest kod=${manifestCheck.code} gövde=${body.take(150).replace("\n", " ")}",
                        url    = "$mainUrl/#tani",
                        type   = ExtractorLinkType.M3U8
                    ) { quality = Qualities.Unknown.value }
                )
            }
        } catch (e: Throwable) {
            callback.invoke(
                newExtractorLink(
                    source = "STF-TANI",
                    name   = "TANI» manifest hata » ${e::class.simpleName}: ${e.message}",
                    url    = "$mainUrl/#tani",
                    type   = ExtractorLinkType.M3U8
                ) { quality = Qualities.Unknown.value }
            )
        }

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = "${this.name} - $suffix",
                url     = m3uLink,
                type    = ExtractorLinkType.M3U8
            ) {
                quality = Qualities.Unknown.value
                headers = manifestHeaders
            }
        )
    }
}
