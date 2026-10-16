package com.Bymesut1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DizidadPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dizidad())
    }
}
