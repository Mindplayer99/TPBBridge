/*
 * TPBBridge - v11 profile-scoped configuration.
 *
 * Profiles isolate manifest groups from one another while preserving the stream,
 * metadata and debrid engine. Existing v10 settings migrate into one profile.
 *
 * GPL-3.0-or-later.
 */
package com.tpbbridge

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal const val PREF_PROFILES = "profiles_v11"

internal data class BridgeProfile(
    val id: String = UUID.randomUUID().toString(),
    val manifestInput: String,
    val homeName: String = DEFAULT_HOME_NAME,
    val searchPrefix: String = DEFAULT_SEARCH_PREFIX,
    val searchGroups: List<SearchRouteGroup> = emptyList(),
    val facetRoutes: List<FacetRoute> = emptyList(),
    val homeSources: List<String> = emptyList(),
    val homeOrder: List<String> = emptyList(),
    val disabledSources: List<String> = emptyList(),
    val parentSearch: Boolean = false,
    val studioEnabled: Boolean = false,
    val performerEnabled: Boolean = false,
    val tagEnabled: Boolean = false
) {
    val bases: List<String>
        get() = parseManifestInput(manifestInput)
}

internal fun newBridgeProfile(index: Int): BridgeProfile = BridgeProfile(
    manifestInput = "",
    homeName = if (index <= 1) DEFAULT_HOME_NAME else "$DEFAULT_HOME_NAME $index"
)

internal fun saveProfiles(prefs: SharedPreferences, profiles: List<BridgeProfile>): Boolean =
    prefs.edit().putString(PREF_PROFILES, profilesToJson(profiles)).commit()

internal fun loadProfilesOrMigrate(prefs: SharedPreferences): List<BridgeProfile> {
    val stored = prefs.getString(PREF_PROFILES, null)
    if (stored != null) {
        val parsed = profilesFromJson(stored)
        if (parsed.isNotEmpty() || stored.trim() == "[]") return parsed
        // Corrupt profile JSON should not silently hide a still-valid v10 setup.
    }

    val legacyInput = prefs.getString(PREF_MANIFESTS, "").orEmpty()
    if (parseManifestInput(legacyInput).isEmpty()) return emptyList()

    val legacy = BridgeProfile(
        id = "migrated-v10",
        manifestInput = legacyInput,
        homeName = prefs.getString(PREF_HOME_NAME, DEFAULT_HOME_NAME)
            ?.trim().orEmpty().ifBlank { DEFAULT_HOME_NAME },
        searchPrefix = prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX).orEmpty(),
        searchGroups = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty()),
        facetRoutes = loadFacetRoutes(prefs.getString(PREF_FACET_ROUTES, "").orEmpty()),
        homeSources = loadHomeNameList(prefs.getString(PREF_HOME_SOURCES, "").orEmpty()),
        homeOrder = loadHomeNameList(prefs.getString(PREF_HOME_ORDER, "").orEmpty()),
        disabledSources = loadHomeNameList(prefs.getString(PREF_DISABLED_SOURCES, "").orEmpty()),
        parentSearch = prefs.getBoolean(PREF_PARENT_SEARCH, false),
        studioEnabled = prefs.getBoolean(PREF_FACET_STUDIO, false),
        performerEnabled = prefs.getBoolean(PREF_FACET_PERFORMER, false),
        tagEnabled = prefs.getBoolean(PREF_FACET_TAG, false)
    )

    // Keep legacy keys for rollback safety. The full wipe clears the whole file.
    prefs.edit().putString(PREF_PROFILES, profilesToJson(listOf(legacy))).commit()
    return listOf(legacy)
}

private fun profilesToJson(profiles: List<BridgeProfile>): String {
    val arr = JSONArray()
    profiles.forEach { profile ->
        arr.put(JSONObject().apply {
            put("id", profile.id)
            put("manifestInput", profile.manifestInput)
            put("homeName", profile.homeName)
            put("searchPrefix", profile.searchPrefix)
            put("searchGroups", JSONArray(saveRouteGroups(profile.searchGroups)))
            put("facetRoutes", JSONArray(saveFacetRoutes(profile.facetRoutes)))
            put("homeSources", JSONArray(saveHomeNameList(profile.homeSources)))
            put("homeOrder", JSONArray(saveHomeNameList(profile.homeOrder)))
            put("disabledSources", JSONArray(saveHomeNameList(profile.disabledSources)))
            put("parentSearch", profile.parentSearch)
            put("studioEnabled", profile.studioEnabled)
            put("performerEnabled", profile.performerEnabled)
            put("tagEnabled", profile.tagEnabled)
        })
    }
    return arr.toString()
}

private fun profilesFromJson(json: String): List<BridgeProfile> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val manifestInput = obj.optString("manifestInput", "")
                if (parseManifestInput(manifestInput).isEmpty()) continue
                val id = obj.optString("id", "").trim().ifBlank { UUID.randomUUID().toString() }
                val homeName = obj.optString("homeName", DEFAULT_HOME_NAME)
                    .trim().ifBlank { DEFAULT_HOME_NAME }
                add(
                    BridgeProfile(
                        id = id,
                        manifestInput = manifestInput,
                        homeName = homeName,
                        searchPrefix = obj.optString("searchPrefix", DEFAULT_SEARCH_PREFIX),
                        searchGroups = loadRouteGroups(obj.optJSONArray("searchGroups")?.toString().orEmpty()),
                        facetRoutes = loadFacetRoutes(obj.optJSONArray("facetRoutes")?.toString().orEmpty()),
                        homeSources = loadHomeNameList(obj.optJSONArray("homeSources")?.toString().orEmpty()),
                        homeOrder = loadHomeNameList(obj.optJSONArray("homeOrder")?.toString().orEmpty()),
                        disabledSources = loadHomeNameList(obj.optJSONArray("disabledSources")?.toString().orEmpty()),
                        parentSearch = obj.optBoolean("parentSearch", false),
                        studioEnabled = obj.optBoolean("studioEnabled", false),
                        performerEnabled = obj.optBoolean("performerEnabled", false),
                        tagEnabled = obj.optBoolean("tagEnabled", false)
                    )
                )
            }
        }.distinctBy { it.id }
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun activeSearchGroups(profile: BridgeProfile): List<SearchRouteGroup> {
    val disabled = profile.disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
    return profile.searchGroups.filter { homeSourceKey(it.sourceName) !in disabled }
}

internal fun activeFacetRoutes(profile: BridgeProfile): List<FacetRoute> {
    val disabled = profile.disabledSources.mapTo(mutableSetOf(), ::homeSourceKey)
    return profile.facetRoutes.filter { homeSourceKey(it.sourceName) !in disabled }
}

private fun rawProviderNamesForProfile(profile: BridgeProfile): List<String> {
    if (profile.bases.isEmpty()) return emptyList()
    val names = mutableListOf(profile.homeName)
    activeSearchGroups(profile).forEach { names += profile.searchPrefix + it.sourceName }
    val activeFacets = activeFacetRoutes(profile)
    if (profile.studioEnabled && activeFacets.any { it.kind == FacetKind.STUDIO }) {
        names += "${profile.homeName} • ${FacetKind.STUDIO.label}"
    }
    if (profile.performerEnabled && activeFacets.any { it.kind == FacetKind.PERFORMER }) {
        names += "${profile.homeName} • ${FacetKind.PERFORMER.label}"
    }
    if (profile.tagEnabled && activeFacets.any { it.kind == FacetKind.TAG }) {
        names += "${profile.homeName} • ${FacetKind.TAG.label}"
    }
    return names
}

internal fun providerNamesForProfile(profile: BridgeProfile): Set<String> =
    rawProviderNamesForProfile(profile).toCollection(linkedSetOf())

/** Returns a user-facing collision message, or null when all provider names are unique. */
internal fun validateProfileProviderNames(profiles: List<BridgeProfile>): String? {
    val owners = linkedMapOf<String, String>()
    profiles.forEach { profile ->
        val local = mutableSetOf<String>()
        rawProviderNamesForProfile(profile).forEach { name ->
            val key = name.trim().lowercase(Locale.ROOT)
            if (!local.add(key)) {
                return "Provider name ‘$name’ is duplicated inside this profile. Change the Home name or Search prefix."
            }
            val previousOwner = owners[key]
            if (previousOwner != null && previousOwner != profile.id) {
                return "Provider name ‘$name’ is already used by another profile. Change the Home name or Search prefix."
            }
            owners[key] = profile.id
        }
    }
    return null
}
