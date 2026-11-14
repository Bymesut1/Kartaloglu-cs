package com.bymesut1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmmoduNewsPlugin : Plugin() {
    override fun load(context: Context) {
        FilmmoduNewsSettings.init(context)
        registerMainAPI(FilmmoduNews())
        openSettings = { ctx ->
            FilmmoduNewsSettings.showDialog(ctx, "FilmmoduNews", FilmmoduNews.ROWS)
        }
    }
}
