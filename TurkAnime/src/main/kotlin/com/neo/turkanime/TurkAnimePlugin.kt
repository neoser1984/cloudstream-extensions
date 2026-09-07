package com.neo.turkanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TurkAnimePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TurkAnime())
    }
}