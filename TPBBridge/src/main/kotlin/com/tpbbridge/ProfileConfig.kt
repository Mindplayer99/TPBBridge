/*
 * TPBBridge - v11 profile configuration.
 *
 * A profile owns one or more manifest URLs plus its Home/Search/source settings.
 * This keeps independently configured manifest groups isolated while preserving
 * the v10 single-setup behavior through an automatic one-time migration.
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
internal const val PREF_PROFILES_BACKUP = "profiles_v11_backup"

internal data class BridgeProfile(
    val id: String,
    val manifestInput: String,
    val homeName: String,
    val searchPrefix: String,
    val routeGroups: List<SearchRouteGroup> = emptyList(),
    val facetRoutes: List<FacetRoute> = emptyList(),
    val managedSources: List<String> = emptyList(),
    val homeOrder: List<String> = emptyList(),
    val disabledSources: List<String> = emptyList(),
    val parentSearch: Boolean = false,
    val studioEnabled: Boolean = false,
    val performerEnabled: Boolean = false,
    val tagEnabled: Boolean = false
) {
    val bases: List<String>
        get() = parseManifestInput(manifestInput)

    fun normalizedHomeName(): String = homeName.trim().ifBlank { DEFAULT_HOME_NAME }
}

internal fun newProfileId(): String = "p-${UUID.randomUUID()}"

internal fun saveProfilesJson(profiles: List<BridgeProfile>): String {
    val out = JSONArray()
    profiles.forEach { profile ->
        out.put(JSONObject().apply {
            put("id", profile.id)
            put("manifestInput", profile.manifestInput)
            put("homeName", profile.normalizedHomeName())
            put("searchPrefix", profile.searchPrefix)
            put("routes", JSONArray(saveRouteGroups(profile.routeGroups)))
            put("facetRoutes", JSONArray(saveFacetRoutes(profile.facetRoutes)))
            put("managedSources", JSONArray(saveHomeNameList(profile.managedSources)))
            put("homeOrder", JSONArray(saveHomeNameList(profile.homeOrder)))
            put("disabledSources", JSONArray(saveHomeNameList(profile.disabledSources)))
            put("parentSearch", profile.parentSearch)
            put("studioEnabled", profile.studioEnabled)
            put("performerEnabled", profile.performerEnabled)
            put("tagEnabled", profile.tagEnabled)
        })
    }
    return out.toString()
}

/** Null means malformed/corrupt; an empty list is a valid saved configuration. */
internal fun parseProfilesJson(json: String): List<BridgeProfile>? {
    if (json.isBlank()) return null
    return try {
        val array = JSONArray(json)
        val seenIds = mutableSetOf<String>()
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                var id = obj.optString("id", "").trim()
                if (id.isBlank() || !seenIds.add(id)) {
                    do {
                        id = newProfileId()
                    } while (!seenIds.add(id))
                }

                val raw = obj.optString("manifestInput", "")
                val homeName = obj.optString("homeName", DEFAULT_HOME_NAME)
                    .trim().ifBlank { DEFAULT_HOME_NAME }
                val searchPrefix = obj.optString("searchPrefix", DEFAULT_SEARCH_PREFIX)
                val routes = loadRouteGroups(obj.optJSONArray("routes")?.toString().orEmpty())
                val facets = loadFacetRoutes(obj.optJSONArray("facetRoutes")?.toString().orEmpty())
                val sources = loadHomeNameList(obj.optJSONArray("managedSources")?.toString().orEmpty())
                val order = reconcileHomeOrder(
                    loadHomeNameList(obj.optJSONArray("homeOrder")?.toString().orEmpty()),
                    sources
                )
                val disabled = loadHomeNameList(
                    obj.optJSONArray("disabledSources")?.toString().orEmpty()
                )

                add(
                    BridgeProfile(
                        id = id,
                        manifestInput = raw,
                        homeName = homeName,
                        searchPrefix = searchPrefix,
                        routeGroups = routes,
                        facetRoutes = facets,
                        managedSources = sources,
                        homeOrder = order,
                        disabledSources = disabled,
                        parentSearch = obj.optBoolean("parentSearch", false),
                        studioEnabled = obj.optBoolean("studioEnabled", false),
                        performerEnabled = obj.optBoolean("performerEnabled", false),
                        tagEnabled = obj.optBoolean("tagEnabled", false)
                    )
                )
            }
        }
    } catch (_: Throwable) {
        null
    }
}

internal fun loadProfilesJson(json: String): List<BridgeProfile> =
    parseProfilesJson(json) ?: emptyList()

/**
 * Keep one last known-good profile snapshot. A valid primary value always wins,
 * including an intentionally saved empty list, so deleted profiles cannot be
 * resurrected by the backup.
 */
internal fun persistProfiles(prefs: SharedPreferences, profiles: List<BridgeProfile>): Boolean {
    val next = saveProfilesJson(profiles)
    val current = prefs.getString(PREF_PROFILES, null)
    val editor = prefs.edit()
    if (!current.isNullOrBlank() && parseProfilesJson(current) != null) {
        editor.putString(PREF_PROFILES_BACKUP, current)
    }
    return editor.putString(PREF_PROFILES, next).commit()
}

private fun loadSavedProfilesSafely(prefs: SharedPreferences): List<BridgeProfile>? {
    if (!prefs.contains(PREF_PROFILES)) return null

    val primaryRaw = prefs.getString(PREF_PROFILES, "").orEmpty()
    val primary = parseProfilesJson(primaryRaw)
    if (primary != null) return primary

    val backupRaw = prefs.getString(PREF_PROFILES_BACKUP, "").orEmpty()
    val backup = parseProfilesJson(backupRaw)
    if (backup != null) {
        // Best-effort self-heal. Even if this commit fails, use the valid backup
        // for the current session instead of making every profile appear gone.
        prefs.edit().putString(PREF_PROFILES, backupRaw).commit()
        return backup
    }

    // Both v11 copies are invalid. Return null so legacy v10 data, if it still
    // exists from an interrupted migration, gets one final recovery chance.
    return null
}

/**
 * v10 -> v11 migration. The old configuration becomes exactly one profile, so
 * existing users keep the same manifests, visible names, route cache, source
 * order/disable state and optional search/filter toggles.
 *
 * The new profile value is committed first. Legacy keys are removed only after
 * that succeeds, avoiding a destructive partial migration.
 */
internal fun loadProfilesWithMigration(prefs: SharedPreferences): List<BridgeProfile> {
    loadSavedProfilesSafely(prefs)?.let { return it }

    val raw = prefs.getString(PREF_MANIFESTS, "").orEmpty()
    val bases = parseManifestInput(raw)
    if (bases.isEmpty()) {
        // If v11 existed but both primary and backup were malformed, do not
        // overwrite them with an empty value. This preserves evidence/data for
        // recovery instead of turning corruption into a destructive save.
        if (prefs.contains(PREF_PROFILES)) return emptyList()
        persistProfiles(prefs, emptyList())
        return emptyList()
    }

    val homeName = prefs.getString(PREF_HOME_NAME, DEFAULT_HOME_NAME)
        ?.trim().orEmpty().ifBlank { DEFAULT_HOME_NAME }
    val prefix = prefs.getString(PREF_SEARCH_PREFIX, DEFAULT_SEARCH_PREFIX).orEmpty()
    val routes = loadRouteGroups(prefs.getString(PREF_ROUTES, "").orEmpty())
    val facets = loadFacetRoutes(prefs.getString(PREF_FACET_ROUTES, "").orEmpty())
    val sources = loadHomeNameList(prefs.getString(PREF_HOME_SOURCES, "").orEmpty())
    val order = reconcileHomeOrder(
        loadHomeNameList(prefs.getString(PREF_HOME_ORDER, "").orEmpty()),
        sources
    )
    val disabled = loadHomeNameList(prefs.getString(PREF_DISABLED_SOURCES, "").orEmpty())
    val stableMigrationId = "p-legacy-${baseRef(bases.joinToString("\n"))}"

    val migrated = BridgeProfile(
        id = stableMigrationId,
        manifestInput = raw,
        homeName = homeName,
        searchPrefix = prefix,
        routeGroups = routes,
        facetRoutes = facets,
        managedSources = sources,
        homeOrder = order,
        disabledSources = disabled,
        parentSearch = prefs.getBoolean(PREF_PARENT_SEARCH, false),
        studioEnabled = prefs.getBoolean(PREF_FACET_STUDIO, false),
        performerEnabled = prefs.getBoolean(PREF_FACET_PERFORMER, false),
        tagEnabled = prefs.getBoolean(PREF_FACET_TAG, false)
    )

    val profiles = listOf(migrated)
    if (persistProfiles(prefs, profiles)) {
        prefs.edit()
            .remove(PREF_MANIFESTS)
            .remove(PREF_HOME_NAME)
            .remove(PREF_SEARCH_PREFIX)
            .remove(PREF_ROUTES)
            .remove(PREF_FACET_ROUTES)
            .remove(PREF_HOME_SOURCES)
            .remove(PREF_HOME_ORDER)
            .remove(PREF_DISABLED_SOURCES)
            .remove(PREF_PARENT_SEARCH)
            .remove(PREF_FACET_STUDIO)
            .remove(PREF_FACET_PERFORMER)
            .remove(PREF_FACET_TAG)
            .apply()
    }
    return profiles
}

internal fun profileHomeNamesAreUnique(
    profiles: List<BridgeProfile>,
    candidateId: String,
    candidateHomeName: String
): Boolean {
    val key = candidateHomeName.trim().lowercase(Locale.ROOT)
    return profiles.none {
        it.id != candidateId && it.normalizedHomeName().lowercase(Locale.ROOT) == key
    }
}

internal fun profileSummary(profile: BridgeProfile): String {
    val manifests = profile.bases.size
    val sources = profile.managedSources.size
    val off = profile.managedSources.count { isSourceDisabled(it, profile.disabledSources) }
    return buildString {
        append(manifests)
        append(if (manifests == 1) " manifest" else " manifests")
        append(" • ")
        append(sources)
        append(if (sources == 1) " source" else " sources")
        if (off > 0) append(" • $off off")
    }
}
