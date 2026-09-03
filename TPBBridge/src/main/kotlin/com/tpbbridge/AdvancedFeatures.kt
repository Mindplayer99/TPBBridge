/*
 * TPBBridge - optional aggregate search and TPB filter-catalog support.
 *
 * GPL-3.0-or-later.
 */
package com.tpbbridge

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val PREF_PARENT_SEARCH = "parent_search_enabled"
internal const val PREF_FACET_STUDIO = "facet_studio_enabled"
internal const val PREF_FACET_PERFORMER = "facet_performer_enabled"
internal const val PREF_FACET_TAG = "facet_tag_enabled"
internal const val PREF_FACET_ROUTES = "facet_routes_v6"

private const val MAX_FUZZY_FACET_OPTIONS = 6
private const val MAX_FUZZY_FACET_REQUESTS = 30

internal enum class FacetKind(val token: String, val label: String) {
    STUDIO("studio", "Studio"),
    PERFORMER("performer", "Performer"),
    TAG("tag", "Tag");

    companion object {
        fun fromToken(value: String?): FacetKind? = entries.firstOrNull {
            it.token.equals(value?.trim(), true)
        }
    }
}

internal data class FacetRoute(
    val baseUrl: String,
    val catalogId: String,
    val type: String,
    val sourceName: String,
    val kind: FacetKind,
    val options: List<String>,
    val supportsSkip: Boolean = false
)

internal data class DiscoveryBundle(
    val searchGroups: List<SearchRouteGroup>,
    val facetRoutes: List<FacetRoute>
)

internal fun discoverBridgeRoutes(bases: List<String>): DiscoveryBundle {
    data class SearchCandidate(
        val source: String,
        val route: SearchRoute,
        val explicit: Boolean
    )

    val searchCandidates = mutableListOf<SearchCandidate>()
    val facets = mutableListOf<FacetRoute>()

    bases.forEach { base ->
        val manifestText = fetchManifestText(base)
        val root = JSONObject(manifestText)
        val catalogs = root.optJSONArray("catalogs") ?: JSONArray()

        for (i in 0 until catalogs.length()) {
            val c = catalogs.optJSONObject(i) ?: continue
            val name = c.optString("name", "")
            val id = c.optString("id", "").trim()
            if (id.isBlank()) continue

            val source = deriveSourceName(name, id)
            if (source.isBlank() || isGenericSourceName(source)) continue

            val typeList = jsonCatalogTypes(c)
            val skip = jsonCatalogHasExtra(c, "skip")

            if (jsonCatalogSupportsSearch(c)) {
                val label = "$name $id".lowercase(Locale.ROOT)
                val explicit = jsonCatalogSearchRequired(c) || label.contains("search")
                typeList.forEach { type ->
                    searchCandidates += SearchCandidate(
                        source,
                        SearchRoute(base, id, type, skip),
                        explicit
                    )
                }
            }

            val kind = facetKindFromCatalogId(id) ?: continue
            val options = jsonCatalogGenreOptions(c)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("All", true) }
                .distinctBy { it.lowercase(Locale.ROOT) }
            if (options.isEmpty()) continue

            typeList.forEach { type ->
                facets += FacetRoute(
                    baseUrl = base,
                    catalogId = id,
                    type = type,
                    sourceName = source,
                    kind = kind,
                    options = options,
                    supportsSkip = skip
                )
            }
        }
    }

    val searchGroups = searchCandidates
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

    val mergedFacets = facets
        .groupBy { listOf(it.baseUrl, it.catalogId, it.type, it.sourceName, it.kind.token).joinToString("\u0000") }
        .map { (_, group) ->
            val first = group.first()
            first.copy(
                options = group.flatMap { it.options }
                    .distinctBy { it.lowercase(Locale.ROOT) },
                supportsSkip = group.any { it.supportsSkip }
            )
        }
        .sortedWith(compareBy<FacetRoute>({ it.kind.ordinal }, { it.sourceName.lowercase(Locale.ROOT) }))

    return DiscoveryBundle(searchGroups, mergedFacets)
}

private fun jsonCatalogTypes(c: JSONObject): List<String> {
    val out = mutableListOf<String>()
    val types = c.optJSONArray("types")
    if (types != null) {
        for (i in 0 until types.length()) {
            val value = types.optString(i).trim()
            if (value.isNotBlank()) out += value
        }
    }
    if (out.isEmpty()) {
        out += c.optString("type", "Porn").trim().ifBlank { "Porn" }
    }
    return out.distinct()
}

private fun facetKindFromCatalogId(id: String): FacetKind? {
    val lower = id.lowercase(Locale.ROOT)
    return when {
        lower.endsWith("_studio") -> FacetKind.STUDIO
        lower.endsWith("_performer") -> FacetKind.PERFORMER
        lower.endsWith("_tag") -> FacetKind.TAG
        else -> null
    }
}

private fun jsonCatalogGenreOptions(c: JSONObject): List<String> {
    val extra = c.optJSONArray("extra") ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until extra.length()) {
        val item = extra.optJSONObject(i) ?: continue
        if (!item.optString("name").equals("genre", true)) continue
        val options = item.optJSONArray("options") ?: continue
        for (j in 0 until options.length()) {
            val value = options.optString(j).trim()
            if (value.isNotBlank()) out += value
        }
    }
    return out
}

internal fun saveFacetRoutes(routes: List<FacetRoute>): String {
    val arr = JSONArray()
    routes.forEach { route ->
        val options = JSONArray()
        route.options.forEach { options.put(it) }
        arr.put(JSONObject().apply {
            put("baseUrl", route.baseUrl)
            put("catalogId", route.catalogId)
            put("type", route.type)
            put("sourceName", route.sourceName)
            put("kind", route.kind.token)
            put("supportsSkip", route.supportsSkip)
            put("options", options)
        })
    }
    return arr.toString()
}

internal fun loadFacetRoutes(json: String): List<FacetRoute> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val base = obj.optString("baseUrl", "").trim()
                val id = obj.optString("catalogId", "").trim()
                val type = obj.optString("type", "Porn").trim().ifBlank { "Porn" }
                val source = obj.optString("sourceName", "").trim()
                val kind = FacetKind.fromToken(obj.optString("kind")) ?: continue
                val optionsJson = obj.optJSONArray("options") ?: JSONArray()
                val options = buildList {
                    for (j in 0 until optionsJson.length()) {
                        val value = optionsJson.optString(j).trim()
                        if (value.isNotBlank() && !value.equals("All", true)) add(value)
                    }
                }.distinctBy { it.lowercase(Locale.ROOT) }
                if (base.isNotBlank() && id.isNotBlank() && source.isNotBlank() && options.isNotEmpty()) {
                    add(
                        FacetRoute(
                            baseUrl = base,
                            catalogId = id,
                            type = type,
                            sourceName = source,
                            kind = kind,
                            options = options,
                            supportsSkip = obj.optBoolean("supportsSkip", false)
                        )
                    )
                }
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun advancedConfigurationSummary(
    manifestCount: Int,
    searchGroups: List<SearchRouteGroup>,
    facetRoutes: List<FacetRoute>
): String {
    val base = configurationSummary(manifestCount, searchGroups)
    if (manifestCount == 0) return base

    fun count(kind: FacetKind): Int = facetRoutes
        .filter { it.kind == kind }
        .map { it.sourceName.lowercase(Locale.ROOT) }
        .distinct()
        .size

    return buildString {
        append(base)
        append("\nOptional filters available: ")
        append("Studio ").append(count(FacetKind.STUDIO))
        append(" • Performer ").append(count(FacetKind.PERFORMER))
        append(" • Tag ").append(count(FacetKind.TAG))
    }
}

internal class TPBUnifiedHomeProvider(
    override var name: String,
    internal val manifestBases: List<String>,
    internal val searchGroups: List<SearchRouteGroup>,
    internal val combinedSearchEnabled: Boolean
) : TPBBaseProvider(manifestBases) {
    override var mainUrl: String = "$SAFE_MAIN_URL/home"
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    override val searchTimeoutMs: Long? = 90_000L

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = manifestBases.amap { base ->
            val manifest = fetchManifest(base) ?: return@amap emptyList<HomeRow>()
            manifest.catalogs
                .filter { it.isHomeCatalog() }
                .amap { catalog -> catalog.toHomeRow(base, this, page) }
                .filter { it.items.isNotEmpty() }
        }.flatten()

        val merged = linkedMapOf<String, HomeRow>()
        rows.forEach { row ->
            val key = row.name.lowercase(Locale.ROOT)
            val old = merged[key]
            if (old == null) {
                merged[key] = row
            } else {
                merged[key] = old.copy(
                    items = (old.items + row.items).distinctBy { it.url },
                    horizontal = old.horizontal || row.horizontal
                )
            }
        }

        return newHomePageResponse(
            merged.values.map { HomePageList(it.name, it.items, it.horizontal) },
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> =
        if (combinedSearchEnabled) searchPage(query, 1).first else emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (!combinedSearchEnabled) return newSearchResponseList(emptyList(), false)
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank() || page < 1) return emptyList<SearchResponse>() to false

        data class NamedRoute(val source: String, val route: SearchRoute)
        val namedRoutes = searchGroups.flatMap { group ->
            group.routes.map { NamedRoute(group.sourceName, it) }
        }
        val usable = if (page == 1) namedRoutes else namedRoutes.filter { it.route.supportsSkip }
        if (usable.isEmpty()) return emptyList<SearchResponse>() to false

        val encoded = encodePath(query.trim())
        val entries = usable.amap { named ->
            val route = named.route
            val extras = "search=$encoded"
            fetchSearchCatalogEntries(
                route.baseUrl,
                route.type,
                route.catalogId,
                extras,
                page,
                route.supportsSkip
            ).orEmpty()
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .map { Triple(named.source, route.baseUrl, it.withFallbackType(route.type)) }
        }.flatten()

        val deduped = entries.distinctBy { (_, base, entry) ->
            "${baseRef(base)}|${entry.type}|${entry.id}"
        }
        val results = deduped.mapNotNull { (source, base, entry) ->
            entry.toSearchResponse(this, base, source)
        }
        val hasNext = namedRoutes.any { it.route.supportsSkip } && results.isNotEmpty()
        return results to hasNext
    }
}

internal class TPBFacetProvider(
    override var name: String,
    internal val kind: FacetKind,
    internal val routes: List<FacetRoute>
) : TPBBaseProvider(routes.map { it.baseUrl }.distinct()) {
    override var mainUrl: String = "$SAFE_MAIN_URL/filter/${kind.token}"
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false
    override val searchTimeoutMs: Long? = 90_000L

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    private suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank() || page < 1) return emptyList<SearchResponse>() to false
        val q = query.trim()
        val kindRoutes = routes.filter { it.kind == kind }
        if (kindRoutes.isEmpty()) return emptyList<SearchResponse>() to false

        val allOptions = kindRoutes.flatMap { it.options }
            .distinctBy { it.lowercase(Locale.ROOT) }
        val exact = allOptions.firstOrNull { it.equals(q, true) }
        val selected = if (exact != null) {
            listOf(exact)
        } else {
            val prefix = allOptions
                .filter { it.startsWith(q, true) }
                .sortedBy { it.length }
            val contains = allOptions
                .filter { !it.startsWith(q, true) && it.contains(q, true) }
                .sortedBy { it.length }
            (prefix + contains).take(MAX_FUZZY_FACET_OPTIONS)
        }
        if (selected.isEmpty()) return emptyList<SearchResponse>() to false

        data class FacetRequest(val route: FacetRoute, val option: String)
        val requests = buildList {
            selected.forEach { selectedOption ->
                kindRoutes.forEach routeLoop@ { route ->
                    if (page > 1 && !route.supportsSkip) return@routeLoop
                    val canonical = route.options.firstOrNull { it.equals(selectedOption, true) }
                        ?: return@routeLoop
                    add(FacetRequest(route, canonical))
                }
            }
        }.let { if (exact == null) it.take(MAX_FUZZY_FACET_REQUESTS) else it }

        if (requests.isEmpty()) return emptyList<SearchResponse>() to false

        val entries = requests.amap { request ->
            val route = request.route
            val extras = "genre=${encodePath(request.option)}"
            fetchSearchCatalogEntries(
                route.baseUrl,
                route.type,
                route.catalogId,
                extras,
                page,
                route.supportsSkip
            ).orEmpty()
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .map { Triple(route, request.option, it.withFallbackType(route.type)) }
        }.flatten()

        val deduped = entries.distinctBy { (route, _, entry) ->
            "${baseRef(route.baseUrl)}|${entry.type}|${entry.id}"
        }
        val results = deduped.mapNotNull { (route, _, entry) ->
            entry.toSearchResponse(this, route.baseUrl, route.sourceName)
        }
        val hasNext = requests.any { it.route.supportsSkip } && results.isNotEmpty()
        return results to hasNext
    }
}
