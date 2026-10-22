package com.bymesut1

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DiziCehennemiPlugin : BasePlugin() {
    override fun load() {
        // DiziCehennemi.kt içindeki sağlayıcıyı CloudStream'e kaydeder
        registerMainAPI(DiziCehennemi())
    }
}
