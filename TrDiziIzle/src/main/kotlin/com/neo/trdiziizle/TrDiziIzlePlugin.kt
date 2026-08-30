package com.neo.trdiziizle

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TrDiziIzlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TrDiziIzle())
    }
}
