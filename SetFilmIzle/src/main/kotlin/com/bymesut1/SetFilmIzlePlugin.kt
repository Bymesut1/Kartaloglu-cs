@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.bymesut1

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SetFilmIzlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SetFilmIzle())
    }
}
