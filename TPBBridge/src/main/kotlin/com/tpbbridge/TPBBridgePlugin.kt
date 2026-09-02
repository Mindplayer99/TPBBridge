/*
 * TPBBridge - CloudStream plugin
 *
 * v11 groups one or more Stremio/TPB manifest URLs into independent profiles.
 * Every profile owns its Home name, Search prefix, source enable/order state,
 * combined Search toggle and optional Studio/Performer/Tag filters. Playback,
 * metadata, debrid, P2P, quality and header handling remain in the shared bridge
 * core and are intentionally not profile-specific.
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
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.util.Locale

// Legacy v10 keys are retained only so ProfileConfig.kt can migrate them once.
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
        replaceProviders(loadProfilesWithMigration(prefs), notifyUi = false)
    }

    /**
     * Replace only providers created by this plugin instance. Search/Home choices
     * belonging to other CloudStream extensions are preserved. If a TPBBridge
     * provider keeps the same visible name across a refresh, its selection stays.
     */
    @Synchronized
    private fun replaceProviders(profiles: List<BridgeProfile>, notifyUi: Boolean) {
        val oldProviders = registeredProviders.toList()
        val oldNames = oldProviders.mapTo(linkedSetOf()) { it.name }

        registeredProviders.clear()
        if (oldProviders.isNotEmpty()) {
            val old = oldProviders.toSet()
            old.forEach { APIHolder.removePluginMapping(it) }
            APIHolder.allProviders.withLock {
                APIHolder.allProviders.removeAll { it in old }
            }
        }

        val usedNames = mutableSetOf<String>()
        fun nameKey(value: String): String = value.trim().lowercase(Locale.ROOT)
        fun reserveName(preferred: String, profileLabel: String): String {
            val base = preferred.trim().ifBlank { "TPB Source" }
            if (usedNames.add(nameKey(base))) return base

            val scope = profileLabel.trim().ifBlank { "Profile" }
            var candidate = "$base • $scope"
            var index = 2
            while (!usedNames.add(nameKey(candidate))) {
                candidate = "$base • $scope $index"
                index++
            }
            return candidate
        }

        profiles.forEach { profile ->
            val bases = profile.bases
            if (bases.isEmpty()) return@forEach

            val profileLabel = profile.normalizedHomeName()
            val scope = safeNameToken(profile.id)
            val disabledKeys = profile.disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
            val activeSearchGroups = profile.routeGroups.filter {
                homeSourceKey(it.sourceName) !in disabledKeys
            }
            val activeFacetRoutes = profile.facetRoutes.filter {
                homeSourceKey(it.sourceName) !in disabledKeys
            }

            val homeProviderName = reserveName(profileLabel, profileLabel)
            val home = TPBOrderedHomeProvider(
                name = homeProviderName,
                manifestBases = bases,
                searchGroups = activeSearchGroups,
                combinedSearchEnabled = profile.parentSearch,
                homeOrder = profile.homeOrder,
                disabledSources = profile.disabledSources
            ).apply {
                mainUrl = "$SAFE_MAIN_URL/home/$scope"
            }
            registerMainAPI(home)
            registeredProviders += home

            activeSearchGroups.forEach { group ->
                if (group.routes.isEmpty()) return@forEach
                val providerName = reserveName(
                    profile.searchPrefix + group.sourceName,
                    profileLabel
                )
                val provider = TPBSearchProvider(
                    name = providerName,
                    sourceName = group.sourceName,
                    routes = group.routes
                ).apply {
                    mainUrl = "$SAFE_MAIN_URL/search/$scope/${safeNameToken(group.sourceName)}"
                }
                registerMainAPI(provider)
                registeredProviders += provider
            }

            val enabledFacets = listOf(
                FacetKind.STUDIO to profile.studioEnabled,
                FacetKind.PERFORMER to profile.performerEnabled,
                FacetKind.TAG to profile.tagEnabled
            )
            enabledFacets.forEach { (kind, enabled) ->
                if (!enabled) return@forEach
                val routes = activeFacetRoutes.filter { it.kind == kind }
                if (routes.isEmpty()) return@forEach

                val provider = TPBFacetProvider(
                    name = reserveName("$homeProviderName • ${kind.label}", profileLabel),
                    kind = kind,
                    routes = routes
                ).apply {
                    mainUrl = "$SAFE_MAIN_URL/filter/$scope/${kind.token}"
                }
                registerMainAPI(provider)
                registeredProviders += provider
            }
        }

        // Remove only stale TPBBridge names from CloudStream's saved selection.
        val newNames = registeredProviders.mapTo(linkedSetOf()) { it.name }
        if (oldNames.isNotEmpty()) {
            val selected = DataStoreHelper.searchPreferenceProviders
            val cleaned = selected.filter { it !in oldNames || it in newNames }.distinct()
            if (cleaned != selected) DataStoreHelper.searchPreferenceProviders = cleaned

            val currentHome = DataStoreHelper.currentHomePage
            if (currentHome != null && currentHome in oldNames && currentHome !in newNames) {
                DataStoreHelper.currentHomePage = null
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
        var profiles = loadProfilesWithMigration(prefs)

        fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
        fun section(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(5))
        }
        fun helper(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 13f
            alpha = 0.72f
            setPadding(0, 0, 0, dp(4))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }
        val summary = TextView(activity).apply {
            textSize = 13.5f
            alpha = 0.78f
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(summary)
        root.addView(section("Profiles"))
        root.addView(helper("Each profile has its own manifests, Home, Search and source settings."))

        val profileContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(profileContainer)

        fun updateSummary() {
            if (profiles.isEmpty()) {
                summary.text = "No profiles configured"
            } else {
                val manifestCount = profiles.sumOf { it.bases.size }
                summary.text = buildString {
                    append(profiles.size)
                    append(if (profiles.size == 1) " profile" else " profiles")
                    append(" • ")
                    append(manifestCount)
                    append(if (manifestCount == 1) " manifest" else " manifests")
                }
            }
        }

        fun nextDefaultHomeName(): String {
            if (profiles.isEmpty() && profileHomeNamesAreUnique(profiles, "", DEFAULT_HOME_NAME)) {
                return DEFAULT_HOME_NAME
            }
            var index = profiles.size + 1
            while (true) {
                val candidate = "TPB Profile $index"
                if (profileHomeNamesAreUnique(profiles, "", candidate)) return candidate
                index++
            }
        }

        fun renderProfiles() {
            profileContainer.removeAllViews()
            profiles.forEach { profile ->
                profileContainer.addView(Button(activity).apply {
                    text = "${profile.normalizedHomeName()}\n${profileSummary(profile)}"
                    textSize = 15f
                    setOnClickListener {
                        showProfileEditor(
                            activity = activity,
                            prefs = prefs,
                            currentProfiles = profiles,
                            initialProfile = profile,
                            isNew = false
                        ) { updated ->
                            profiles = updated
                            updateSummary()
                            renderProfiles()
                        }
                    }
                })
            }
            if (profiles.isEmpty()) {
                profileContainer.addView(helper("Add a profile to begin."))
            }
        }

        val addProfile = Button(activity).apply { text = "+ Add profile" }
        root.addView(addProfile)
        root.addView(helper("A profile can contain one or several split manifest URLs."))

        root.addView(section("Remove"))
        val wipe = Button(activity).apply { text = "Delete all TPBBridge data" }
        root.addView(wipe)
        root.addView(helper("Use before uninstalling • removes every TPBBridge profile and active provider"))

        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("TPBBridge")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        addProfile.setOnClickListener {
            val draft = BridgeProfile(
                id = newProfileId(),
                manifestInput = "",
                homeName = nextDefaultHomeName(),
                searchPrefix = DEFAULT_SEARCH_PREFIX
            )
            showProfileEditor(
                activity = activity,
                prefs = prefs,
                currentProfiles = profiles,
                initialProfile = draft,
                isNew = true
            ) { updated ->
                profiles = updated
                updateSummary()
                renderProfiles()
            }
        }

        wipe.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Delete all TPBBridge data?")
                .setMessage(
                    "This permanently erases every TPBBridge profile, manifest URL, source order, " +
                        "disabled-source state, search/filter setting and active TPBBridge provider.\n\n" +
                        "It does not delete CloudStream history/bookmarks, player settings, debrid settings, " +
                        "or other extensions.\n\nAfter this succeeds, you can uninstall TPBBridge."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete all data") { _, _ ->
                    val knownNames = allKnownProviderNames(profiles)
                    val erased = prefs.edit().clear().commit()
                    if (!erased) {
                        Toast.makeText(activity, "TPBBridge data was not erased.", Toast.LENGTH_LONG).show()
                    } else {
                        DataStoreHelper.searchPreferenceProviders =
                            DataStoreHelper.searchPreferenceProviders.filterNot { it in knownNames }
                        val currentHome = DataStoreHelper.currentHomePage
                        if (currentHome != null && currentHome in knownNames) {
                            DataStoreHelper.currentHomePage = null
                        }

                        invalidateManifestCache()
                        replaceProviders(emptyList(), notifyUi = true)
                        profiles = emptyList()
                        Toast.makeText(
                            activity,
                            "TPBBridge data deleted. You can uninstall the extension now.",
                            Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                    }
                }
                .show()
        }

        updateSummary()
        renderProfiles()
        dialog.show()
    }

    private fun showProfileEditor(
        activity: android.app.Activity,
        prefs: android.content.SharedPreferences,
        currentProfiles: List<BridgeProfile>,
        initialProfile: BridgeProfile,
        isNew: Boolean,
        onProfilesChanged: (List<BridgeProfile>) -> Unit
    ) {
        fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
        fun section(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(5))
        }
        fun label(text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(2))
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

        var availableSources = initialProfile.managedSources
        var workingHomeOrder = reconcileHomeOrder(initialProfile.homeOrder, availableSources)
        var workingDisabledSources = initialProfile.disabledSources

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(18))
        }

        root.addView(section("Setup"))
        root.addView(label("Manifest URL(s)"))
        root.addView(helper("One URL per line • URLs in this profile are combined together"))
        val manifestsEdit = EditText(activity).apply {
            minLines = 3
            maxLines = 7
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(initialProfile.manifestInput)
            hint = "https://…/manifest.json"
        }
        root.addView(
            manifestsEdit,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(warning("Manifest URLs can contain private API keys. Keep them private."))

        root.addView(label("Home name"))
        root.addView(helper("Only this profile uses this Home provider name"))
        val homeNameEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(initialProfile.normalizedHomeName())
            hint = DEFAULT_HOME_NAME
        }
        root.addView(homeNameEdit)

        val manageSources = Button(activity).apply {
            text = "Manage sources"
            isEnabled = availableSources.isNotEmpty()
        }
        root.addView(manageSources)
        val manageHint = helper(
            if (availableSources.isEmpty()) "Save + refresh once to discover this profile's sources"
            else "Enable, disable, and arrange only this profile's sources"
        )
        root.addView(manageHint)

        root.addView(label("Search prefix (optional)"))
        root.addView(helper("Applied only to this profile's individual Search providers"))
        val prefixEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(initialProfile.searchPrefix)
            hint = "TPB • "
        }
        root.addView(prefixEdit)

        root.addView(section("Search"))
        val parentSearchSwitch = toggle("Search through Home name", initialProfile.parentSearch)
        root.addView(parentSearchSwitch)
        root.addView(helper("Combine results from all enabled sources in this profile"))
        root.addView(warning("Selecting the Home provider + its individual sources together can show duplicates."))

        root.addView(section("Extra filters"))
        root.addView(helper("Search only • never shown as Home rows"))
        val studioSwitch = toggle("Studio", initialProfile.studioEnabled)
        val performerSwitch = toggle("Performer", initialProfile.performerEnabled)
        val tagSwitch = toggle("Tag", initialProfile.tagEnabled)
        root.addView(studioSwitch)
        root.addView(performerSwitch)
        root.addView(tagSwitch)
        root.addView(helper("Requires matching filters enabled in the manifests for this profile."))

        val status = TextView(activity).apply {
            textSize = 13.5f
            setPadding(0, dp(10), 0, dp(6))
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
                status.text = "Source settings changed • Save + refresh to apply"
            }
        }

        val save = Button(activity).apply { text = "Save + refresh" }
        root.addView(save)
        root.addView(helper("Refreshes this profile; other profiles keep their saved configuration"))

        val remove = if (!isNew) {
            root.addView(section("Remove profile"))
            Button(activity).apply { text = "Remove this profile" }.also { root.addView(it) }
        } else null

        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (isNew) "Add TPBBridge profile" else initialProfile.normalizedHomeName())
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        save.setOnClickListener {
            val raw = manifestsEdit.text.toString().trim()
            val bases = parseManifestInput(raw)
            val homeName = homeNameEdit.text.toString().trim().ifBlank { DEFAULT_HOME_NAME }
            val prefix = prefixEdit.text.toString()

            if (raw.isBlank() || bases.isEmpty()) {
                status.text = "⚠ Add at least one valid manifest URL."
                return@setOnClickListener
            }
            if (!profileHomeNamesAreUnique(currentProfiles, initialProfile.id, homeName)) {
                status.text = "⚠ Home name '$homeName' is already used by another TPBBridge profile."
                return@setOnClickListener
            }

            save.isEnabled = false
            remove?.isEnabled = false
            status.text = "Refreshing this profile…"

            Thread {
                try {
                    // Discovery is intentionally profile-local: other profiles are not fetched again.
                    val discovered = discoverBridgeRoutes(bases)
                    val homeSources = try {
                        discoverHomeSources(bases)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    val sources = managedSourceList(
                        homeSources,
                        discovered.searchGroups,
                        discovered.facetRoutes
                    )
                    val nextOrder = reconcileHomeOrder(workingHomeOrder, sources)
                    val nextDisabled = workingDisabledSources
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy(::homeSourceKey)

                    val updatedProfile = initialProfile.copy(
                        manifestInput = raw,
                        homeName = homeName,
                        searchPrefix = prefix,
                        routeGroups = discovered.searchGroups,
                        facetRoutes = discovered.facetRoutes,
                        managedSources = sources,
                        homeOrder = nextOrder,
                        disabledSources = nextDisabled,
                        parentSearch = parentSearchSwitch.isChecked,
                        studioEnabled = studioSwitch.isChecked,
                        performerEnabled = performerSwitch.isChecked,
                        tagEnabled = tagSwitch.isChecked
                    )
                    val nextProfiles = if (isNew) {
                        currentProfiles + updatedProfile
                    } else {
                        currentProfiles.map {
                            if (it.id == initialProfile.id) updatedProfile else it
                        }
                    }

                    if (!persistProfiles(prefs, nextProfiles)) {
                        throw IllegalStateException("Android could not save the profile")
                    }

                    invalidateManifestCache()
                    activity.runOnUiThread {
                        replaceProviders(nextProfiles, notifyUi = true)
                        onProfilesChanged(nextProfiles)
                        Toast.makeText(activity, "$homeName refreshed.", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                } catch (t: Throwable) {
                    activity.runOnUiThread {
                        save.isEnabled = true
                        remove?.isEnabled = true
                        status.text = "⚠ Could not refresh: ${t.message ?: t.javaClass.simpleName}. Existing providers were kept."
                    }
                }
            }.start()
        }

        remove?.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("Remove ${initialProfile.normalizedHomeName()}?")
                .setMessage(
                    "This removes only this profile's manifest URLs, Home/Search providers, source order and filter settings. " +
                        "Other TPBBridge profiles are not changed."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove profile") { _, _ ->
                    val nextProfiles = currentProfiles.filterNot { it.id == initialProfile.id }
                    if (!persistProfiles(prefs, nextProfiles)) {
                        Toast.makeText(activity, "Profile was not removed.", Toast.LENGTH_LONG).show()
                    } else {
                        invalidateManifestCache()
                        replaceProviders(nextProfiles, notifyUi = true)
                        onProfilesChanged(nextProfiles)
                        Toast.makeText(activity, "Profile removed.", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
                .show()
        }

        dialog.show()
    }

    /** Names that may still exist in CloudStream selection state when wiping. */
    private fun allKnownProviderNames(profiles: List<BridgeProfile>): Set<String> = linkedSetOf<String>().apply {
        addAll(registeredProviders.map { it.name })
        profiles.forEach { profile ->
            val home = profile.normalizedHomeName()
            add(home)
            profile.routeGroups.forEach { group ->
                val raw = (profile.searchPrefix + group.sourceName).trim()
                if (raw.isNotBlank()) {
                    add(raw)
                    add("$raw • $home")
                }
            }
            FacetKind.entries.forEach { kind -> add("$home • ${kind.label}") }
        }
    }
}
