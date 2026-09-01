/*
 * TPBBridge - CloudStream plugin
 *
 * Reads configured Stremio/TPB manifests, keeps normal Recent catalogs together
 * in one Home provider, and exposes each Search catalog as its own CloudStream
 * provider. Optional switches add parent/all-source Search and TPB's required
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
        fun compactSummary(manifestCount: Int, searchCount: Int): String {
            if (manifestCount == 0) return "Not configured"
            val manifests = if (manifestCount == 1) "manifest" else "manifests"
            val sources = if (searchCount == 1) "source" else "sources"
            return "$manifestCount $manifests • $searchCount $sources"
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(18))
        }

        val currentRoutes = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())
        val currentBases = parseManifestInput(prefs.getString(PREF_MANIFESTS, "").orEmpty())
        var hasSavedConfiguration = currentBases.isNotEmpty()

        val summary = TextView(activity).apply {
            textSize = 13.5f
            alpha = 0.78f
            setPadding(0, 0, 0, dp(2))
            text = compactSummary(currentBases.size, currentRoutes.size)
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
        root.addView(helper("Combine results from all discovered sources"))
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
                        .apply()
                    invalidateManifestCache()
                    replaceProviders(
                        emptyList(), homeName, prefix, emptyList(), emptyList(),
                        parentSearch, studioEnabled, performerEnabled, tagEnabled,
                        notifyUi = true
                    )
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
                        hasSavedConfiguration = true
                        summary.text = compactSummary(bases.size, discovered.searchGroups.size)

                        fun count(kind: FacetKind): Int = discovered.facetRoutes
                            .filter { it.kind == kind }
                            .map { it.sourceName.lowercase() }
                            .distinct()
                            .size

                        val warnings = mutableListOf<String>()
                        if (discovered.searchGroups.isEmpty()) {
                            warnings += "No Search catalogs found; enable Search in TPB."
                        }
                        if (studioEnabled && count(FacetKind.STUDIO) == 0) {
                            warnings += "Studio is ON but unavailable in this manifest."
                        }
                        if (performerEnabled && count(FacetKind.PERFORMER) == 0) {
                            warnings += "Performer is ON but unavailable in this manifest."
                        }
                        if (tagEnabled && count(FacetKind.TAG) == 0) {
                            warnings += "Tag is ON but unavailable in this manifest."
                        }

                        status.text = buildString {
                            append("Saved • ${discovered.searchGroups.size} sources")
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