/*
 * TPBBridge - CloudStream plugin
 *
 * Reads configured Stremio/TPB manifests, keeps normal Recent catalogs together
 * in one Home provider, and exposes each Search catalog as its own CloudStream
 * provider. Configured manifest URLs stay only in local app storage.
 *
 * GPL-3.0-or-later. Stremio protocol handling is derived from the GPL bridge
 * approach used by Hexated/phisher98 and compatible forks.
 */
package com.tpbbridge

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixTitle
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal fun discoverSearchRoutes(bases: List<String>): List<SearchRouteGroup> {
    data class Candidate(
        val source: String,
        val route: SearchRoute,
        val explicit: Boolean
    )

    val candidates = mutableListOf<Candidate>()

    bases.forEach { base ->
        val manifestText = httpGetText("$base/manifest.json")
        val root = JSONObject(manifestText)
        val catalogs = root.optJSONArray("catalogs") ?: JSONArray()

        for (i in 0 until catalogs.length()) {
            val c = catalogs.optJSONObject(i) ?: continue
            if (!jsonCatalogSupportsSearch(c)) continue

            val name = c.optString("name", "")
            val id = c.optString("id", "")
            if (id.isBlank()) continue
            val source = deriveSourceName(name, id)
            if (source.isBlank() || isGenericSourceName(source)) continue

            val typeList = mutableListOf<String>()
            val types = c.optJSONArray("types")
            if (types != null) {
                for (j in 0 until types.length()) {
                    val t = types.optString(j).trim()
                    if (t.isNotBlank()) typeList += t
                }
            }
            if (typeList.isEmpty()) {
                c.optString("type", "Porn").trim().ifBlank { "Porn" }.let { typeList += it }
            }

            val label = "$name $id".lowercase(Locale.ROOT)
            val explicit = jsonCatalogSearchRequired(c) || label.contains("search")
            val skip = jsonCatalogHasExtra(c, "skip")
            typeList.distinct().forEach { type ->
                candidates += Candidate(source, SearchRoute(base, id, type, skip), explicit)
            }
        }
    }

    return candidates
        .groupBy { it.source }
        .map { (source, group) ->
            val explicit = group.filter { it.explicit }
            val chosen = if (explicit.isNotEmpty()) explicit else group
            SearchRouteGroup(
                sourceName = source,
                routes = chosen.map { it.route }.distinct()
            )
        }
        .sortedBy { it.sourceName.lowercase(Locale.ROOT) }
}

internal fun saveRouteGroups(groups: List<SearchRouteGroup>): String {
    val arr = JSONArray()
    groups.forEach { group ->
        val routes = JSONArray()
        group.routes.forEach { route ->
            routes.put(JSONObject().apply {
                put("baseUrl", route.baseUrl)
                put("catalogId", route.catalogId)
                put("type", route.type)
                put("supportsSkip", route.supportsSkip)
            })
        }
        arr.put(JSONObject().apply {
            put("sourceName", group.sourceName)
            put("routes", routes)
        })
    }
    return arr.toString()
}

internal fun loadRouteGroups(json: String): List<SearchRouteGroup> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("sourceName", "").trim()
                if (name.isBlank()) continue
                val routesJson = obj.optJSONArray("routes") ?: JSONArray()
                val routes = buildList {
                    for (j in 0 until routesJson.length()) {
                        val r = routesJson.optJSONObject(j) ?: continue
                        val base = r.optString("baseUrl", "").trim()
                        val id = r.optString("catalogId", "").trim()
                        val type = r.optString("type", "Porn").trim().ifBlank { "Porn" }
                        val supportsSkip = r.optBoolean("supportsSkip", false)
                        if (base.isNotBlank() && id.isNotBlank()) {
                            add(SearchRoute(base, id, type, supportsSkip))
                        }
                    }
                }
                if (routes.isNotEmpty()) add(SearchRouteGroup(name, routes))
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun jsonCatalogSupportsSearch(c: JSONObject): Boolean = jsonCatalogHasExtra(c, "search")

internal fun jsonCatalogHasExtra(c: JSONObject, target: String): Boolean {
    val supported = c.optJSONArray("extraSupported")
    if (supported != null) {
        for (i in 0 until supported.length()) {
            if (supported.optString(i).equals(target, true)) return true
        }
    }
    val extra = c.optJSONArray("extra")
    if (extra != null) {
        for (i in 0 until extra.length()) {
            if (extra.optJSONObject(i)?.optString("name")?.equals(target, true) == true) return true
        }
    }
    return false
}

internal fun jsonCatalogSearchRequired(c: JSONObject): Boolean {
    val extra = c.optJSONArray("extra") ?: return false
    for (i in 0 until extra.length()) {
        val e = extra.optJSONObject(i) ?: continue
        if (e.optString("name").equals("search", true) && e.optBoolean("isRequired", false)) return true
    }
    return false
}

internal data class ManifestCacheEntry(val createdAt: Long, val manifest: Manifest)
internal val manifestCache = ConcurrentHashMap<String, ManifestCacheEntry>()

internal suspend fun fetchManifest(base: String): Manifest? {
    val now = System.currentTimeMillis()
    manifestCache[base]?.takeIf { now - it.createdAt < MANIFEST_CACHE_MS }?.let { return it.manifest }

    return try {
        app.get("$base/manifest.json", timeout = 60L).parsedSafe<Manifest>()?.also {
            manifestCache[base] = ManifestCacheEntry(now, it)
        }
    } catch (_: Throwable) {
        null
    }
}

internal fun invalidateManifestCache() = manifestCache.clear()

internal fun parseManifestInput(raw: String): List<String> {
    return raw
        .lineSequence()
        .flatMap { it.split(',').asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { normalizeManifestBase(it) }
        .distinct()
        .toList()
}

internal fun normalizeManifestBase(input: String): String? {
    var s = input.trim()
    if (s.isBlank()) return null
    if (s.startsWith("stremio://", true)) s = "https://" + s.substringAfter("stremio://")
    if (!s.startsWith("https://", true) && !s.startsWith("http://", true)) return null
    s = s.substringBefore('#').removeSuffix("/")

    // TPB uses path-based configuration. Reject query-configured addon URLs instead of
    // silently rewriting them into a different endpoint.
    if ('?' in s) return null
    if (s.endsWith("/manifest.json", true)) {
        s = s.dropLast("/manifest.json".length)
    }
    return s.removeSuffix("/")
}

internal fun Context.findActivity(): android.app.Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is android.app.Activity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? android.app.Activity
}

internal fun httpGetText(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 40_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/149 Mobile Safari/537.36")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

internal fun deriveSourceName(name: String?, id: String): String {
    var value = name?.trim().orEmpty().ifBlank { id.trim() }
    listOf("·", "•", "—", "|").forEach { separator ->
        if (value.contains(separator)) value = value.substringBefore(separator).trim()
    }
    value = value.replace(
        Regex("""\s*[-:]?\s*(Recent|Search|Studio|Tag|Performer)\s*$""", RegexOption.IGNORE_CASE),
        ""
    ).trim()
    return value
}

internal fun isGenericSourceName(value: String): Boolean {
    return value.trim().lowercase(Locale.ROOT) in setOf(
        "search", "recent", "studio", "tag", "performer", "porn", "movie", "movies"
    )
}

internal fun configurationSummary(manifestCount: Int, routes: List<SearchRouteGroup>): String {
    if (manifestCount == 0) return "Not configured"
    val names = routes.map { it.sourceName }
    return buildString {
        append(manifestCount)
        append(if (manifestCount == 1) " manifest" else " manifests")
        append(" • ")
        append(routes.size)
        append(if (routes.size == 1) " Search source" else " Search sources")
        if (names.isNotEmpty()) {
            append("\n")
            append(names.joinToString(", "))
        }
    }
}

internal fun encodePath(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun encodeQuery(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

internal fun baseRef(base: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(base.toByteArray(StandardCharsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun safeNameToken(value: String): String =
    value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "source" }

internal fun cleanText(value: String?): String? = value
    ?.replace(Regex("[\\r\\n\\t]+"), " ")
    ?.replace(Regex("\\s{2,}"), " ")
    ?.trim()
    ?.takeIf { it.isNotBlank() }

internal fun cleanStreamSource(name: String?): String {
    val cleaned = cleanText(name) ?: return "TPB"
    val withoutQuality = cleaned
        .replace(Regex("(?i)\\b(2160|1440|1080|720|576|540|480|360)p?\\b"), "")
        .replace(Regex("(?i)\\b(4k|uhd|fhd|hd)\\b"), "")
        .replace("🧲", "")
        .replace(Regex("\\s{2,}"), " ")
        .trim(' ', '•', '-', '|')
    return withoutQuality.ifBlank { "TPB" }.take(60)
}

internal fun cleanStreamName(name: String?, title: String?, filename: String?): String {
    val n = cleanText(name)
    val t = cleanText(title)
    val f = cleanText(filename)

    val chosen = when {
        !n.isNullOrBlank() -> n
        !t.isNullOrBlank() -> t
        !f.isNullOrBlank() -> f.substringAfterLast('/')
        else -> "TPB Stream"
    }

    return chosen.take(140)
}

internal fun inferQuality(vararg values: String?): Int {
    val joined = values.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
    val explicit = Regex("(2160|1440|1080|720|576|540|480|360)[pP]?").find(joined)?.groupValues?.getOrNull(1)
    val qualityName = when {
        explicit != null -> "${explicit}p"
        Regex("\\b(4k|uhd)\\b").containsMatchIn(joined) -> "2160p"
        Regex("\\bfhd\\b").containsMatchIn(joined) -> "1080p"
        Regex("\\bhd\\b").containsMatchIn(joined) -> "720p"
        else -> null
    }
    return getQualityFromName(qualityName)
}

internal fun buildMagnet(
    infoHash: String,
    displayName: String?,
    sources: List<String>,
    fileIndex: Int? = null
): String {
    val hash = infoHash.trim()
    val parts = mutableListOf("xt=urn:btih:${encodeQuery(hash)}")
    cleanText(displayName)?.let { parts += "dn=${encodeQuery(it)}" }
    fileIndex?.takeIf { it >= 0 }?.let { parts += "index=$it" }

    sources.asSequence()
        .mapNotNull { source ->
            when {
                source.startsWith("tracker:", true) -> source.substringAfter(':').takeIf { it.isNotBlank() }
                source.startsWith("udp://", true) || source.startsWith("http://", true) || source.startsWith("https://", true) -> source
                else -> null
            }
        }
        .distinct()
        .take(30)
        .forEach { tracker -> parts += "tr=${encodeQuery(tracker)}" }

    return "magnet:?${parts.joinToString("&")}"
}
