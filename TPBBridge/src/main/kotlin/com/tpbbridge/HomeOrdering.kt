/*
 * TPBBridge - Home catalogue ordering and source enable/disable support.
 *
 * This file is intentionally isolated from stream/debrid handling. It controls
 * source discovery, Home-row ordering, source filtering, and the management UI.
 *
 * GPL-3.0-or-later.
 */
package com.tpbbridge

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val PREF_HOME_SOURCES = "home_sources_v8"
internal const val PREF_HOME_ORDER = "home_order_v8"
internal const val PREF_DISABLED_SOURCES = "disabled_sources_v9"

internal fun homeSourceKey(name: String): String =
    name.trim().lowercase(Locale.ROOT)

internal fun saveHomeNameList(names: List<String>): String {
    val arr = JSONArray()
    names
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
        .forEach { arr.put(it) }
    return arr.toString()
}

internal fun loadHomeNameList(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val value = arr.optString(i).trim()
                if (value.isNotBlank()) add(value)
            }
        }.distinctBy(::homeSourceKey)
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun isSourceDisabled(source: String, disabledSources: Collection<String>): Boolean {
    val key = homeSourceKey(source)
    return disabledSources.any { homeSourceKey(it) == key }
}

/**
 * Discover normal Home/Recent source names in manifest order. This mirrors the
 * same rule used by Catalog.isHomeCatalog(): required-extra catalogs are never
 * Home rows, and only Recent catalogs are included.
 */
internal fun discoverHomeSources(bases: List<String>): List<String> {
    val sources = mutableListOf<String>()
    bases.forEach { base ->
        val root = JSONObject(httpGetText("$base/manifest.json"))
        val catalogs = root.optJSONArray("catalogs") ?: JSONArray()
        for (i in 0 until catalogs.length()) {
            val c = catalogs.optJSONObject(i) ?: continue
            val name = c.optString("name", "")
            val id = c.optString("id", "").trim()
            if (id.isBlank()) continue

            val extra = c.optJSONArray("extra")
            var hasRequired = false
            if (extra != null) {
                for (j in 0 until extra.length()) {
                    if (extra.optJSONObject(j)?.optBoolean("isRequired", false) == true) {
                        hasRequired = true
                        break
                    }
                }
            }
            if (hasRequired) continue
            if (!"$name $id".lowercase(Locale.ROOT).contains("recent")) continue

            val source = deriveSourceName(name, id)
            if (source.isNotBlank() && !isGenericSourceName(source)) sources += source
        }
    }
    return sources.distinctBy(::homeSourceKey)
}

/**
 * Build the complete manageable source list. Home sources stay first in manifest
 * order; search/filter-only sources are appended so they can also be disabled.
 */
internal fun managedSourceList(
    homeSources: List<String>,
    searchGroups: List<SearchRouteGroup>,
    facetRoutes: List<FacetRoute>
): List<String> = buildList {
    addAll(homeSources)
    addAll(searchGroups.map { it.sourceName })
    addAll(facetRoutes.map { it.sourceName })
}.map { it.trim() }
    .filter { it.isNotBlank() }
    .distinctBy(::homeSourceKey)

/**
 * Keeps the user's complete saved order, including temporarily unavailable
 * sources, and appends genuinely new sources at the bottom.
 */
internal fun reconcileHomeOrder(
    savedOrder: List<String>,
    discoveredSources: List<String>
): List<String> {
    val out = savedOrder
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
        .toMutableList()
    val known = out.mapTo(mutableSetOf(), ::homeSourceKey)

    discoveredSources
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
        .forEach { source ->
            if (known.add(homeSourceKey(source))) out += source
        }

    return out
}

/** Current visible/manageable sources, rendered in the user's saved order. */
internal fun visibleHomeOrder(
    fullOrder: List<String>,
    discoveredSources: List<String>
): List<String> {
    val discovered = discoveredSources
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
    val byKey = discovered.associateBy(::homeSourceKey)
    return reconcileHomeOrder(fullOrder, discovered)
        .mapNotNull { byKey[homeSourceKey(it)] }
}

/**
 * Applies a reordered visible subset while leaving temporarily missing sources
 * in their saved slots. This prevents a transient source outage from destroying
 * its remembered position.
 */
internal fun mergeVisibleHomeOrder(
    fullOrder: List<String>,
    visibleOrder: List<String>,
    discoveredSources: List<String>
): List<String> {
    val discovered = discoveredSources
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
    val visibleKeys = discovered.mapTo(mutableSetOf(), ::homeSourceKey)
    val reordered = visibleOrder
        .filter { homeSourceKey(it) in visibleKeys }
        .distinctBy(::homeSourceKey)

    val out = reconcileHomeOrder(fullOrder, discovered).toMutableList()
    var next = 0
    for (i in out.indices) {
        if (homeSourceKey(out[i]) in visibleKeys && next < reordered.size) {
            out[i] = reordered[next++]
        }
    }

    while (next < reordered.size) {
        val value = reordered[next++]
        if (out.none { homeSourceKey(it) == homeSourceKey(value) }) out += value
    }
    return out.distinctBy(::homeSourceKey)
}

/**
 * Update enable/disable state only for currently manageable sources while
 * retaining the state of temporarily unavailable sources.
 */
internal fun mergeVisibleDisabledSources(
    fullDisabled: List<String>,
    discoveredSources: List<String>,
    visibleDisabled: Collection<String>
): List<String> {
    val visibleKeys = discoveredSources
        .map { homeSourceKey(it) }
        .toSet()
    val out = fullDisabled
        .map { it.trim() }
        .filter { it.isNotBlank() && homeSourceKey(it) !in visibleKeys }
        .distinctBy(::homeSourceKey)
        .toMutableList()

    visibleDisabled
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)
        .forEach { out += it }

    return out.distinctBy(::homeSourceKey)
}

internal fun orderHomeRows(rows: Collection<HomeRow>, homeOrder: List<String>): List<HomeRow> {
    if (rows.size < 2 || homeOrder.isEmpty()) return rows.toList()
    val rank = homeOrder
        .distinctBy(::homeSourceKey)
        .mapIndexed { index, name -> homeSourceKey(name) to index }
        .toMap()

    return rows.withIndex()
        .sortedWith(
            compareBy<IndexedValue<HomeRow>> { rank[homeSourceKey(it.value.name)] ?: Int.MAX_VALUE }
                .thenBy { it.index }
        )
        .map { it.value }
}

/**
 * v9 Home provider. Disabled sources are removed before Home catalog network
 * requests. Search groups passed here are already filtered by the plugin.
 */
internal class TPBOrderedHomeProvider(
    override var name: String,
    internal val manifestBases: List<String>,
    internal val searchGroups: List<SearchRouteGroup>,
    internal val combinedSearchEnabled: Boolean,
    internal val homeOrder: List<String>,
    internal val disabledSources: List<String>
) : TPBBaseProvider(manifestBases) {
    override var mainUrl: String = "$SAFE_MAIN_URL/home"
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
    override val searchTimeoutMs: Long? = 90_000L

    private val disabledKeys = disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = manifestBases.amap { base ->
            val manifest = fetchManifest(base) ?: return@amap emptyList<HomeRow>()
            manifest.catalogs
                .filter { catalog ->
                    if (!catalog.isHomeCatalog()) return@filter false
                    val source = deriveSourceName(catalog.name, catalog.id)
                    homeSourceKey(source) !in disabledKeys
                }
                .amap { catalog -> catalog.toHomeRow(base, this, page) }
                .filter { it.items.isNotEmpty() }
        }.flatten()

        val merged = linkedMapOf<String, HomeRow>()
        rows.forEach { row ->
            val key = homeSourceKey(row.name)
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

        val ordered = orderHomeRows(merged.values, homeOrder)
        return newHomePageResponse(
            ordered.map { HomePageList(it.name, it.items, it.horizontal) },
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

        val encoded = encodePath(query)
        val skip = (page - 1) * SEARCH_PAGE_SIZE
        val entries = usable.amap { named ->
            val route = named.route
            try {
                val extras = buildString {
                    append("search=").append(encoded)
                    if (page > 1 && route.supportsSkip) append("&skip=").append(skip)
                }
                app.get(
                    "${route.baseUrl}/catalog/${encodePath(route.type)}/${encodePath(route.catalogId)}/$extras.json",
                    timeout = 90L
                ).parsedSafe<CatalogResponse>()
                    ?.metas.orEmpty()
                    .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    .map { Triple(named.source, route.baseUrl, it) }
            } catch (_: Throwable) {
                emptyList()
            }
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

internal data class HomeSourceManagementResult(
    val fullOrder: List<String>,
    val disabledSources: List<String>
)

internal fun showHomeSourceManagerDialog(
    activity: Activity,
    discoveredSources: List<String>,
    initialFullOrder: List<String>,
    initialDisabledSources: List<String>,
    onDone: (HomeSourceManagementResult) -> Unit
) {
    fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    val defaults = discoveredSources
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy(::homeSourceKey)

    if (defaults.isEmpty()) {
        AlertDialog.Builder(activity)
            .setTitle("Manage sources")
            .setMessage("No sources are available yet. Save + refresh once after enabling Recent or Search in TPB.")
            .setPositiveButton("OK", null)
            .show()
        return
    }

    var working = visibleHomeOrder(initialFullOrder, defaults).toMutableList()
    val disabledKeys = initialDisabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
    val container = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(8))
    }

    fun render() {
        container.removeAllViews()
        container.addView(TextView(activity).apply {
            text = "Off hides a source from Home and all TPBBridge searches. Order controls Home rows."
            textSize = 13f
            alpha = 0.76f
            setPadding(0, 0, 0, dp(8))
        })

        working.forEachIndexed { index, source ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }

            val enabled = CheckBox(activity).apply {
                text = "${index + 1}. $source"
                textSize = 16f
                isChecked = homeSourceKey(source) !in disabledKeys
                setPadding(dp(2), 0, dp(6), 0)
                setOnCheckedChangeListener { _, checked ->
                    val key = homeSourceKey(source)
                    if (checked) disabledKeys.remove(key) else disabledKeys.add(key)
                }
            }
            row.addView(
                enabled,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            val up = Button(activity).apply {
                text = "↑"
                textSize = 18f
                isEnabled = index > 0
                minimumWidth = 0
                minWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    if (index > 0) {
                        val item = working.removeAt(index)
                        working.add(index - 1, item)
                        render()
                    }
                }
            }
            val down = Button(activity).apply {
                text = "↓"
                textSize = 18f
                isEnabled = index < working.lastIndex
                minimumWidth = 0
                minWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    if (index < working.lastIndex) {
                        val item = working.removeAt(index)
                        working.add(index + 1, item)
                        render()
                    }
                }
            }
            row.addView(up)
            row.addView(down)
            container.addView(row)
        }

        container.addView(Button(activity).apply {
            text = "Reset order"
            setOnClickListener {
                working = defaults.toMutableList()
                render()
            }
        })
    }

    render()
    val scroll = ScrollView(activity).apply { addView(container) }
    AlertDialog.Builder(activity)
        .setTitle("Manage sources")
        .setView(scroll)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Done") { _, _ ->
            val visibleDisabled = defaults.filter { homeSourceKey(it) in disabledKeys }
            onDone(
                HomeSourceManagementResult(
                    fullOrder = mergeVisibleHomeOrder(initialFullOrder, working, defaults),
                    disabledSources = mergeVisibleDisabledSources(
                        initialDisabledSources,
                        defaults,
                        visibleDisabled
                    )
                )
            )
        }
        .show()
}
