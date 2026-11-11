package com.bymesut1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmCehennemi.LandPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilmCehennemi.Land())
        registerExtractorAPI(FastPlay())
    }
}
