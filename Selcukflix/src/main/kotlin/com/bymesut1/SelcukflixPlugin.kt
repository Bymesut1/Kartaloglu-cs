package com.bymesut1

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SelcukflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Selcukflix())
    }
}
