package com.neo.fullhdfilmizlesene

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FullHDFilmizlesenePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FullHDFilmizlesene())
        // ! RapidVid, TRsTX, VidMoxy, Sobreatsesuyp artık CloudStream çekirdeğinde
        // ! yerleşik olarak geldiği için burada tekrar kaydedilmiyor.
        registerExtractorAPI(TurboImgz())
    }
}
