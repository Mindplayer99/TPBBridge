/*
 * TPBBridge - CloudStream plugin
 *
 * Reads configured Stremio/TPB manifests, keeps normal Recent catalogs together
 * in one Home provider, and exposes each Search catalog as its own CloudStream
 * provider. Optional v6 switches add parent/all-source Search and TPB's required
 * Studio/Performer/Tag filter catalogs without polluting Home rows.
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

        replaceProviders(
            bases = bases,
            homeName = homeName,
            prefix = prefix,
            routeGroups = routeGroups,
            facetRoutes = facetRoutes,
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

        if (bases.isNotEmpty()) {
            val home = TPBUnifiedHomeProvider(
                name = homeName,
                manifestBases = bases,
                searchGroups = routeGroups,
                combinedSearchEnabled = parentSearch
            )
            registerMainAPI(home)
            registeredProviders += home

            routeGroups.forEach { group ->
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
                val routes = facetRoutes.filter { it.kind == kind }
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
        fun label(text: String): TextView = TextView(activity).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        fun helper(text: String): TextView = TextView(activity).apply {
            this.text = text
            alpha = 0.78f
            setPadding(0, 0, 0, dp(4))
        }
        fun toggle(text: String, checked: Boolean): Switch = Switch(activity).apply {
            this.text = text
            isChecked = checked
            setPadding(0, dp(3), 0, dp(3))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }

        val currentRoutes = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())
        val currentFacets = loadFacetRoutes(prefs.getString(PREF_FACET_ROUTES, "").orEmpty())
        val currentBases = parseManifestInput(prefs.getString(PREF_MANIFESTS, "").orEmpty())

        val summary = TextView(activity).apply {
            setPadding(0, 0, 0, dp(8))
            text = advancedConfigurationSummary(currentBases.size, currentRoutes, currentFacets)
        }
        root.addView(summary)

        root.addView(label("Manifest URL(s)"))
        root.addView(helper("Paste TPB/Stremio manifest URLs, one per line. They stay in CloudStream app storage and are never written to GitHub."))
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

        root.addView(label("Home name"))
        val homeNameEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(prefs.getString(PREF_HOME_NAME, DEFAULT_HOME_NAME) ?: DEFAULT_HOME_NAME)
            hint = DEFAULT_HOME_NAME
        }
        root.addView(homeNameEdit)

        root.addView(label("Search prefix (optional)"))
        root.addView(helper("Leave blank for clean per-source names such as Hotleak and Notfans."))
        val prefixEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX) ?: DEFAULT_SEARCH_PREFIX)
            hint = "Example: TPB • "
        }
        root.addView(prefixEdit)

        root.addView(label("Search behavior"))
        val parentSearchSwitch = toggle(
            "Search all sources through the Home name",
            prefs.getBoolean(PREF_PARENT_SEARCH, false)
        )
        root.addView(parentSearchSwitch)
        root.addView(helper("OFF keeps the Home name browse-only. ON lets the Home provider itself search every discovered source Search catalog at once. Per-source Search providers remain available either way."))

        root.addView(label("Optional TPB filter search"))
        root.addView(helper("These never add Studio/Performer/Tag rows to Home. When enabled, they appear only as search providers such as ‘Valley • Studio’. TPB must expose the matching filter catalogs in the manifest."))
        val studioSwitch = toggle("Studio", prefs.getBoolean(PREF_FACET_STUDIO, false))
        val performerSwitch = toggle("Performer", prefs.getBoolean(PREF_FACET_PERFORMER, false))
        val tagSwitch = toggle("Tag", prefs.getBoolean(PREF_FACET_TAG, false))
        root.addView(studioSwitch)
        root.addView(performerSwitch)
        root.addView(tagSwitch)
        root.addView(helper("Filter search matches what you type against TPB's advertised option list. Exact matches are preferred; narrow partial matches are allowed. All three are OFF by default."))

        val status = TextView(activity).apply {
            setPadding(0, dp(12), 0, dp(8))
            text = "Ready."
        }
        root.addView(status)

        val apply = Button(activity).apply { text = "Save + apply now" }
        root.addView(apply)
        root.addView(helper("Re-discovers Search and optional filter catalogs, then safely refreshes only TPBBridge providers. No app restart is needed."))

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
                status.text = "No valid HTTP/HTTPS/Stremio manifest URL was found."
                return@setOnClickListener
            }

            if (bases.isEmpty()) {
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
                    .apply()
                invalidateManifestCache()
                replaceProviders(
                    emptyList(), homeName, prefix, emptyList(), emptyList(),
                    parentSearch, studioEnabled, performerEnabled, tagEnabled,
                    notifyUi = true
                )
                summary.text = advancedConfigurationSummary(0, emptyList(), emptyList())
                status.text = "Cleared and applied."
                Toast.makeText(activity, "TPBBridge configuration cleared.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            apply.isEnabled = false
            status.text = "Reading manifest(s) and discovering Search/filter catalogs…"

            Thread {
                try {
                    val discovered = discoverBridgeRoutes(bases)

                    prefs.edit()
                        .putString(PREF_MANIFESTS, raw)
                        .putString(PREF_HOME_NAME, homeName)
                        .putString(PREF_SEARCH_PREFIX, prefix)
                        .putString(PREF_ROUTES, saveRouteGroups(discovered.searchGroups))
                        .putString(PREF_FACET_ROUTES, saveFacetRoutes(discovered.facetRoutes))
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
                            parentSearch = parentSearch,
                            studioEnabled = studioEnabled,
                            performerEnabled = performerEnabled,
                            tagEnabled = tagEnabled,
                            notifyUi = true
                        )
                        apply.isEnabled = true
                        summary.text = advancedConfigurationSummary(
                            bases.size,
                            discovered.searchGroups,
                            discovered.facetRoutes
                        )

                        fun count(kind: FacetKind): Int = discovered.facetRoutes
                            .filter { it.kind == kind }
                            .map { it.sourceName.lowercase() }
                            .distinct()
                            .size

                        status.text = buildString {
                            append("Applied now: ${discovered.searchGroups.size} source Search provider(s).")
                            append(" Filters available — Studio ${count(FacetKind.STUDIO)}, Performer ${count(FacetKind.PERFORMER)}, Tag ${count(FacetKind.TAG)}.")
                            if (discovered.searchGroups.isEmpty()) {
                                append(" Home still works; enable Search in TPB for sources you want searchable.")
                            }
                        }
                        Toast.makeText(
                            activity,
                            "TPBBridge applied ${discovered.searchGroups.size} Search source(s).",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (t: Throwable) {
                    activity.runOnUiThread {
                        apply.isEnabled = true
                        status.text = "Could not read manifest: ${t.message ?: t.javaClass.simpleName}. Existing working providers were left unchanged."
                    }
                }
            }.start()
        }

        dialog.show()
    }
}
