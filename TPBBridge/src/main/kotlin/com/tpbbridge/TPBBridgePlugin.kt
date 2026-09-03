/*
 * TPBBridge - CloudStream plugin
 *
 * v11 groups one or more manifest URLs into independent profiles. Each profile
 * owns its Home name, Search prefix, source order/state and optional searches.
 * Stream/debrid/metadata handling remains shared and unchanged.
 *
 * GPL-3.0-or-later.
 */
package com.tpbbridge

import android.app.Activity
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
import com.lagradost.cloudstream3.utils.DataStoreHelper

// v10 keys are retained for automatic migration in ProfileConfig.kt.
internal const val PREF_FILE = "TPBBridge"
internal const val PREF_MANIFESTS = "manifest_urls"
internal const val PREF_HOME_NAME = "home_name"
internal const val PREF_SEARCH_PREFIX = "search_prefix"
internal const val PREF_ROUTES = "search_routes"
internal const val DEFAULT_HOME_NAME = "TPB Tubes"
internal const val DEFAULT_SEARCH_PREFIX = ""
internal const val SAFE_MAIN_URL = "https://tpbbridge.invalid"
internal const val MANIFEST_CACHE_MS = 5 * 60 * 1000L
internal const val HOME_CATALOG_CACHE_MS = 2 * 60 * 1000L
internal const val SEARCH_PAGE_SIZE = 20

@CloudstreamPlugin
class TPBBridgePlugin : Plugin() {
    private val registeredProviders = mutableListOf<MainAPI>()

    override fun load(context: Context) {
        openSettings = { settingsContext -> showSettings(settingsContext) }
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        replaceProviders(loadProfilesOrMigrate(prefs), notifyUi = false)
    }

    @Synchronized
    private fun replaceProviders(profiles: List<BridgeProfile>, notifyUi: Boolean) {
        if (registeredProviders.isNotEmpty()) {
            val old = registeredProviders.toSet()
            registeredProviders.clear()
            old.forEach { APIHolder.removePluginMapping(it) }
            APIHolder.allProviders.withLock {
                APIHolder.allProviders.removeAll { it in old }
            }
        }

        profiles.forEach { profile ->
            val bases = profile.bases
            if (bases.isEmpty()) return@forEach

            val searches = activeSearchGroups(profile)
            val facets = activeFacetRoutes(profile)

            registerTracked(
                TPBOrderedHomeProvider(
                    name = profile.homeName,
                    manifestBases = bases,
                    searchGroups = searches,
                    combinedSearchEnabled = profile.parentSearch,
                    homeOrder = profile.homeOrder,
                    disabledSources = profile.disabledSources,
                    separateLiveCategories = profile.separateLiveCategories
                )
            )

            searches.forEach { group ->
                if (group.routes.isNotEmpty()) {
                    registerTracked(
                        TPBSearchProvider(
                            name = profile.searchPrefix + group.sourceName,
                            sourceName = group.sourceName,
                            routes = group.routes
                        )
                    )
                }
            }

            listOf(
                FacetKind.STUDIO to profile.studioEnabled,
                FacetKind.PERFORMER to profile.performerEnabled,
                FacetKind.TAG to profile.tagEnabled
            ).forEach { (kind, enabled) ->
                if (!enabled) return@forEach
                val routes = facets.filter { it.kind == kind }
                if (routes.isNotEmpty()) {
                    registerTracked(
                        TPBFacetProvider(
                            name = "${profile.homeName} • ${kind.label}",
                            kind = kind,
                            routes = routes
                        )
                    )
                }
            }
        }

        if (notifyUi) afterPluginsLoadedEvent.invoke(true)
    }

    private fun registerTracked(provider: MainAPI) {
        registerMainAPI(provider)
        registeredProviders += provider
    }

    private fun removeCloudStreamSelections(names: Collection<String>) {
        if (names.isEmpty()) return
        val keys = names.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        DataStoreHelper.searchPreferenceProviders =
            DataStoreHelper.searchPreferenceProviders.filterNot { it in keys }
        if (DataStoreHelper.currentHomePage in keys) {
            DataStoreHelper.currentHomePage = null
        }
    }

    private fun showSettings(context: Context) {
        val activity = context.findActivity() ?: run {
            Toast.makeText(context, "Unable to open TPBBridge settings from this screen.", Toast.LENGTH_LONG).show()
            return
        }
        showProfileManager(activity)
    }

    private fun showProfileManager(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        var profiles = loadProfilesOrMigrate(prefs)
        fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }
        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("TPBBridge")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        fun header(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(5))
        }
        fun helper(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            alpha = 0.72f
            setPadding(0, 0, 0, dp(5))
        }

        fun applyProfiles(updated: List<BridgeProfile>) {
            profiles = updated
            replaceProviders(updated, notifyUi = true)
        }

        fun render() {
            root.removeAllViews()
            root.addView(helper(
                if (profiles.isEmpty()) "No profiles configured"
                else "${profiles.size} profile${if (profiles.size == 1) "" else "s"} • each profile is independent"
            ))
            root.addView(header("Profiles"))
            root.addView(helper("A profile can contain one or more split manifest URLs."))

            profiles.forEach { profile ->
                val disabledKeys = profile.disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
                val activeCount = profile.homeSources.count { homeSourceKey(it) !in disabledKeys }
                val manifests = profile.bases.size
                val button = Button(activity).apply {
                    isAllCaps = false
                    text = buildString {
                        append(profile.homeName)
                        append("\n")
                        append(manifests).append(if (manifests == 1) " manifest" else " manifests")
                        append(" • ").append(activeCount).append(" active sources")
                    }
                    setOnClickListener {
                        showProfileEditor(activity, prefs, profile, profiles) { updated ->
                            applyProfiles(updated)
                            render()
                        }
                    }
                }
                root.addView(button)
            }

            root.addView(Button(activity).apply {
                text = "+ Add profile"
                isAllCaps = false
                setOnClickListener {
                    showProfileEditor(activity, prefs, null, profiles) { updated ->
                        applyProfiles(updated)
                        render()
                    }
                }
            })

            root.addView(header("Remove"))
            root.addView(Button(activity).apply {
                text = "Delete all TPBBridge data"
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Delete all TPBBridge data?")
                        .setMessage(
                            "This permanently erases every TPBBridge profile, manifest URL, source order, " +
                                "disabled-source state and TPBBridge search/filter setting.\n\n" +
                                "It does not delete CloudStream history/bookmarks, player settings, debrid settings, " +
                                "or other extensions."
                        )
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete all data") { _, _ ->
                            val known = linkedSetOf<String>().apply {
                                addAll(registeredProviders.map { it.name })
                                profiles.forEach { addAll(providerNamesForProfile(it)) }
                            }
                            val erased = prefs.edit().clear().commit()
                            if (!erased) {
                                Toast.makeText(activity, "TPBBridge data was not erased.", Toast.LENGTH_LONG).show()
                            } else {
                                removeCloudStreamSelections(known)
                                invalidateManifestCache()
                                applyProfiles(emptyList())
                                render()
                                Toast.makeText(
                                    activity,
                                    "TPBBridge data deleted. You can keep it empty or uninstall it now.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .show()
                }
            })
            root.addView(helper("Use this for a complete wipe before uninstalling."))
        }

        render()
        dialog.show()
    }

    private fun showProfileEditor(
        activity: Activity,
        prefs: android.content.SharedPreferences,
        current: BridgeProfile?,
        allProfiles: List<BridgeProfile>,
        onApplied: (List<BridgeProfile>) -> Unit
    ) {
        fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
        fun section(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        }
        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(2))
        }
        fun helper(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            alpha = 0.72f
            setPadding(0, 0, 0, dp(4))
        }
        fun toggle(text: String, checked: Boolean) = Switch(activity).apply {
            this.text = text
            textSize = 16f
            isChecked = checked
        }

        lateinit var editorDialog: AlertDialog
        val seed = current ?: newBridgeProfile(allProfiles.size + 1)
        var availableSources = seed.homeSources
        var workingOrder = reconcileHomeOrder(seed.homeOrder, availableSources)
        var workingDisabled = seed.disabledSources

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(18))
        }

        root.addView(section(if (current == null) "New profile" else seed.homeName))
        root.addView(label("Manifest URL(s)"))
        root.addView(helper("One URL per line • split manifests can stay together in this profile"))
        val manifestsEdit = EditText(activity).apply {
            minLines = 3
            maxLines = 7
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(seed.manifestInput)
            hint = "https://…/manifest.json"
        }
        root.addView(manifestsEdit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(helper("Manifest URLs can contain private API keys. Keep them private."))

        root.addView(label("Home name"))
        val homeEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(seed.homeName)
            hint = DEFAULT_HOME_NAME
        }
        root.addView(homeEdit)
        root.addView(helper("This profile's Home provider name"))

        root.addView(label("Search prefix (optional)"))
        val prefixEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(seed.searchPrefix)
            hint = "TPB • "
        }
        root.addView(prefixEdit)
        root.addView(helper("Applied only to this profile's individual source searches"))

        val manageSources = Button(activity).apply {
            text = "Manage sources"
            isAllCaps = false
            isEnabled = availableSources.isNotEmpty()
        }
        root.addView(manageSources)
        val manageHint = helper(
            if (availableSources.isEmpty()) "Save + refresh once to discover this profile's sources"
            else "Enable, disable, and arrange this profile's sources"
        )
        root.addView(manageHint)

        root.addView(section("Live catalogs"))
        val separateLiveCategories = toggle(
            "Separate live category rows",
            seed.separateLiveCategories
        )
        root.addView(separateLiveCategories)
        root.addView(helper(
            "Off: one Stripchat row and one Chaturbate row. On: enabled region/category catalogs get their own rows. " +
                "Ignored safely when this profile has no live catalogs."
        ))

        root.addView(section("Search"))
        val parentSearch = toggle("Search through Home name", seed.parentSearch)
        root.addView(parentSearch)
        root.addView(helper("Combines this profile's enabled sources only"))

        root.addView(section("Extra filters"))
        root.addView(helper("Search only • profile-specific • never shown as Home rows"))
        val studio = toggle("Studio", seed.studioEnabled)
        val performer = toggle("Performer", seed.performerEnabled)
        val tag = toggle("Tag", seed.tagEnabled)
        root.addView(studio)
        root.addView(performer)
        root.addView(tag)
        root.addView(helper("Requires matching filters enabled in the manifest(s)."))

        val status = TextView(activity).apply {
            textSize = 13.5f
            setPadding(0, dp(10), 0, dp(5))
        }
        root.addView(status)

        manageSources.setOnClickListener {
            showHomeSourceManagerDialog(
                activity = activity,
                discoveredSources = availableSources,
                initialFullOrder = workingOrder,
                initialDisabledSources = workingDisabled
            ) { result ->
                workingOrder = result.fullOrder
                workingDisabled = result.disabledSources
                status.text = "Source settings changed • Save + refresh to apply"
            }
        }

        val save = Button(activity).apply {
            text = "Save + refresh"
            isAllCaps = false
        }
        root.addView(save)
        root.addView(helper("Only this profile is changed; existing providers stay active if refresh fails."))

        if (current != null) {
            root.addView(section("Remove profile"))
            root.addView(Button(activity).apply {
                text = "Remove ${current.homeName}"
                isAllCaps = false
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setTitle("Remove profile?")
                        .setMessage("This removes only ‘${current.homeName}’. Other TPBBridge profiles are unchanged.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            val updated = allProfiles.filterNot { it.id == current.id }
                            if (!saveProfiles(prefs, updated)) {
                                Toast.makeText(activity, "Could not save profile removal.", Toast.LENGTH_LONG).show()
                            } else {
                                removeCloudStreamSelections(providerNamesForProfile(current))
                                invalidateManifestCache()
                                onApplied(updated)
                                Toast.makeText(activity, "Profile removed.", Toast.LENGTH_SHORT).show()
                                editorDialog.dismiss()
                            }
                        }
                        .show()
                }
            })
        }

        val scroll = ScrollView(activity).apply { addView(root) }
        editorDialog = AlertDialog.Builder(activity)
            .setTitle(if (current == null) "Add profile" else "Configure profile")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        save.setOnClickListener {
            val raw = manifestsEdit.text.toString().trim()
            val bases = parseManifestInput(raw)
            val homeName = homeEdit.text.toString().trim().ifBlank { DEFAULT_HOME_NAME }
            val prefix = prefixEdit.text.toString()
            // Snapshot all UI-owned state before leaving the main thread.
            val separateLiveCategoriesEnabled = separateLiveCategories.isChecked
            val parentSearchEnabled = parentSearch.isChecked
            val studioEnabled = studio.isChecked
            val performerEnabled = performer.isChecked
            val tagEnabled = tag.isChecked
            val orderSnapshot = workingOrder.toList()
            val disabledSnapshot = workingDisabled.toList()

            if (raw.isBlank() || bases.isEmpty()) {
                status.text = "⚠ Add at least one valid manifest URL."
                return@setOnClickListener
            }

            save.isEnabled = false
            status.text = "Refreshing this profile…"

            Thread {
                try {
                    // A manual refresh must start from one fresh snapshot. The
                    // discovery helpers then share it with each other and Home.
                    invalidateManifestCache()
                    val discovered = discoverBridgeRoutes(bases)
                    val discoveredHome = try {
                        discoverHomeSources(bases, separateLiveCategoriesEnabled)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val discoveredSources = managedSourceList(
                        discoveredHome,
                        discovered.searchGroups,
                        discovered.facetRoutes
                    )
                    val nextOrder = reconcileHomeOrder(orderSnapshot, discoveredSources)
                    val nextDisabled = disabledSnapshot
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy(::homeSourceKey)

                    val candidate = BridgeProfile(
                        id = seed.id,
                        manifestInput = raw,
                        homeName = homeName,
                        searchPrefix = prefix,
                        searchGroups = discovered.searchGroups,
                        facetRoutes = discovered.facetRoutes,
                        homeSources = discoveredSources,
                        homeOrder = nextOrder,
                        disabledSources = nextDisabled,
                        separateLiveCategories = separateLiveCategoriesEnabled,
                        parentSearch = parentSearchEnabled,
                        studioEnabled = studioEnabled,
                        performerEnabled = performerEnabled,
                        tagEnabled = tagEnabled
                    )

                    val updated = if (current == null) {
                        allProfiles + candidate
                    } else {
                        allProfiles.map { if (it.id == current.id) candidate else it }
                    }
                    val collision = validateProfileProviderNames(updated)
                    if (collision != null) {
                        activity.runOnUiThread {
                            save.isEnabled = true
                            status.text = "⚠ $collision"
                        }
                        return@Thread
                    }

                    if (!saveProfiles(prefs, updated)) {
                        activity.runOnUiThread {
                            save.isEnabled = true
                            status.text = "⚠ Could not save profile data. Existing providers were kept."
                        }
                        return@Thread
                    }

                    activity.runOnUiThread {
                        val oldNames = current?.let(::providerNamesForProfile).orEmpty()
                        val newNames = providerNamesForProfile(candidate)
                        removeCloudStreamSelections(oldNames - newNames)
                        onApplied(updated)
                        Toast.makeText(activity, "${candidate.homeName} refreshed.", Toast.LENGTH_SHORT).show()
                        editorDialog.dismiss()
                    }
                } catch (t: Throwable) {
                    activity.runOnUiThread {
                        save.isEnabled = true
                        status.text = "⚠ Could not refresh: ${t.message ?: t.javaClass.simpleName}. Existing providers were kept."
                    }
                }
            }.start()
        }

        editorDialog.show()
    }
}
