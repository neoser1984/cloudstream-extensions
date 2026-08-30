package com.neo.hdfilmizle

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HDFilmizlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilmizle())
    }
}
