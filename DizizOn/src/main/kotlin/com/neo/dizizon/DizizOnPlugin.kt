package com.neo.dizizon

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DizizOnPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DizizOn())
    }
}
