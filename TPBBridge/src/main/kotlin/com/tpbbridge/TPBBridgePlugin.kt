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
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
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
internal const val TPB_CONFIGURE_URL = "https://tpb-adult-addon.click/configure"
internal const val MANIFEST_CACHE_MS = 5 * 60 * 1000L
internal const val HOME_CATALOG_CACHE_MS = 2 * 60 * 1000L
internal const val LIVE_HOME_CATALOG_CACHE_MS = 20 * 1000L
internal const val SEARCH_CATALOG_CACHE_MS = 2 * 60 * 1000L
internal const val METADATA_CACHE_MS = 5 * 60 * 1000L
internal const val SEARCH_PAGE_SIZE = 20

private data class SetupPalette(
    val surface: Int,
    val raisedSurface: Int,
    val border: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val accent: Int,
    val success: Int,
    val warning: Int
)

private fun Activity.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private fun Activity.themeColor(attribute: Int, fallback: Int): Int {
    val values = obtainStyledAttributes(intArrayOf(attribute))
    return try {
        values.getColor(0, fallback)
    } finally {
        values.recycle()
    }
}

private fun mixColor(base: Int, overlay: Int, amount: Float): Int {
    val fraction = amount.coerceIn(0f, 1f)
    fun channel(first: Int, second: Int): Int =
        (first + (second - first) * fraction).toInt().coerceIn(0, 255)
    return Color.rgb(
        channel(Color.red(base), Color.red(overlay)),
        channel(Color.green(base), Color.green(overlay)),
        channel(Color.blue(base), Color.blue(overlay))
    )
}

private fun Activity.setupPalette(): SetupPalette {
    val background = themeColor(android.R.attr.colorBackground, Color.rgb(18, 18, 18))
    val primary = themeColor(android.R.attr.textColorPrimary, Color.WHITE)
    val secondary = themeColor(
        android.R.attr.textColorSecondary,
        mixColor(background, primary, 0.68f)
    )
    val accent = themeColor(android.R.attr.colorAccent, Color.rgb(125, 96, 255))
    val dark = (Color.red(background) * 0.299 +
        Color.green(background) * 0.587 +
        Color.blue(background) * 0.114) < 128
    return SetupPalette(
        surface = mixColor(background, primary, if (dark) 0.07f else 0.035f),
        raisedSurface = mixColor(background, primary, if (dark) 0.12f else 0.07f),
        border = mixColor(background, primary, if (dark) 0.20f else 0.14f),
        primaryText = primary,
        secondaryText = secondary,
        accent = accent,
        success = if (dark) Color.rgb(129, 201, 149) else Color.rgb(19, 115, 51),
        warning = if (dark) Color.rgb(253, 186, 116) else Color.rgb(180, 83, 9)
    )
}

private fun roundedPanelBackground(
    activity: Activity,
    palette: SetupPalette,
    raised: Boolean = false
) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(if (raised) palette.raisedSurface else palette.surface)
    cornerRadius = activity.dp(14).toFloat()
    setStroke(activity.dp(1).coerceAtLeast(1), palette.border)
}

private fun setupPanel(
    activity: Activity,
    palette: SetupPalette,
    raised: Boolean = false
) = LinearLayout(activity).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
    background = roundedPanelBackground(activity, palette, raised)
}

private fun addSetupPanel(activity: Activity, root: LinearLayout, panel: LinearLayout) {
    root.addView(
        panel,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = activity.dp(10) }
    )
}

private fun setupText(
    activity: Activity,
    palette: SetupPalette,
    value: String,
    size: Float = 14f,
    bold: Boolean = false
) = TextView(activity).apply {
    text = value
    textSize = size
    setTextColor(if (bold) palette.primaryText else palette.secondaryText)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

private fun openTpbConfigurator(activity: Activity) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TPB_CONFIGURE_URL))
    runCatching { activity.startActivity(intent) }
        .onFailure {
            Toast.makeText(
                activity,
                "No browser could open the TPB configurator.",
                Toast.LENGTH_LONG
            ).show()
        }
}

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
                    catalogSources = profile.catalogSources,
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

    private fun showManifestManagerDialog(
        activity: Activity,
        bases: List<String>,
        initialDisabledRefs: List<String>,
        onDone: (List<String>) -> Unit
    ) {
        val palette = activity.setupPalette()
        val disabled = initialDisabledRefs.toMutableSet()
        val labels = bases.mapIndexed { index, base ->
            val host = runCatching { android.net.Uri.parse(base).host }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "configured URL"
            "Manifest ${index + 1} • $host\nID ${baseRef(base).take(8).uppercase()}"
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(8), activity.dp(18), activity.dp(12))
        }
        val summary = TextView(activity).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, activity.dp(8))
        }
        root.addView(summary)

        fun updateSummary() {
            val onCount = bases.count { baseRef(it) !in disabled }
            summary.text = "$onCount enabled • ${bases.size - onCount} disabled\n" +
                "Disabled URLs stay saved and make zero catalogue, search, metadata or stream requests."
            summary.setTextColor(if (onCount > 0) palette.success else palette.warning)
        }

        val switches = mutableListOf<Switch>()

        fun updateSwitchLabel(index: Int, manifestSwitch: Switch) {
            manifestSwitch.text = labels[index] + "\n" + if (manifestSwitch.isChecked) {
                "ON • included after Save + refresh"
            } else {
                "OFF • retained, no requests"
            }
        }

        val bulkActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bulkActions.addView(
            Button(activity).apply {
                text = "Enable all"
                isAllCaps = false
                setOnClickListener { switches.forEach { it.isChecked = true } }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = activity.dp(4)
            }
        )
        bulkActions.addView(
            Button(activity).apply {
                text = "Disable all"
                isAllCaps = false
                setOnClickListener { switches.forEach { it.isChecked = false } }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = activity.dp(4)
            }
        )
        root.addView(bulkActions)

        labels.forEachIndexed { index, _ ->
            val ref = baseRef(bases[index])
            val manifestSwitch = Switch(activity).apply {
                textSize = 15f
                isChecked = ref !in disabled
                setPadding(0, activity.dp(3), 0, activity.dp(3))
                updateSwitchLabel(index, this)
                setOnCheckedChangeListener { _, enabled ->
                    if (enabled) disabled.remove(ref) else disabled.add(ref)
                    updateSwitchLabel(index, this)
                    updateSummary()
                }
            }
            switches += manifestSwitch
            val panel = setupPanel(activity, palette).apply { addView(manifestSwitch) }
            addSetupPanel(activity, root, panel)
        }
        updateSummary()

        val scroll = ScrollView(activity).apply { addView(root) }

        AlertDialog.Builder(activity)
            .setTitle("Enabled manifests")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ ->
                onDone(bases.map(::baseRef).filter { it in disabled })
            }
            .show()
    }

    private fun showProfileManager(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        var profiles = loadProfilesOrMigrate(prefs)
        fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
        val palette = activity.setupPalette()

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
            setTextColor(palette.primaryText)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(5))
        }
        fun helper(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTextColor(palette.secondaryText)
            setPadding(0, 0, 0, dp(5))
        }

        fun applyProfiles(updated: List<BridgeProfile>) {
            profiles = updated
            replaceProviders(updated, notifyUi = true)
        }

        fun render() {
            root.removeAllViews()
            val savedManifestCount = profiles.sumOf { it.allBases.size }
            val activeManifestCount = profiles.sumOf { it.bases.size }
            val activeSourceCount = profiles.sumOf { profile ->
                if (profile.bases.isEmpty()) {
                    0
                } else {
                    val disabled = profile.disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
                    profile.homeSources.count { homeSourceKey(it) !in disabled }
                }
            }
            val dashboard = setupPanel(activity, palette, raised = true).apply {
                addView(setupText(activity, palette, "CloudStream setup", 18f, bold = true))
                addView(setupText(
                    activity,
                    palette,
                    if (profiles.isEmpty()) {
                        "No profile yet • start by generating a TPB manifest"
                    } else {
                        "${profiles.size} profile${if (profiles.size == 1) "" else "s"} • " +
                            "$activeManifestCount/$savedManifestCount manifests ON • " +
                            "$activeSourceCount active sources"
                    },
                    14f
                ).apply { setPadding(0, dp(5), 0, 0) })
            }
            addSetupPanel(activity, root, dashboard)

            val guide = setupPanel(activity, palette).apply {
                addView(setupText(activity, palette, "How setup works", 16f, bold = true))
                addView(setupText(
                    activity,
                    palette,
                    "1. Choose sources, qualities, debrid and metadata on TPB's configurator.\n" +
                        "2. Paste its generated manifest URL into a profile.\n" +
                        "3. Save + refresh, then arrange what CloudStream shows.",
                    13.5f
                ).apply { setPadding(0, dp(5), 0, dp(6)) })
                addView(Button(activity).apply {
                    text = "Open TPB configurator"
                    isAllCaps = false
                    setOnClickListener { openTpbConfigurator(activity) }
                })
            }
            addSetupPanel(activity, root, guide)

            root.addView(header("Profiles"))
            root.addView(helper("Profiles are independent. A profile can contain one or more split manifest URLs."))

            profiles.forEach { profile ->
                val disabledKeys = profile.disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
                val activeCount = if (profile.bases.isEmpty()) 0 else {
                    profile.homeSources.count { homeSourceKey(it) !in disabledKeys }
                }
                val savedManifests = profile.allBases.size
                val activeManifests = profile.bases.size
                val facets = activeFacetRoutes(profile)
                val searchCount = if (profile.bases.isEmpty()) 0 else {
                    activeSearchGroups(profile).size + listOf(
                        profile.studioEnabled && facets.any { it.kind == FacetKind.STUDIO },
                        profile.performerEnabled && facets.any { it.kind == FacetKind.PERFORMER },
                        profile.tagEnabled && facets.any { it.kind == FacetKind.TAG }
                    ).count { it }
                }
                val profilePanel = setupPanel(activity, palette).apply {
                    addView(setupText(activity, palette, profile.homeName, 17f, bold = true))
                    addView(setupText(
                        activity,
                        palette,
                        "$activeManifests/$savedManifests manifests ON • $activeCount sources ON",
                        13.5f
                    ).apply {
                        setPadding(0, dp(4), 0, 0)
                        setTextColor(if (activeManifests > 0) palette.success else palette.warning)
                    })
                    addView(setupText(
                        activity,
                        palette,
                        if (searchCount > 0) "$searchCount CloudStream search/filter providers" else "No optional search/filter providers",
                        12.5f
                    ).apply { setPadding(0, dp(2), 0, dp(7)) })
                    addView(Button(activity).apply {
                        isAllCaps = false
                        text = "Configure profile"
                        setOnClickListener {
                            showProfileEditor(activity, prefs, profile, profiles) { updated ->
                                applyProfiles(updated)
                                render()
                            }
                        }
                    })
                }
                addSetupPanel(activity, root, profilePanel)
            }

            root.addView(Button(activity).apply {
                text = "+ New profile"
                isAllCaps = false
                setOnClickListener {
                    showProfileEditor(activity, prefs, null, profiles) { updated ->
                        applyProfiles(updated)
                        render()
                    }
                }
            })

            root.addView(header("Data & privacy"))
            val dataPanel = setupPanel(activity, palette).apply {
                addView(setupText(
                    activity,
                    palette,
                    "Manifest URLs may contain API keys. TPBBridge stores them only in CloudStream's private app data.",
                    13f
                ).apply { setPadding(0, 0, 0, dp(6)) })
                addView(Button(activity).apply {
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
            }
            addSetupPanel(activity, root, dataPanel)
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
        val palette = activity.setupPalette()
        fun section(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 17f
            setTextColor(palette.primaryText)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(4))
        }
        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTextColor(palette.primaryText)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(7), 0, dp(2))
        }
        fun helper(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTextColor(palette.secondaryText)
            setPadding(0, 0, 0, dp(4))
        }
        fun toggle(title: String, checked: Boolean) = Switch(activity).apply {
            textSize = 16f
            isChecked = checked
            fun updateText() {
                text = "$title • ${if (isChecked) "ON" else "OFF"}"
            }
            updateText()
            setOnCheckedChangeListener { _, _ -> updateText() }
        }

        lateinit var editorDialog: AlertDialog
        val seed = current ?: newBridgeProfile(allProfiles.size + 1)
        var availableSources = seed.homeSources
        var workingOrder = reconcileHomeOrder(seed.homeOrder, availableSources)
        var workingDisabled = seed.disabledSources
        var workingDisabledManifestRefs = seed.disabledManifestRefs

        fun manifestSwitchLabel(bases: List<String>, disabledRefs: List<String>): String {
            if (bases.isEmpty()) return "Manifest switches • add URL(s) first"
            val disabledSet = disabledRefs.toSet()
            val onCount = bases.count { baseRef(it) !in disabledSet }
            return "Manifest switches • $onCount ON • ${bases.size - onCount} OFF"
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(18))
        }

        val intro = setupPanel(activity, palette, raised = true).apply {
            addView(setupText(
                activity,
                palette,
                if (current == null) "Create a CloudStream profile" else seed.homeName,
                18f,
                bold = true
            ))
            addView(setupText(
                activity,
                palette,
                "The TPB URL decides what exists. This profile decides how it is named, searched, ordered and enabled in CloudStream.",
                13.5f
            ).apply { setPadding(0, dp(5), 0, 0) })
        }
        addSetupPanel(activity, root, intro)

        val manifestPanel = setupPanel(activity, palette).apply {
            addView(setupText(activity, palette, "1. TPB manifests", 17f, bold = true))
            addView(helper(
                "Choose sources, qualities, debrid and metadata on TPB's official configurator, then paste its generated URL here."
            ))
            addView(Button(activity).apply {
                text = "Open TPB configurator"
                isAllCaps = false
                setOnClickListener { openTpbConfigurator(activity) }
            })
            addView(label("Manifest URL(s)"))
            addView(helper("One URL per line • split manifests can stay together in this profile"))
        }
        val manifestsEdit = EditText(activity).apply {
            minLines = 3
            maxLines = 7
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(seed.manifestInput)
            hint = "https://…/manifest.json"
        }
        manifestPanel.addView(
            manifestsEdit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val manifestInputStatus = TextView(activity).apply {
            textSize = 13f
            setPadding(0, dp(5), 0, dp(3))
        }
        manifestPanel.addView(manifestInputStatus)
        manifestPanel.addView(helper("URLs may contain private API keys. They stay in CloudStream's private app data."))

        val manageManifests = Button(activity).apply {
            text = manifestSwitchLabel(seed.allBases, workingDisabledManifestRefs)
            isAllCaps = false
        }
        manifestPanel.addView(manageManifests)
        manifestPanel.addView(helper("Turn URLs off without deleting them. OFF manifests make no requests."))
        addSetupPanel(activity, root, manifestPanel)

        val appearancePanel = setupPanel(activity, palette).apply {
            addView(setupText(activity, palette, "2. CloudStream appearance", 17f, bold = true))
            addView(helper("These labels affect CloudStream only; they do not alter the TPB manifest."))
            addView(label("Home name"))
        }
        val homeEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(seed.homeName)
            hint = DEFAULT_HOME_NAME
        }
        appearancePanel.addView(homeEdit)
        appearancePanel.addView(helper("Name shown for this profile's Home provider"))

        appearancePanel.addView(label("Search prefix (optional)"))
        val prefixEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(seed.searchPrefix)
            hint = "TPB • "
        }
        appearancePanel.addView(prefixEdit)
        appearancePanel.addView(helper("Added only to this profile's individual source searches"))
        addSetupPanel(activity, root, appearancePanel)

        val sourcesPanel = setupPanel(activity, palette).apply {
            addView(setupText(activity, palette, "3. Sources & search", 17f, bold = true))
            addView(helper("Control CloudStream visibility. TPB source selection itself stays in the manifest."))
        }
        val manageSources = Button(activity).apply {
            val disabled = workingDisabled.mapTo(mutableSetOf(), ::homeSourceKey)
            val active = availableSources.count { homeSourceKey(it) !in disabled }
            text = if (availableSources.isEmpty()) "Manage sources" else "Manage sources • $active/${availableSources.size} ON"
            isAllCaps = false
            isEnabled = availableSources.isNotEmpty()
        }
        sourcesPanel.addView(manageSources)
        sourcesPanel.addView(helper(
            if (availableSources.isEmpty()) "Save + refresh once to discover this profile's sources"
            else "Enable, disable, and arrange this profile's sources"
        ))

        sourcesPanel.addView(label("Live catalogs"))
        val separateLiveCategories = toggle(
            "Separate live category rows",
            seed.separateLiveCategories
        )
        sourcesPanel.addView(separateLiveCategories)
        sourcesPanel.addView(helper(
            "Off: one Stripchat row and one Chaturbate row. On: enabled region/category catalogs get their own rows. " +
                "Ignored safely when this profile has no live catalogs."
        ))

        sourcesPanel.addView(label("Search"))
        val parentSearch = toggle("Search through Home name", seed.parentSearch)
        sourcesPanel.addView(parentSearch)
        sourcesPanel.addView(helper("Combines this profile's enabled sources only"))

        sourcesPanel.addView(label("Extra filters"))
        sourcesPanel.addView(helper("Search only • profile-specific • never shown as Home rows"))
        val studio = toggle("Studio", seed.studioEnabled)
        val performer = toggle("Performer", seed.performerEnabled)
        val tag = toggle("Tag", seed.tagEnabled)
        sourcesPanel.addView(studio)
        sourcesPanel.addView(performer)
        sourcesPanel.addView(tag)
        sourcesPanel.addView(helper("Requires matching filter catalogs in the manifest; unavailable filters are ignored safely."))
        addSetupPanel(activity, root, sourcesPanel)

        val status = TextView(activity).apply {
            textSize = 13.5f
            setTextColor(palette.secondaryText)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(status)

        fun updateManifestInputState() {
            val analysis = analyzeManifestInput(manifestsEdit.text.toString())
            val bases = analysis.validBases
            manageManifests.isEnabled = bases.isNotEmpty()
            manageManifests.text = manifestSwitchLabel(bases, workingDisabledManifestRefs)
            manifestInputStatus.text = when {
                analysis.totalEntries == 0 -> "No manifest URL entered"
                analysis.invalidEntries > 0 -> buildString {
                    append(analysis.validBases.size).append(" valid")
                    append(" • ").append(analysis.invalidEntries).append(" invalid")
                    if (analysis.duplicateEntries > 0) {
                        append(" • ").append(analysis.duplicateEntries).append(" duplicate")
                    }
                    append(" • invalid entries must be fixed before saving")
                }
                analysis.duplicateEntries > 0 ->
                    "${analysis.validBases.size} valid • ${analysis.duplicateEntries} duplicate ignored"
                else -> "${analysis.validBases.size} valid manifest${if (analysis.validBases.size == 1) "" else "s"}"
            }
            manifestInputStatus.setTextColor(
                when {
                    analysis.invalidEntries > 0 -> palette.warning
                    bases.isNotEmpty() -> palette.success
                    else -> palette.secondaryText
                }
            )
        }

        manifestsEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                updateManifestInputState()
            }
            override fun afterTextChanged(value: Editable?) = Unit
        })
        updateManifestInputState()

        manageManifests.setOnClickListener {
            val analysis = analyzeManifestInput(manifestsEdit.text.toString())
            val savedBases = analysis.validBases
            if (savedBases.isEmpty()) {
                status.text = "⚠ Add at least one valid manifest URL first."
                status.setTextColor(palette.warning)
                return@setOnClickListener
            }
            showManifestManagerDialog(
                activity,
                savedBases,
                workingDisabledManifestRefs
            ) { disabled ->
                workingDisabledManifestRefs = disabled
                val active = savedBases.count { baseRef(it) !in disabled.toSet() }
                manageManifests.text = manifestSwitchLabel(savedBases, disabled)
                status.text = "$active/${savedBases.size} manifests enabled • Save + refresh to apply"
                status.setTextColor(palette.accent)
            }
        }

        manageSources.setOnClickListener {
            showHomeSourceManagerDialog(
                activity = activity,
                discoveredSources = availableSources,
                initialFullOrder = workingOrder,
                initialDisabledSources = workingDisabled
            ) { result ->
                workingOrder = result.fullOrder
                workingDisabled = result.disabledSources
                val disabled = workingDisabled.mapTo(mutableSetOf(), ::homeSourceKey)
                val active = availableSources.count { homeSourceKey(it) !in disabled }
                manageSources.text = "Manage sources • $active/${availableSources.size} ON"
                status.text = "Source settings changed • Save + refresh to apply"
                status.setTextColor(palette.accent)
            }
        }

        val save = Button(activity).apply {
            text = "Save + refresh"
            isAllCaps = false
        }
        val savePanel = setupPanel(activity, palette, raised = true).apply {
            addView(save)
            addView(helper(
                "Enabled manifests are checked together. Nothing is replaced unless every check and the local save succeed."
            ).apply { setPadding(0, dp(5), 0, 0) })
        }
        addSetupPanel(activity, root, savePanel)

        if (current != null) {
            root.addView(section("Remove profile"))
            val removePanel = setupPanel(activity, palette).apply {
                addView(helper("Removes only this profile. Other profiles and CloudStream data stay untouched."))
                addView(Button(activity).apply {
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
            addSetupPanel(activity, root, removePanel)
        }

        val scroll = ScrollView(activity).apply { addView(root) }
        editorDialog = AlertDialog.Builder(activity)
            .setTitle(if (current == null) "Add profile" else "Configure profile")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        save.setOnClickListener {
            val raw = manifestsEdit.text.toString().trim()
            val inputAnalysis = analyzeManifestInput(raw)
            val allBases = inputAnalysis.validBases
            val currentRefs = allBases.mapTo(mutableSetOf(), ::baseRef)
            val disabledManifestRefsSnapshot = workingDisabledManifestRefs
                .filter { it in currentRefs }
                .distinct()
            val disabledManifestSet = disabledManifestRefsSnapshot.toSet()
            val bases = allBases.filter { baseRef(it) !in disabledManifestSet }
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

            if (raw.isBlank() || allBases.isEmpty()) {
                status.text = "⚠ Add at least one valid manifest URL."
                status.setTextColor(palette.warning)
                return@setOnClickListener
            }
            if (inputAnalysis.invalidEntries > 0) {
                status.text = "⚠ Fix ${inputAnalysis.invalidEntries} invalid manifest " +
                    "entr${if (inputAnalysis.invalidEntries == 1) "y" else "ies"} before saving."
                status.setTextColor(palette.warning)
                return@setOnClickListener
            }

            save.isEnabled = false
            status.text = if (bases.isEmpty()) {
                "Saving with every manifest OFF…"
            } else {
                "Checking ${bases.size} enabled manifest${if (bases.size == 1) "" else "s"} and refreshing sources…"
            }
            status.setTextColor(palette.accent)

            Thread {
                try {
                    // A manual refresh must start from one fresh snapshot. The
                    // discovery helpers then share it with each other and Home.
                    invalidateManifestCache()
                    preloadManifestSnapshots(bases)
                    val discovered = if (bases.isEmpty()) {
                        DiscoveryBundle(emptyList(), emptyList())
                    } else {
                        discoverBridgeRoutes(bases)
                    }
                    val discoveredHome = if (bases.isEmpty()) {
                        emptyList()
                    } else {
                        discoverHomeSources(bases, separateLiveCategoriesEnabled)
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
                        disabledManifestRefs = disabledManifestRefsSnapshot,
                        homeName = homeName,
                        searchPrefix = prefix,
                        searchGroups = discovered.searchGroups,
                        facetRoutes = discovered.facetRoutes,
                        catalogSources = discoveredHome,
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
                            status.setTextColor(palette.warning)
                        }
                        return@Thread
                    }

                    if (!saveProfiles(prefs, updated)) {
                        activity.runOnUiThread {
                            save.isEnabled = true
                            status.text = "⚠ Could not save profile data. Existing providers were kept."
                            status.setTextColor(palette.warning)
                        }
                        return@Thread
                    }

                    activity.runOnUiThread {
                        val oldNames = current?.let(::providerNamesForProfile).orEmpty()
                        val newNames = providerNamesForProfile(candidate)
                        removeCloudStreamSelections(oldNames - newNames)
                        onApplied(updated)
                        val activeKeys = candidate.disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
                        val activeSources = candidate.homeSources.count { homeSourceKey(it) !in activeKeys }
                        Toast.makeText(
                            activity,
                            "${candidate.homeName}: ${candidate.bases.size} manifests and $activeSources sources ready.",
                            Toast.LENGTH_SHORT
                        ).show()
                        editorDialog.dismiss()
                    }
                } catch (t: Throwable) {
                    activity.runOnUiThread {
                        save.isEnabled = true
                        status.text = "⚠ Could not refresh: ${t.message ?: t.javaClass.simpleName}. Existing providers were kept."
                        status.setTextColor(palette.warning)
                    }
                }
            }.start()
        }

        editorDialog.show()
    }
}
