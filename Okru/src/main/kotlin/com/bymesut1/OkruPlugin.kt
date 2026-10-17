package com.bymesut1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OkruPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OkruProvider())
    }
}
