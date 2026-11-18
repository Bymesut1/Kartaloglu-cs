package com.bymesut1

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DizirellaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Dizirella())
    }
}
