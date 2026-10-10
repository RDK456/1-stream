package com.lagradost.cloudstream3.ui.settings.extensions

/** Same shape as CloudStream's RepositoryData, so converted plugins can talk to [com.lagradost.cloudstream3.plugins.RepositoryManager]. */
data class RepositoryData(val iconUrl: String?, val name: String, val url: String) {
    constructor(name: String, url: String) : this(null, name, url)
}