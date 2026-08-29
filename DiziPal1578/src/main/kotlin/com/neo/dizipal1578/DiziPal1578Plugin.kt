package com.neo.dizipal1578

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DiziPal1578Plugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziPal1578())
    }
}
