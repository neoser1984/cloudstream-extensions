// ! NeO tarafından yazıldı - Fastplay (fastplay.mom) oynatıcısı için deneme extractor

package com.neo.setfilmizle

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

open class Fastplay : ExtractorApi() {
    override val name            = "Fastplay"
    override val mainUrl         = "https://fastplay.mom"
    override val requiresReferer = true

    private val browserUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef = referer ?: mainUrl

        val reqHeaders = mapOf(
            "User-Agent" to browserUserAgent,
            "Referer"    to extRef,
            "Origin"     to mainUrl
        )

        val iSource = app.get(url, headers = reqHeaders, referer = extRef).text
        Log.d("Kekik_${this.name}", "iSource length » ${iSource.length}")

        // Olası birkaç desen deneniyor - sitenin gerçek yapısı doğrulanamadı
        val masterM3u8 = Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").find(iSource)?.groupValues?.get(1)
        val fileField  = Regex(""""?file"?\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(iSource)?.groupValues?.get(1)
        val verifyTxt  = Regex("""(https?://[^"'\s]*manifests/[^"'\s]+\.txt\?verify=[^"'\s]+)""").find(iSource)?.groupValues?.get(1)
        val srcField   = Regex("""source\s+src=["']([^"']+\.m3u8[^"']*)["']""").find(iSource)?.groupValues?.get(1)

        val foundUrl = masterM3u8 ?: fileField ?: verifyTxt ?: srcField
            ?: throw ErrorLoadingException("Fastplay: video linki bulunamadı - site yapısı değişmiş olabilir")

        val finalUrl = if (foundUrl.startsWith("http")) foundUrl else "${mainUrl}${foundUrl}"
        Log.d("Kekik_${this.name}", "finalUrl » $finalUrl")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = finalUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = reqHeaders
            }
        )
    }
}
