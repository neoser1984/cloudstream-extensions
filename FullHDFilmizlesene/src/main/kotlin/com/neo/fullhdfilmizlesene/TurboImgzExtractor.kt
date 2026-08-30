// ! Bu araç @keyiflerolsun (Kekik-cloudstream) tabanlı, NeO tarafından uyarlanmıştır.
// ! CloudStream çekirdeğinde yerleşik olarak bulunmadığı için ayrı eklendi.

package com.neo.fullhdfilmizlesene

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class TurboImgz : ExtractorApi() {
    override val name       = "TurboImgz"
    override val mainUrl    = "https://turbo.imgz.me"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val videoReq = app.get(url.substringAfter("||"), referer = extRef).text

        val videoLink = Regex("""file: "(.*)",""").find(videoReq)?.groupValues?.get(1) ?: throw ErrorLoadingException("File not found")
        val sourceKey = url.substringBefore("||").uppercase()

        callback.invoke(
            newExtractorLink(
                source = "${this.name} - $sourceKey",
                name   = "${this.name} - $sourceKey",
                url    = videoLink,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = extRef
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
