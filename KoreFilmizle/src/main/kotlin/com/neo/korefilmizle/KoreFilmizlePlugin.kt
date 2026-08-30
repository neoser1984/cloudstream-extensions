package com.neo.korefilmizle

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KoreFilmizlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KoreFilmizle())
    }
}
