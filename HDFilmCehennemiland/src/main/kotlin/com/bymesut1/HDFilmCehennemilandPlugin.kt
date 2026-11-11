package com.bymesut1

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HDFilmCehennemilandPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilmCehennemiland())
        registerExtractorAPI(FastPlay())
    }
}
