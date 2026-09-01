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
    /** Providers registered by this plugin instance. Used for safe live reconfiguration. */
    private val registeredProviders = mutableListOf<MainAPI>()

    override fun load(context: Context) {
        openSettings = { settingsContext -> showSettings(settingsContext) }

        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val bases = parseManifestInput(prefs.getString(PREF_MANIFESTS, "").orEmpty())
        val homeName = prefs.getString(PREF_HOME_NAME, DEFAULT_HOME_NAME)
            ?.trim().orEmpty().ifBlank { DEFAULT_HOME_NAME }
        val prefix = prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX).orEmpty()
        val routeGroups = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())

        replaceProviders(bases, homeName, prefix, routeGroups, notifyUi = false)
    }

    /**
     * Replaces only providers created by this plugin instance.
     * This avoids reloading the .cs3 itself; CloudStream explicitly warns plugins not to hot-reload plugins.
     */
    @Synchronized
    private fun replaceProviders(
        bases: List<String>,
        homeName: String,
        prefix: String,
        routeGroups: List<SearchRouteGroup>,
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
            val home = TPBHomeProvider(homeName, bases)
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

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
        }

        val currentRoutes = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())
        val currentBases = parseManifestInput(prefs.getString(PREF_MANIFESTS, "").orEmpty())

        val summary = TextView(activity).apply {
            setPadding(0, 0, 0, dp(8))
            text = configurationSummary(currentBases.size, currentRoutes)
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
        root.addView(helper("Leave blank for clean names such as Hotleak and Notfans."))
        val prefixEdit = EditText(activity).apply {
            setSingleLine(true)
            setText(prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX) ?: DEFAULT_SEARCH_PREFIX)
            hint = "Example: TPB • "
        }
        root.addView(prefixEdit)

        val status = TextView(activity).apply {
            setPadding(0, dp(12), 0, dp(8))
            text = "Ready."
        }
        root.addView(status)

        val apply = Button(activity).apply { text = "Save + apply now" }
        root.addView(apply)
        root.addView(helper("This re-discovers Search catalogs and refreshes TPBBridge providers immediately. No app restart is needed."))

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

            if (raw.isNotBlank() && bases.isEmpty()) {
                status.text = "No valid HTTP/HTTPS/Stremio manifest URL was found."
                return@setOnClickListener
            }

            if (bases.isEmpty()) {
                prefs.edit()
                    .putString(PREF_MANIFESTS, "")
                    .putString(PREF_HOME_NAME, homeName)
                    .putString(PREF_SEARCH_PREFIX, prefix)
                    .remove(PREF_ROUTES)
                    .apply()
                invalidateManifestCache()
                replaceProviders(emptyList(), homeName, prefix, emptyList(), notifyUi = true)
                summary.text = configurationSummary(0, emptyList())
                status.text = "Cleared and applied."
                Toast.makeText(activity, "TPBBridge configuration cleared.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            apply.isEnabled = false
            status.text = "Reading manifest(s) and discovering Search sources…"

            Thread {
                try {
                    val discovered = discoverSearchRoutes(bases)

                    prefs.edit()
                        .putString(PREF_MANIFESTS, raw)
                        .putString(PREF_HOME_NAME, homeName)
                        .putString(PREF_SEARCH_PREFIX, prefix)
                        .putString(PREF_ROUTES, saveRouteGroups(discovered))
                        .apply()

                    invalidateManifestCache()
                    activity.runOnUiThread {
                        replaceProviders(bases, homeName, prefix, discovered, notifyUi = true)
                        apply.isEnabled = true
                        summary.text = configurationSummary(bases.size, discovered)
                        status.text = if (discovered.isEmpty()) {
                            "Saved and applied. No Search catalogs were found; Home still works. Enable Search for the desired TPB sources and press Save + apply now again."
                        } else {
                            "Applied now: ${discovered.size} Search source(s)."
                        }
                        Toast.makeText(
                            activity,
                            "TPBBridge applied ${discovered.size} Search source(s).",
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
