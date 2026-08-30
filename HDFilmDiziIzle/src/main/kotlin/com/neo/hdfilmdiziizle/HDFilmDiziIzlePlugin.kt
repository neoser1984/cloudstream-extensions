package com.neo.hdfilmdiziizle

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HDFilmDiziIzlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilmDiziIzle())
    }
}
