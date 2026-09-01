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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixTitle
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
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
import java.util.Locale

private const val PREF_FILE = "TPBBridge"
private const val PREF_MANIFESTS = "manifest_urls"
private const val PREF_HOME_NAME = "home_name"
private const val PREF_SEARCH_PREFIX = "search_prefix"
private const val PREF_ROUTES = "search_routes"
private const val DEFAULT_HOME_NAME = "TPB Tubes"
private const val DEFAULT_SEARCH_PREFIX = "TPB • "

@CloudstreamPlugin
class TPBBridgePlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val bases = parseManifestInput(prefs.getString(PREF_MANIFESTS, "").orEmpty())
        val homeName = prefs.getString(PREF_HOME_NAME, DEFAULT_HOME_NAME)
            ?.trim().orEmpty().ifBlank { DEFAULT_HOME_NAME }
        val prefix = prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX).orEmpty()
        val routeGroups = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())

        openSettings = { settingsContext -> showSettings(settingsContext) }

        if (bases.isNotEmpty()) {
            registerMainAPI(TPBHomeProvider(homeName, bases))
            routeGroups.forEach { group ->
                if (group.routes.isNotEmpty()) {
                    registerMainAPI(TPBSearchProvider(prefix + group.sourceName, group.sourceName, group.routes))
                }
            }
        }
    }

    private fun showSettings(context: Context) {
        val activity = context.findActivity() ?: run {
            Toast.makeText(context, "Unable to open TPBBridge settings from this screen.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = activity.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
        fun label(text: String): TextView = TextView(activity).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }

        root.addView(label("TPB manifest URL(s)"))
        root.addView(TextView(activity).apply {
            text = "Paste every TPB split manifest here, one per line. URLs are stored only in CloudStream app storage."
        })
        val manifestsEdit = EditText(activity).apply {
            minLines = 4
            maxLines = 10
            setText(prefs.getString(PREF_MANIFESTS, "").orEmpty())
            hint = "https://…/manifest.json\nhttps://…/manifest.json"
        }
        root.addView(
            manifestsEdit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        root.addView(label("Combined Home provider name"))
        val homeNameEdit = EditText(activity).apply {
            setText(prefs.getString(PREF_HOME_NAME, DEFAULT_HOME_NAME) ?: DEFAULT_HOME_NAME)
            hint = DEFAULT_HOME_NAME
        }
        root.addView(homeNameEdit)

        root.addView(label("Search provider prefix"))
        val prefixEdit = EditText(activity).apply {
            setText(prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX) ?: DEFAULT_SEARCH_PREFIX)
            hint = DEFAULT_SEARCH_PREFIX
        }
        root.addView(prefixEdit)

        val existing = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())
        val status = TextView(activity).apply {
            setPadding(0, dp(12), 0, dp(8))
            text = if (existing.isEmpty()) {
                "No search sources discovered yet."
            } else {
                "Discovered ${existing.size} search sources:\n" + existing.joinToString(", ") { it.sourceName }
            }
        }
        root.addView(status)

        val discover = Button(activity).apply { text = "Discover sources + save" }
        val saveOnly = Button(activity).apply { text = "Save without rediscovering" }
        val clear = Button(activity).apply { text = "Clear discovered search providers" }
        root.addView(discover)
        root.addView(saveOnly)
        root.addView(clear)

        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("TPBBridge settings")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        saveOnly.setOnClickListener {
            prefs.edit()
                .putString(PREF_MANIFESTS, manifestsEdit.text.toString().trim())
                .putString(PREF_HOME_NAME, homeNameEdit.text.toString().trim())
                .putString(PREF_SEARCH_PREFIX, prefixEdit.text.toString())
                .apply()
            Toast.makeText(activity, "Saved. Restart CloudStream.", Toast.LENGTH_LONG).show()
        }

        clear.setOnClickListener {
            prefs.edit().remove(PREF_ROUTES).apply()
            status.text = "Discovered search providers cleared. Restart CloudStream."
        }

        discover.setOnClickListener {
            val raw = manifestsEdit.text.toString().trim()
            val bases = parseManifestInput(raw)
            if (bases.isEmpty()) {
                Toast.makeText(activity, "Paste at least one manifest URL first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            discover.isEnabled = false
            status.text = "Reading manifests…"

            Thread {
                try {
                    val discovered = discoverSearchRoutes(bases)
                    prefs.edit()
                        .putString(PREF_MANIFESTS, raw)
                        .putString(PREF_HOME_NAME, homeNameEdit.text.toString().trim())
                        .putString(PREF_SEARCH_PREFIX, prefixEdit.text.toString())
                        .putString(PREF_ROUTES, saveRouteGroups(discovered))
                        .apply()

                    activity.runOnUiThread {
                        discover.isEnabled = true
                        status.text = if (discovered.isEmpty()) {
                            "No Search catalogs were discovered. Keep Search enabled for each TPB tube source and try again."
                        } else {
                            "Found ${discovered.size}:\n" + discovered.joinToString(", ") { it.sourceName } +
                                "\n\nSaved. Fully close and reopen CloudStream."
                        }
                    }
                } catch (t: Throwable) {
                    activity.runOnUiThread {
                        discover.isEnabled = true
                        status.text = "Discovery failed: ${t.javaClass.simpleName}: ${t.message ?: "unknown error"}"
                    }
                }
            }.start()
        }

        dialog.show()
    }
}

/** One Home provider; global search intentionally returns nothing to avoid duplicates. */
private class TPBHomeProvider(
    override var name: String,
    private val manifestBases: List<String>
) : TPBBaseProvider() {
    override var mainUrl: String = manifestBases.firstOrNull().orEmpty()
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others, TvType.Movie)
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = manifestBases.amap { base ->
            val manifest = fetchManifest(base) ?: return@amap emptyList<HomePageList>()
            manifest.catalogs
                .filter { it.isHomeCatalog() }
                .amap { catalog -> catalog.toHomePageList(base, this, page) }
                .filter { it.list.isNotEmpty() }
        }.flatten()

        return newHomePageResponse(
            lists.distinctBy { it.name.lowercase(Locale.ROOT) },
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()
}

/** One instance per discovered tube source. No Home rows, source-specific search only. */
private class TPBSearchProvider(
    override var name: String,
    private val sourceName: String,
    private val routes: List<SearchRoute>
) : TPBBaseProvider() {
    override var mainUrl: String = routes.firstOrNull()?.baseUrl.orEmpty()
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others, TvType.Movie)
    override val hasMainPage = false

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = encodePath(query)

        return routes.amap { route ->
            try {
                val url = "${route.baseUrl}/catalog/${encodePath(route.type)}/${encodePath(route.catalogId)}/search=$encoded.json"
                val response = app.get(url, timeout = 120L).parsedSafe<CatalogResponse>()
                response?.metas.orEmpty().map { entry ->
                    entry.toSearchResponse(this, route.baseUrl, sourceName)
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }.flatten().distinctBy { it.url }
    }
}

private abstract class TPBBaseProvider : MainAPI() {
    override suspend fun load(url: String): LoadResponse {
        val bridge = parseJson<BridgeItem>(url)
        val fallback = bridge.entry
        val type = fallback.type ?: bridge.type ?: "movie"
        val id = fallback.id

        val meta = try {
            val res = app.get(
                "${bridge.baseUrl}/meta/${encodePath(type)}/${encodePath(id)}.json",
                timeout = 120L
            ).parsedSafe<CatalogResponse>()
            res?.meta ?: res?.metas?.firstOrNull { it.id == id } ?: res?.metas?.firstOrNull()
        } catch (_: Throwable) {
            null
        } ?: fallback

        val data = StreamLoadData(
            baseUrl = bridge.baseUrl,
            type = meta.type ?: type,
            id = meta.id
        ).toJson()

        return newMovieLoadResponse(
            meta.name,
            url,
            TvType.Others,
            data
        ) {
            posterUrl = meta.poster
            backgroundPosterUrl = meta.background
            plot = meta.description
            year = meta.yearString?.take(4)?.toIntOrNull()
            tags = meta.genre ?: meta.genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<StreamLoadData>(data)
        val response = try {
            app.get(
                "${loadData.baseUrl}/stream/${encodePath(loadData.type)}/${encodePath(loadData.id)}.json",
                timeout = 120L
            ).parsedSafe<StreamsResponse>()
        } catch (_: Throwable) {
            null
        } ?: return false

        response.streams.forEach { stream ->
            stream.emit(subtitleCallback, callback)
        }
        return response.streams.isNotEmpty()
    }
}

private data class Manifest(
    @JsonProperty("catalogs") val catalogs: List<Catalog> = emptyList()
)

private data class Extra(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("isRequired") val isRequired: Boolean? = null
)

private data class Catalog(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("id") val id: String = "",
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("types") val types: List<String>? = null,
    @JsonProperty("extra") val extra: List<Extra>? = null,
    @JsonProperty("extraSupported") val extraSupported: List<String>? = null
) {
    fun allTypes(): List<String> = when {
        !types.isNullOrEmpty() -> types.filter { it.isNotBlank() }
        !type.isNullOrBlank() -> listOf(type)
        else -> listOf("movie")
    }

    fun supportsSearch(): Boolean {
        return extra?.any { it.name.equals("search", true) } == true ||
            extraSupported?.any { it.equals("search", true) } == true
    }

    fun hasRequiredExtra(): Boolean = extra?.any { it.isRequired == true } == true

    fun isHomeCatalog(): Boolean {
        if (hasRequiredExtra()) return false
        val label = "${name.orEmpty()} $id".lowercase(Locale.ROOT)
        return label.contains("recent")
    }

    suspend fun toHomePageList(base: String, provider: TPBBaseProvider, page: Int): HomePageList {
        val skip = ((page - 1).coerceAtLeast(0) * 20)
        val items = allTypes().amap { t ->
            try {
                val suffix = if (page > 1) "/skip=$skip" else ""
                val response = app.get(
                    "$base/catalog/${encodePath(t)}/${encodePath(id)}$suffix.json",
                    timeout = 120L
                ).parsedSafe<CatalogResponse>()
                response?.metas.orEmpty().map { it.toSearchResponse(provider, base, deriveSourceName(name, id)) }
            } catch (_: Throwable) {
                emptyList()
            }
        }.flatten()
        return HomePageList(name ?: id, items.distinctBy { it.url })
    }
}

private data class CatalogResponse(
    @JsonProperty("metas") val metas: List<CatalogEntry>? = null,
    @JsonProperty("meta") val meta: CatalogEntry? = null
)

private data class CatalogEntry(
    @JsonProperty("name") val name: String,
    @JsonProperty("id") val id: String,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("background") val background: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("genre") val genre: List<String>? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("year") val yearString: String? = null
) {
    fun toSearchResponse(provider: TPBBaseProvider, baseUrl: String, source: String): SearchResponse {
        val payload = BridgeItem(baseUrl = baseUrl, type = type, source = source, entry = this).toJson()
        return provider.newMovieSearchResponse(fixTitle(name), payload, TvType.Others) {
            posterUrl = poster
        }
    }
}

private data class BridgeItem(
    val baseUrl: String,
    val type: String? = null,
    val source: String? = null,
    val entry: CatalogEntry
)

private data class StreamLoadData(
    val baseUrl: String,
    val type: String,
    val id: String
)

private data class Subtitle(
    val url: String? = null,
    val lang: String? = null,
    val id: String? = null
)

private data class ProxyHeaders(
    val request: Map<String, String>? = null
)

private data class BehaviorHints(
    val proxyHeaders: ProxyHeaders? = null,
    val headers: Map<String, String>? = null
)

private data class Stream(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val behaviorHints: BehaviorHints? = null,
    val subtitles: List<Subtitle> = emptyList()
) {
    suspend fun emit(
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name ?: "TPB",
                    name = buildDisplayName(name, title),
                    url = url,
                    type = INFER_TYPE
                ) {
                    quality = inferQuality(description, title, name)
                    headers = behaviorHints?.proxyHeaders?.request
                        ?: behaviorHints?.headers
                        ?: emptyMap()
                    val ref = headers.entries.firstOrNull { it.key.equals("referer", true) }?.value
                    if (!ref.isNullOrBlank()) referer = ref
                }
            )

            subtitles.forEach { sub ->
                val subUrl = sub.url ?: return@forEach
                val label = SubtitleHelper.fromTagToEnglishLanguageName(sub.lang ?: "")
                    ?: sub.lang
                    ?: "Subtitle"
                subtitleCallback(newSubtitleFile(label, subUrl))
            }
        }

        if (!ytId.isNullOrBlank()) {
            loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
        }
        if (!externalUrl.isNullOrBlank()) {
            loadExtractor(externalUrl, subtitleCallback, callback)
        }
    }
}

private data class StreamsResponse(
    val streams: List<Stream> = emptyList()
)

private data class SearchRoute(
    val baseUrl: String,
    val catalogId: String,
    val type: String
)

private data class SearchRouteGroup(
    val sourceName: String,
    val routes: List<SearchRoute>
)

private fun discoverSearchRoutes(bases: List<String>): List<SearchRouteGroup> {
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
                c.optString("type", "movie").trim().ifBlank { "movie" }.let { typeList += it }
            }

            val label = "$name $id".lowercase(Locale.ROOT)
            val explicit = jsonCatalogSearchRequired(c) || label.contains("search")
            typeList.distinct().forEach { type ->
                candidates += Candidate(source, SearchRoute(base, id, type), explicit)
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

private fun saveRouteGroups(groups: List<SearchRouteGroup>): String {
    val arr = JSONArray()
    groups.forEach { group ->
        val routes = JSONArray()
        group.routes.forEach { route ->
            routes.put(JSONObject().apply {
                put("baseUrl", route.baseUrl)
                put("catalogId", route.catalogId)
                put("type", route.type)
            })
        }
        arr.put(JSONObject().apply {
            put("sourceName", group.sourceName)
            put("routes", routes)
        })
    }
    return arr.toString()
}

private fun loadRouteGroups(json: String): List<SearchRouteGroup> {
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
                        val type = r.optString("type", "movie").trim().ifBlank { "movie" }
                        if (base.isNotBlank() && id.isNotBlank()) add(SearchRoute(base, id, type))
                    }
                }
                add(SearchRouteGroup(name, routes))
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun jsonCatalogSupportsSearch(c: JSONObject): Boolean {
    val supported = c.optJSONArray("extraSupported")
    if (supported != null) {
        for (i in 0 until supported.length()) {
            if (supported.optString(i).equals("search", true)) return true
        }
    }
    val extra = c.optJSONArray("extra")
    if (extra != null) {
        for (i in 0 until extra.length()) {
            if (extra.optJSONObject(i)?.optString("name")?.equals("search", true) == true) return true
        }
    }
    return false
}

private fun jsonCatalogSearchRequired(c: JSONObject): Boolean {
    val extra = c.optJSONArray("extra") ?: return false
    for (i in 0 until extra.length()) {
        val e = extra.optJSONObject(i) ?: continue
        if (e.optString("name").equals("search", true) && e.optBoolean("isRequired", false)) return true
    }
    return false
}

private suspend fun fetchManifest(base: String): Manifest? {
    return try {
        app.get("$base/manifest.json", timeout = 60L).parsedSafe<Manifest>()
    } catch (_: Throwable) {
        null
    }
}

private fun parseManifestInput(raw: String): List<String> {
    return raw
        .lineSequence()
        .flatMap { it.split(',').asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { normalizeManifestBase(it) }
        .distinct()
        .toList()
}

private fun normalizeManifestBase(input: String): String? {
    var s = input.trim()
    if (s.isBlank()) return null
    if (s.startsWith("stremio://", true)) s = "https://" + s.substringAfter("stremio://")
    if (!s.startsWith("https://", true) && !s.startsWith("http://", true)) return null
    s = s.substringBefore('#').removeSuffix("/")
    if (s.substringBefore('?').endsWith("/manifest.json", true)) {
        val query = s.substringAfter('?', "")
        s = s.substringBefore('?').dropLast("/manifest.json".length)
        if (query.isNotBlank()) {
            // TPB currently uses path-based configuration; query-configured Stremio addons are
            // intentionally not rewritten because catalog/meta/stream URL semantics vary.
        }
    }
    return s.removeSuffix("/")
}

private fun Context.findActivity(): android.app.Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is android.app.Activity) return current
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return current as? android.app.Activity
}

private fun httpGetText(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) TPBBridge/1")
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

private fun deriveSourceName(name: String?, id: String): String {
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

private fun isGenericSourceName(value: String): Boolean {
    return value.trim().lowercase(Locale.ROOT) in setOf("search", "recent", "studio", "tag", "performer", "porn")
}

private fun encodePath(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun buildDisplayName(name: String?, title: String?): String = when {
    !name.isNullOrBlank() && !title.isNullOrBlank() -> "$name $title"
    !title.isNullOrBlank() -> title
    !name.isNullOrBlank() -> name
    else -> "TPB"
}

private fun inferQuality(vararg values: String?): Int {
    val q = values.firstNotNullOfOrNull { value ->
        value?.let { Regex("(\\d{3,4}[pP])").find(it)?.groupValues?.getOrNull(1) }
    }
    return getQualityFromName(q)
}
