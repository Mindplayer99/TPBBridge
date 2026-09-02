/*
 * TPBBridge - narrow TPB protocol compatibility helpers.
 *
 * Keep TPB-specific catalog and live-stream quirks here so the general
 * Stremio/debrid/playback path stays stable.
 *
 * GPL-3.0-or-later.
 */
package com.tpbbridge

import java.util.Locale

/**
 * Decide whether a manifest catalog belongs on TPBBridge Home.
 *
 * The bridge intentionally does not expose every catalog in a large TPB
 * manifest. Required-extra catalogs (Search/Studio/Performer/Tag selectors)
 * remain search-only. Normal Recent catalogs remain the preferred Home view.
 *
 * TPB also has important browse forms which do not necessarily contain
 * "Recent": live cams, compact P2P studio/performer catalogs and cache-backed
 * performer MegaPacks.
 *
 * For known P2P families, a Top catalog is used only when its matching Recent
 * catalog is absent from that manifest. This supports users who deliberately
 * configure Top-only without doubling Home rows when both sorts are present.
 */
internal fun isTpbHomeCatalogDescriptor(
    name: String?,
    id: String,
    hasRequiredExtra: Boolean,
    manifestCatalogIds: Set<String> = emptySet()
): Boolean {
    if (hasRequiredExtra) return false

    val cleanId = id.trim()
    if (cleanId.isBlank()) return false
    val lowerId = cleanId.lowercase(Locale.ROOT)
    val label = "${name.orEmpty()} $cleanId".lowercase(Locale.ROOT)

    // Existing behavior: Recent is the canonical Home sort.
    if (label.contains("recent")) return true

    // TPB live-cam manifests are ordinary browsable catalogs, not Recent
    // catalogs. Current Stripchat uses sc_*; current/newer Chaturbate builds
    // use cb_*/chaturbate_* style ids. Names are checked as a forward-compatible
    // fallback without making every generic catalog a Home row.
    if (
        lowerId.startsWith("sc_") ||
        lowerId.startsWith("cb_") ||
        lowerId.startsWith("chaturbate_") ||
        label.contains("stripchat") ||
        label.contains("chaturbate")
    ) return true

    // Compact TPB P2P catalogs collapse quality/sort variants into one
    // standalone studio/performer catalog. Their ids do not need a sort suffix.
    val isSortedVariant = lowerId.endsWith("_recent") || lowerId.endsWith("_top")
    val isCompactP2pIdentity =
        lowerId.startsWith("xxx_studio_") ||
        lowerId.startsWith("xxx_performer_") ||
        lowerId.startsWith("xxx_creator_")
    if (isCompactP2pIdentity && !isSortedVariant) return true
    if (lowerId.contains("compact") && lowerId.startsWith("xxx_")) return true

    // Current TPB exposes Real-Debrid/TorBox cache-backed performer MegaPacks
    // as normal browse catalogs. Keep their search/filter endpoints out of Home
    // while allowing the performer packs themselves regardless of whether the
    // backend chooses a megapack_*, mp_* or display-name based identifier.
    val isMegaPack = lowerId.startsWith("megapack_") ||
        lowerId.startsWith("mp_") ||
        label.contains("megapack")
    val looksLikeMegaPackFilter = lowerId.endsWith("_search") ||
        label.contains(" search") ||
        label.contains(" tag") ||
        label.contains(" studio")
    if (isMegaPack && !looksLikeMegaPackFilter && !isSortedVariant) return true

    // If a user configures only Top for a known TPB P2P family, don't make the
    // profile look empty. When Recent exists, Recent wins and Top stays hidden
    // to preserve the bridge's one-useful-row behavior.
    if (lowerId.endsWith("_top") && isKnownTpbP2pCatalog(lowerId)) {
        if (manifestCatalogIds.isEmpty()) return false
        val recentId = lowerId.removeSuffix("_top") + "_recent"
        val normalizedIds = manifestCatalogIds.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .toSet()
        return recentId !in normalizedIds
    }

    return false
}

private fun isKnownTpbP2pCatalog(lowerId: String): Boolean =
    lowerId.startsWith("xxx_") ||
        lowerId == "xxx_top" ||
        lowerId.startsWith("curated_") ||
        lowerId.startsWith("sukebei_") ||
        lowerId.startsWith("megapack_") ||
        lowerId.startsWith("mp_")

/**
 * CloudStream infers HLS from a .m3u8 path. TPB live/direct HLS proxies can use
 * extensionless endpoints such as /stripchat/hls/<model>/<quality>, so mark
 * those explicitly. The generic /hls/ path and an explicit HLS stream label
 * also cover TPB's other direct HLS sources without probing media or changing
 * ordinary MP4/debrid links.
 */
internal fun isLikelyTpbHls(
    url: String,
    name: String? = null,
    title: String? = null,
    description: String? = null
): Boolean {
    val clean = url.trim()
    if (clean.isBlank()) return false
    val lowerUrl = clean.lowercase(Locale.ROOT)
    val path = lowerUrl.substringBefore('?').substringBefore('#')

    if (path.endsWith(".m3u8") || path.contains("/hls/")) return true
    if (path.contains("/stripchat/") || path.contains("/chaturbate/")) return true

    val label = listOfNotNull(name, title, description)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return Regex("(^|\\s|[|•-])hls(\\s|$|[|•-])").containsMatchIn(label)
}
