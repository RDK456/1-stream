package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The Android CloudStream `Plugin` base class. Plugins converted from .cs3 extend this one. */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        // Plugins that only override the cross-platform load() still work
        load()
    }

    var resources: Resources? = null
    var openSettings: ((context: Context) -> Unit)? = null
}

data class Repository(
    val iconUrl: String?,
    val name: String,
    val description: String?,
    val manifestVersion: Int,
    val pluginLists: List<String>,
)

/** Hooks the host installs so converted plugins can read and add repositories. */
object CompatHost {
    @Volatile var repositories: () -> List<String> = { emptyList() }
    @Volatile var addRepository: (url: String) -> Unit = {}
    @Volatile var fetchText: (url: String) -> String? = { null }
}

/** The few RepositoryManager functions that plugins call, backed by [CompatHost]. */
object RepositoryManager {
    val PREBUILT_REPOSITORIES: Array<RepositoryData> = emptyArray()

    fun convertRawGitUrl(url: String): String = url

    suspend fun parseRepoUrl(url: String): String? {
        val u = url.trim()
        return when {
            Regex("^https?://").containsMatchIn(u) -> u
            u.startsWith("cloudstreamrepo://") -> "https://" + u.removePrefix("cloudstreamrepo://")
            else -> null
        }
    }

    suspend fun parseRepository(url: String): Repository? = withContext(Dispatchers.IO) {
        runCatching {
            val o = Json.parseToJsonElement(CompatHost.fetchText(url) ?: return@runCatching null) as? JsonObject ?: return@runCatching null
            Repository(
                o["iconUrl"]?.jsonPrimitive?.contentOrNull, o["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null,
                o["description"]?.jsonPrimitive?.contentOrNull, o["manifestVersion"]?.jsonPrimitive?.intOrNull ?: 1,
                (o["pluginLists"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
            )
        }.getOrNull()
    }

    fun getRepositories(): Array<RepositoryData> = CompatHost.repositories().map { RepositoryData(null, it, it) }.toTypedArray()

    suspend fun addRepository(repository: RepositoryData) = CompatHost.addRepository(repository.url)
}