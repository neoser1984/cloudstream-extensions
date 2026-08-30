package com.neo.diziyo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziyoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Diziyo())
    }
}
