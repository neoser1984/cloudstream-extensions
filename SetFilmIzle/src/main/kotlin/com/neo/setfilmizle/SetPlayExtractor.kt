package com.neo.setfilmizle

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay

open class SetPlay : ExtractorApi() {
    override val name            = "SetPlay"
    override val mainUrl         = "https://setplay.shop"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // ! v17: köprü sayfası bazen (aralıklı olarak) FirePlayer(...) yerine bir anti-bot
        // ! betiği (window.SPG_A={"acik":true,"hedef":"engel.html",...}) döndürüyor — bu, her
        // ! istekte değil, bazı isteklerde oluyor. Bu yüzden regex eşleşmezse birkaç kez daha
        // ! (kısa aralıklarla) deniyoruz; belki de geçici/aralıklı bir engelleme.
        var iSource = ""
        var cookies = ""
        var lastCode = 0
        var attempts = 0
        val maxAttempts = 3
        var jsonString: String? = null

        while (attempts < maxAttempts) {
            attempts++
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
            iSource  = response.text
            cookies  = response.headers.values("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
            lastCode = response.code

            jsonString = Regex("""FirePlayer\([^,]+,\s*(\{.*?\})\s*,\s*(?:true|false)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(iSource)?.groupValues?.get(1)

            if (jsonString != null) break
            if (attempts < maxAttempts) delay(900)
        }

        // ! TANI (debug) modu: v15'te tanı metinlerini sahte ExtractorLink olarak "Kaynaklar"
        // ! listesine ekliyorduk, ama bu CloudStream'in kaynak/dublaj seçim mantığını bozup
        // ! gerçek SetPlay linkini gizleyebiliyor (ekranda sadece "STF-TANI - Dublaj" görünüp
        // ! gerçek kaynağın hiç denenmemesine yol açabiliyor). Bu yüzden v16'da tanı metinlerini
        // ! video kaynağıyla YARIŞMAYAN ayrı bir kanala, "Altyazılar" listesine taşıyoruz.
        // ! Gerçek video linki HER ZAMAN tek başına callback'e gönderiliyor.
        fun emitTani(prefix: String, text: String) {
            val flatBody  = text.replace("\n", " ").replace("\r", " ")
            val chunkSize = 350
            val maxChunks = 10
            val totalChunks = minOf(maxChunks, maxOf(1, (flatBody.length + chunkSize - 1) / chunkSize))

            for (i in 0 until totalChunks) {
                val start = i * chunkSize
                val end   = minOf(start + chunkSize, flatBody.length)
                val chunk = if (flatBody.isEmpty()) "" else flatBody.substring(start, end)
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = "${prefix} ${i + 1}/${totalChunks}» $chunk",
                        url  = "$mainUrl/#tani"
                    )
                )
            }
        }

        if (jsonString == null) {
            emitTani("TANI-B» ${attempts}/${maxAttempts} deneme, son kod=${lastCode} uzunluk=${iSource.length} »", iSource)
            return
        }

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

        // ! Gerçek HTTP kodunu/gövdeyi görmek için manifesti biz de ayrıca çekiyoruz, ama
        // ! sonucu artık "Altyazılar" listesine yazıyoruz (video kaynak seçimini etkilemez).
        try {
            val manifestCheck = app.get(url = m3uLink, headers = manifestHeaders, referer = url)
            val body = manifestCheck.text
            if (!body.trimStart().startsWith("#EXTM3U")) {
                emitTani("TANI-M» manifest kod=${manifestCheck.code} uzunluk=${body.length} »", body)
            }
        } catch (e: Throwable) {
            emitTani("TANI-M» manifest hata »", "${e::class.simpleName}: ${e.message}")
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
                this.referer = url
            }
        )
    }
}
