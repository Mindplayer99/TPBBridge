/*
 * TPBBridge - CloudStream plugin
 *
 * Reads configured Stremio/TPB manifests, keeps normal Recent catalogs together
 * in one Home provider, and exposes each Search catalog as its own CloudStream
 * provider. Optional switches add parent/all-source Search and TPB's required
 * Studio/Performer/Tag filter catalogs without polluting Home rows. Sources can
 * be locally reordered or disabled without touching stream/debrid behavior.
 *
 * GPL-3.0-or-later. Stremio protocol handling is derived from the GPL bridge
 * approach used by Hexated/phisher98 and compatible forks.
 */
package com.tpbbridge

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

internal const val PREF_FILE = "TPBBridge"
internal const val PREF_MANIFESTS = "manifest_urls"
internal const val PREF_HOME_NAME = "home_name"
internal const val PREF_SEARCH_PREFIX = "search_prefix"
internal const val PREF_ROUTES = "search_routes"
internal const val DEFAULT_HOME_NAME = "TPB Tubes"
internal const val DEFAULT_SEARCH_PREFIX = ""
internal const val SAFE_MAIN_URL = "https://tpbbridge.invalid"
internal const val MANIFEST_CACHE_MS = 5 * 60 * 1000L
internal const val SEARCH_PAGE_SIZE = 20

@CloudstreamPlugin
class TPBBridgePlugin : Plugin() {
    private val registeredProviders = mutableListOf<MainAPI>()

    override fun load(context: Context) {
        openSettings = { settingsContext -> showSettings(settingsContext) }

        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val bases = parseManifestInput(prefs.getString(PREF_MANIFESTS, "").orEmpty())
        val homeName = prefs.getString(PREF_HOME_NAME, DEFAULT_HOME_NAME)
            ?.trim().orEmpty().ifBlank { DEFAULT_HOME_NAME }
        val prefix = prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX).orEmpty()
        val routeGroups = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())
        val facetRoutes = loadFacetRoutes(prefs.getString(PREF_FACET_ROUTES, "").orEmpty())
        val homeOrder = loadHomeNameList(prefs.getString(PREF_HOME_ORDER, "").orEmpty())
        val disabledSources = loadHomeNameList(prefs.getString(PREF_DISABLED_SOURCES, "").orEmpty())

        replaceProviders(
            bases = bases,
            homeName = homeName,
            prefix = prefix,
            routeGroups = routeGroups,
            facetRoutes = facetRoutes,
            homeOrder = homeOrder,
            disabledSources = disabledSources,
            parentSearch = prefs.getBoolean(PREF_PARENT_SEARCH, false),
            studioEnabled = prefs.getBoolean(PREF_FACET_STUDIO, false),
            performerEnabled = prefs.getBoolean(PREF_FACET_PERFORMER, false),
            tagEnabled = prefs.getBoolean(PREF_FACET_TAG, false),
            notifyUi = false
        )
    }

    @Synchronized
    private fun replaceProviders(
        bases: List<String>,
        homeName: String,
        prefix: String,
        routeGroups: List<SearchRouteGroup>,
        facetRoutes: List<FacetRoute>,
        homeOrder: List<String>,
        disabledSources: List<String>,
        parentSearch: Boolean,
        studioEnabled: Boolean,
        performerEnabled: Boolean,
        tagEnabled: Boolean,
        notifyUi: Boolean
    ) {
        if (registeredProviders.isNotEmpty()) {
            val old = registeredProviders.toSet()
            registeredProviders.clear()

            old.forEach { APIHolder.removePluginMapping(it) }
            APIHolder.allProviders.withLock {
                APIHolder.allProviders.removeAll { it in old }
            }
        }

        val disabledKeys = disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
        val activeSearchGroups = routeGroups.filter { homeSourceKey(it.sourceName) !in disabledKeys }
        val activeFacetRoutes = facetRoutes.filter { homeSourceKey(it.sourceName) !in disabledKeys }

        if (bases.isNotEmpty()) {
            val home = TPBOrderedHomeProvider(
                name = homeName,
                manifestBases = bases,
                searchGroups = activeSearchGroups,
                combinedSearchEnabled = parentSearch,
                homeOrder = homeOrder,
                disabledSources = disabledSources
            )
            registerMainAPI(home)
            registeredProviders += home

            activeSearchGroups.forEach { group ->
                if (group.routes.isNotEmpty()) {
                    val provider = TPBSearchProvider(
                        name = prefix + group.sourceName,
                        sourceName = group.sourceName,
                        routes = group.routes
                    )
                    registerMainAPI(provider)
                    registeredProviders += provider
                }
            }

            val enabledFacets = listOf(
                FacetKind.STUDIO to studioEnabled,
                FacetKind.PERFORMER to performerEnabled,
                FacetKind.TAG to tagEnabled
            )
            enabledFacets.forEach { (kind, enabled) ->
                if (!enabled) return@forEach
                val routes = activeFacetRoutes.filter { it.kind == kind }
                if (routes.isEmpty()) return@forEach
                val provider = TPBFacetProvider(
                    name = "$homeName • ${kind.label}",
                    kind = kind,
                    routes = routes
                )
                registerMainAPI(provider)
                registeredProviders += provider
            }
        }

        if (notifyUi) afterPluginsLoadedEvent.invoke(true)
    }

    private fun showSettings(context: Context) {
        val activity = context.findActivity() ?: run {
            Toast.makeText(context, "Unable to open TPBBridge settings from this screen.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = activity.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
        fun section(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(5))
        }
        fun label(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(2))
        }
        fun helper(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 13f
            alpha = 0.72f
            setPadding(0, 0, 0, dp(3))
        }
        fun warning(text: String): TextView = TextView(activity).apply {
            this.text = "⚠ $text"
            textSize = 13f
            alpha = 0.9f
            setPadding(0, dp(2), 0, dp(4))
        }
        fun toggle(text: String, checked: Boolean): Switch = Switch(activity).apply {
            this.text = text
            textSize = 16f
            isChecked = checked
            setPadding(0, dp(2), 0, dp(2))
        }
        fun compactSummary(manifestCount: Int, sourceCount: Int, disabledCount: Int): String {
            if (manifestCount == 0) return "Not configured"
            val manifests = if (manifestCount == 1) "manifest" else "manifests"
            val sources = if (sourceCount == 1) "source" else "sources"
            return buildString {
                append("$manifestCount $manifests • $sourceCount $sources")
                if (disabledCount > 0) append(" • $disabledCount off")
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(18))
        }

        val currentRoutes = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())
        val currentBases = parseManifestInput(prefs.getString(PREF_MANIFESTS, "").orEmpty())
        var availableSources = loadHomeNameList(prefs.getString(PREF_HOME_SOURCES, "").orEmpty())
        var workingHomeOrder = reconcileHomeOrder(
            loadHomeNameList(prefs.getString(PREF_HOME_ORDER, "").orEmpty()),
            availableSources
        )
        var workingDisabledSources = loadHomeNameList(
            prefs.getString(PREF_DISABLED_SOURCES, "").orEmpty()
        )
        var hasSavedConfiguration = currentBases.isNotEmpty()

        fun visibleDisabledCount(): Int = availableSources.count {
            isSourceDisabled(it, workingDisabledSources)
        }

        val summary = TextView(activity).apply {
            textSize = 13.5f
            alpha = 0.78f
            setPadding(0, 0, 0, dp(2))
            text = compactSummary(currentBases.size, currentRoutes.size, visibleDisabledCount())
        }
        root.addView(summary)

        root.addView(section("Setup"))
        root.addView(label("Manifest URL(s)"))
        root.addView(helper("One URL per line"))
        val manifestsEdit = EditText(activity).apply {
            minLines = 3
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(prefs.getString(PREF_MANIFESTS, "").orEmpty())
            hint = "https://…/manifest.json"
        }
        root.addView(
            manifestsEdit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(warning("Manifest URLs can contain private API keys. Keep them private."))

        root.addView(label("Home name"))
        root.addView(helper("Name for the combined Home provider"))
        val homeNameEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(prefs.getString(PREF_HOME_NAME, DEFAULT_HOME_NAME) ?: DEFAULT_HOME_NAME)
            hint = DEFAULT_HOME_NAME
        }
        root.addView(homeNameEdit)

        val manageSources = Button(activity).apply {
            text = "Manage sources"
            isEnabled = availableSources.isNotEmpty()
        }
        root.addView(manageSources)
        val manageHint = helper(
            if (availableSources.isEmpty()) "Save + refresh once to discover sources"
            else "Enable, disable, and arrange Home rows"
        )
        root.addView(manageHint)

        root.addView(label("Search prefix (optional)"))
        root.addView(helper("Leave blank for clean source names"))
        val prefixEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX) ?: DEFAULT_SEARCH_PREFIX)
            hint = "TPB • "
        }
        root.addView(prefixEdit)

        root.addView(section("Search"))
        val parentSearchSwitch = toggle(
            "Search through Home name",
            prefs.getBoolean(PREF_PARENT_SEARCH, false)
        )
        root.addView(parentSearchSwitch)
        root.addView(helper("Combine results from all enabled sources"))
        root.addView(warning("Selecting Home + individual sources together can show duplicate results."))

        root.addView(section("Extra filters"))
        root.addView(helper("Search only • never shown as Home rows"))
        val studioSwitch = toggle("Studio", prefs.getBoolean(PREF_FACET_STUDIO, false))
        val performerSwitch = toggle("Performer", prefs.getBoolean(PREF_FACET_PERFORMER, false))
        val tagSwitch = toggle("Tag", prefs.getBoolean(PREF_FACET_TAG, false))
        root.addView(studioSwitch)
        root.addView(performerSwitch)
        root.addView(tagSwitch)
        root.addView(helper("Requires matching filters enabled in TPB."))

        val status = TextView(activity).apply {
            textSize = 13.5f
            setPadding(0, dp(10), 0, dp(6))
            text = ""
        }
        root.addView(status)

        manageSources.setOnClickListener {
            showHomeSourceManagerDialog(
                activity = activity,
                discoveredSources = availableSources,
                initialFullOrder = workingHomeOrder,
                initialDisabledSources = workingDisabledSources
            ) { result ->
                workingHomeOrder = result.fullOrder
                workingDisabledSources = result.disabledSources
                summary.text = compactSummary(currentBases.size, currentRoutes.size, visibleDisabledCount())
                status.text = "Source settings changed • Save + refresh to apply"
            }
        }

        val apply = Button(activity).apply { text = "Save + refresh" }
        root.addView(apply)
        root.addView(helper("No app restart needed"))

        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("TPBBridge")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        apply.setOnClickListener {
            val raw = manifestsEdit.text.toString().trim()
            val bases = parseManifestInput(raw)
            val homeName = homeNameEdit.text.toString().trim().ifBlank { DEFAULT_HOME_NAME }
            val prefix = prefixEdit.text.toString()
            val parentSearch = parentSearchSwitch.isChecked
            val studioEnabled = studioSwitch.isChecked
            val performerEnabled = performerSwitch.isChecked
            val tagEnabled = tagSwitch.isChecked

            if (raw.isNotBlank() && bases.isEmpty()) {
                status.text = "⚠ No valid manifest URL found."
                return@setOnClickListener
            }

            if (bases.isEmpty()) {
                fun clearNow() {
                    prefs.edit()
                        .putString(PREF_MANIFESTS, "")
                        .putString(PREF_HOME_NAME, homeName)
                        .putString(PREF_SEARCH_PREFIX, prefix)
                        .putBoolean(PREF_PARENT_SEARCH, parentSearch)
                        .putBoolean(PREF_FACET_STUDIO, studioEnabled)
                        .putBoolean(PREF_FACET_PERFORMER, performerEnabled)
                        .putBoolean(PREF_FACET_TAG, tagEnabled)
                        .remove(PREF_ROUTES)
                        .remove(PREF_FACET_ROUTES)
                        .remove(PREF_HOME_SOURCES)
                        .remove(PREF_HOME_ORDER)
                        .remove(PREF_DISABLED_SOURCES)
                        .apply()
                    invalidateManifestCache()
                    replaceProviders(
                        emptyList(), homeName, prefix, emptyList(), emptyList(), emptyList(), emptyList(),
                        parentSearch, studioEnabled, performerEnabled, tagEnabled,
                        notifyUi = true
                    )
                    availableSources = emptyList()
                    workingHomeOrder = emptyList()
                    workingDisabledSources = emptyList()
                    manageSources.isEnabled = false
                    manageHint.text = "Save + refresh once to discover sources"
                    hasSavedConfiguration = false
                    summary.text = "Not configured"
                    status.text = "Cleared."
                    Toast.makeText(activity, "TPBBridge cleared.", Toast.LENGTH_SHORT).show()
                }

                if (hasSavedConfiguration) {
                    AlertDialog.Builder(activity)
                        .setTitle("Clear TPBBridge?")
                        .setMessage("This removes the saved manifests and TPBBridge providers.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear") { _, _ -> clearNow() }
                        .show()
                } else {
                    clearNow()
                }
                return@setOnClickListener
            }

            apply.isEnabled = false
            status.text = "Refreshing…"

            Thread {
                try {
                    val discovered = discoverBridgeRoutes(bases)
                    val discoveredHomeSources = try {
                        discoverHomeSources(bases)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val discoveredSources = managedSourceList(
                        discoveredHomeSources,
                        discovered.searchGroups,
                        discovered.facetRoutes
                    )
                    val nextHomeOrder = reconcileHomeOrder(workingHomeOrder, discoveredSources)
                    val nextDisabledSources = workingDisabledSources
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy(::homeSourceKey)

                    prefs.edit()
                        .putString(PREF_MANIFESTS, raw)
                        .putString(PREF_HOME_NAME, homeName)
                        .putString(PREF_SEARCH_PREFIX, prefix)
                        .putString(PREF_ROUTES, saveRouteGroups(discovered.searchGroups))
                        .putString(PREF_FACET_ROUTES, saveFacetRoutes(discovered.facetRoutes))
                        .putString(PREF_HOME_SOURCES, saveHomeNameList(discoveredSources))
                        .putString(PREF_HOME_ORDER, saveHomeNameList(nextHomeOrder))
                        .putString(PREF_DISABLED_SOURCES, saveHomeNameList(nextDisabledSources))
                        .putBoolean(PREF_PARENT_SEARCH, parentSearch)
                        .putBoolean(PREF_FACET_STUDIO, studioEnabled)
                        .putBoolean(PREF_FACET_PERFORMER, performerEnabled)
                        .putBoolean(PREF_FACET_TAG, tagEnabled)
                        .apply()

                    invalidateManifestCache()
                    activity.runOnUiThread {
                        replaceProviders(
                            bases = bases,
                            homeName = homeName,
                            prefix = prefix,
                            routeGroups = discovered.searchGroups,
                            facetRoutes = discovered.facetRoutes,
                            homeOrder = nextHomeOrder,
                            disabledSources = nextDisabledSources,
                            parentSearch = parentSearch,
                            studioEnabled = studioEnabled,
                            performerEnabled = performerEnabled,
                            tagEnabled = tagEnabled,
                            notifyUi = true
                        )
                        apply.isEnabled = true
                        availableSources = discoveredSources
                        workingHomeOrder = nextHomeOrder
                        workingDisabledSources = nextDisabledSources
                        manageSources.isEnabled = availableSources.isNotEmpty()
                        manageHint.text = if (availableSources.isEmpty()) {
                            "Save + refresh once to discover sources"
                        } else {
                            "Enable, disable, and arrange Home rows"
                        }
                        hasSavedConfiguration = true

                        val disabledKeys = nextDisabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
                        val enabledSearchCount = discovered.searchGroups.count {
                            homeSourceKey(it.sourceName) !in disabledKeys
                        }
                        val enabledDisabledCount = discoveredSources.count {
                            homeSourceKey(it) in disabledKeys
                        }
                        summary.text = compactSummary(bases.size, enabledSearchCount, enabledDisabledCount)

                        fun countEnabled(kind: FacetKind): Int = discovered.facetRoutes
                            .filter { it.kind == kind && homeSourceKey(it.sourceName) !in disabledKeys }
                            .map { it.sourceName.lowercase() }
                            .distinct()
                            .size

                        val warnings = mutableListOf<String>()
                        if (discoveredSources.isEmpty()) {
                            warnings += "No sources found; enable Recent or Search in TPB."
                        }
                        if (discovered.searchGroups.isEmpty()) {
                            warnings += "No Search catalogs found; enable Search in TPB."
                        }
                        if (studioEnabled && countEnabled(FacetKind.STUDIO) == 0) {
                            warnings += "Studio is ON but unavailable for enabled sources."
                        }
                        if (performerEnabled && countEnabled(FacetKind.PERFORMER) == 0) {
                            warnings += "Performer is ON but unavailable for enabled sources."
                        }
                        if (tagEnabled && countEnabled(FacetKind.TAG) == 0) {
                            warnings += "Tag is ON but unavailable for enabled sources."
                        }

                        status.text = buildString {
                            append("Saved • $enabledSearchCount active sources")
                            if (enabledDisabledCount > 0) append(" • $enabledDisabledCount off")
                            if (warnings.isNotEmpty()) {
                                append("\n⚠ ")
                                append(warnings.joinToString(" "))
                            }
                        }
                        Toast.makeText(activity, "TPBBridge refreshed.", Toast.LENGTH_SHORT).show()
                    }
                } catch (t: Throwable) {
                    activity.runOnUiThread {
                        apply.isEnabled = true
                        status.text = "⚠ Could not refresh: ${t.message ?: t.javaClass.simpleName}. Existing providers were kept."
                    }
                }
            }.start()
        }

        dialog.show()
    }
}
