package com.neo.dizipal2121

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPal2121Plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPal2121())
    }
}
