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

/** One Home provider. Its global search intentionally returns no results. */
internal class TPBHomeProvider(
    override var name: String,
    internal val manifestBases: List<String>
) : TPBBaseProvider(manifestBases) {
    // Never expose configured URLs as MainAPI.mainUrl: CloudStream logs provider mainUrl on registration.
    override var mainUrl: String = "$SAFE_MAIN_URL/home"
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = manifestBases.amap { base ->
            val manifest = fetchManifest(base) ?: return@amap emptyList<HomeRow>()
            manifest.catalogs
                .filter { it.isHomeCatalog() }
                .amap { catalog -> catalog.toHomeRow(base, this, page) }
                .filter { it.items.isNotEmpty() }
        }.flatten()

        val merged = linkedMapOf<String, HomeRow>()
        rows.forEach { row ->
            val key = row.name.lowercase(Locale.ROOT)
            val old = merged[key]
            if (old == null) {
                merged[key] = row
            } else {
                val combined = (old.items + row.items).distinctBy { it.url }
                merged[key] = old.copy(
                    items = combined,
                    horizontal = old.horizontal || row.horizontal
                )
            }
        }

        return newHomePageResponse(
            merged.values.map { row -> HomePageList(row.name, row.items, row.horizontal) },
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? =
        newSearchResponseList(emptyList(), false)
}

/** One instance per discovered source. No Home rows, source-specific search only. */
internal class TPBSearchProvider(
    override var name: String,
    internal val sourceName: String,
    internal val routes: List<SearchRoute>
) : TPBBaseProvider(routes.map { it.baseUrl }.distinct()) {
    override var mainUrl: String = "$SAFE_MAIN_URL/search/${safeNameToken(sourceName)}"
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false
    override val searchTimeoutMs: Long? = 90_000L

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    internal suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank() || page < 1) return emptyList<SearchResponse>() to false

        val usableRoutes = if (page == 1) routes else routes.filter { it.supportsSkip }
        if (usableRoutes.isEmpty()) return emptyList<SearchResponse>() to false

        val encoded = encodePath(query)
        val skip = (page - 1) * SEARCH_PAGE_SIZE

        val entries = usableRoutes.amap { route ->
            try {
                val extras = buildString {
                    append("search=")
                    append(encoded)
                    if (page > 1 && route.supportsSkip) {
                        append("&skip=")
                        append(skip)
                    }
                }
                val url = "${route.baseUrl}/catalog/${encodePath(route.type)}/${encodePath(route.catalogId)}/$extras.json"
                app.get(url, timeout = 90L)
                    .parsedSafe<CatalogResponse>()
                    ?.metas.orEmpty()
                    .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                    .map { route.baseUrl to it }
            } catch (_: Throwable) {
                emptyList()
            }
        }.flatten()

        val deduped = entries.distinctBy { (base, entry) -> "${baseRef(base)}|${entry.type}|${entry.id}" }
        val results = deduped.mapNotNull { (base, entry) ->
            entry.toSearchResponse(this, base, sourceName)
        }

        val supportsMore = routes.any { it.supportsSkip }
        return results to (supportsMore && results.isNotEmpty())
    }
}

internal abstract class TPBBaseProvider(
    internal val knownBases: List<String>
) : MainAPI() {
    override suspend fun load(url: String): LoadResponse {
        val bridge = parseJson<BridgeItem>(url)
        val fallback = bridge.entry
        if (fallback.id.isBlank() || fallback.name.isBlank()) throw ErrorLoadingException("Invalid TPB item")

        val base = resolveBase(bridge.baseRef, bridge.baseUrl)
            ?: throw ErrorLoadingException("TPB manifest is no longer configured")
        val type = fallback.type ?: bridge.type ?: "Porn"
        val id = fallback.id

        val meta = try {
            val res = app.get(
                "$base/meta/${encodePath(type)}/${encodePath(id)}.json",
                timeout = 90L
            ).parsedSafe<CatalogResponse>()
            res?.meta ?: res?.metas?.firstOrNull { it.id == id } ?: res?.metas?.firstOrNull()
        } catch (_: Throwable) {
            null
        } ?: fallback

        val data = StreamLoadData(
            baseRef = baseRef(base),
            type = meta.type ?: type,
            id = meta.id.ifBlank { id }
        ).toJson()

        return newMovieLoadResponse(
            meta.name.ifBlank { fallback.name },
            url,
            TvType.Others,
            data
        ) {
            posterUrl = meta.poster ?: fallback.poster
            backgroundPosterUrl = meta.background ?: fallback.background
            plot = meta.description ?: fallback.description
            year = meta.bestYear() ?: fallback.bestYear()
            tags = meta.genre ?: meta.genres ?: fallback.genre ?: fallback.genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<StreamLoadData>(data)
        val base = resolveBase(loadData.baseRef, loadData.baseUrl) ?: return false

        val response = try {
            app.get(
                "$base/stream/${encodePath(loadData.type)}/${encodePath(loadData.id)}.json",
                timeout = 120L
            ).parsedSafe<StreamsResponse>()
        } catch (_: Throwable) {
            null
        } ?: return false

        var emitted = false
        response.streams
            .distinctBy { it.stableKey() }
            .forEach { stream ->
                if (stream.emit(subtitleCallback, callback)) emitted = true
            }
        return emitted
    }

    internal fun resolveBase(ref: String?, legacyBase: String?): String? {
        if (!legacyBase.isNullOrBlank()) {
            // Legacy v4 items are accepted only if that exact manifest is still configured.
            // Never let an item payload turn TPBBridge into an arbitrary network fetcher.
            return knownBases.firstOrNull { it == legacyBase }
        }
        if (ref.isNullOrBlank()) return knownBases.firstOrNull()
        return knownBases.firstOrNull { baseRef(it) == ref }
    }
}

internal data class Manifest(
    @JsonProperty("catalogs") val catalogs: List<Catalog> = emptyList()
)

internal data class Extra(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("isRequired") val isRequired: Boolean? = null
)

internal data class Catalog(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("id") val id: String = "",
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("types") val types: List<String>? = null,
    @JsonProperty("extra") val extra: List<Extra>? = null,
    @JsonProperty("extraSupported") val extraSupported: List<String>? = null
) {
    fun allTypes(): List<String> = when {
        !types.isNullOrEmpty() -> types.filter { it.isNotBlank() }
        !type.isNullOrBlank() -> listOf(type)
        else -> listOf("Porn")
    }

    fun supportsSearch(): Boolean = hasExtra("search")

    fun supportsSkip(): Boolean = hasExtra("skip")

    internal fun hasExtra(extraName: String): Boolean {
        return extra?.any { it.name.equals(extraName, true) } == true ||
            extraSupported?.any { it.equals(extraName, true) } == true
    }

    fun hasRequiredExtra(): Boolean = extra?.any { it.isRequired == true } == true

    fun isHomeCatalog(): Boolean {
        if (hasRequiredExtra()) return false
        val label = "${name.orEmpty()} $id".lowercase(Locale.ROOT)
        return label.contains("recent")
    }

    suspend fun toHomeRow(base: String, provider: TPBBaseProvider, page: Int): HomeRow {
        val skip = ((page - 1).coerceAtLeast(0) * SEARCH_PAGE_SIZE)
        val rawEntries = allTypes().amap { t ->
            try {
                val suffix = if (page > 1 && supportsSkip()) "/skip=$skip" else ""
                app.get(
                    "$base/catalog/${encodePath(t)}/${encodePath(id)}$suffix.json",
                    timeout = 90L
                ).parsedSafe<CatalogResponse>()?.metas.orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
        }.flatten().filter { it.id.isNotBlank() && it.name.isNotBlank() }

        val entries = rawEntries.distinctBy { "${it.type}|${it.id}" }
        val items = entries.mapNotNull { it.toSearchResponse(provider, base, deriveSourceName(name, id)) }
        val landscapeCount = entries.count { it.posterShape.equals("landscape", true) }
        val horizontal = entries.isNotEmpty() && landscapeCount * 2 >= entries.size

        return HomeRow(
            name = deriveSourceName(name, id).ifBlank { name ?: id },
            items = items,
            horizontal = horizontal
        )
    }
}

internal data class HomeRow(
    val name: String,
    val items: List<SearchResponse>,
    val horizontal: Boolean
)

internal data class CatalogResponse(
    @JsonProperty("metas") val metas: List<CatalogEntry>? = null,
    @JsonProperty("meta") val meta: CatalogEntry? = null
)

internal data class CatalogEntry(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("id") val id: String = "",
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("posterShape") val posterShape: String? = null,
    @JsonProperty("background") val background: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("genre") val genre: List<String>? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("year") val yearString: String? = null,
    @JsonProperty("releaseInfo") val releaseInfo: String? = null,
    @JsonProperty("released") val released: String? = null
) {
    fun toSearchResponse(provider: TPBBaseProvider, baseUrl: String, source: String): SearchResponse? {
        if (name.isBlank() || id.isBlank()) return null
        val payload = BridgeItem(
            baseRef = baseRef(baseUrl),
            type = type,
            source = source,
            entry = this
        ).toJson()
        return provider.newMovieSearchResponse(fixTitle(name), payload, TvType.Others) {
            posterUrl = poster
        }
    }

    fun bestYear(): Int? {
        return sequenceOf(yearString, releaseInfo, released)
            .filterNotNull()
            .mapNotNull { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
            .firstOrNull()
    }
}

/**
 * New payloads use baseRef so private manifest/config URLs do not get copied into item URLs/history.
 * baseUrl stays nullable only for backward compatibility with v4 items already created on-device.
 */
internal data class BridgeItem(
    val baseRef: String? = null,
    val baseUrl: String? = null,
    val type: String? = null,
    val source: String? = null,
    val entry: CatalogEntry
)

internal data class StreamLoadData(
    val baseRef: String? = null,
    val baseUrl: String? = null,
    val type: String,
    val id: String
)

internal data class Subtitle(
    val url: String? = null,
    val lang: String? = null,
    val id: String? = null
)

internal data class ProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null
)

internal data class BehaviorHints(
    val proxyHeaders: ProxyHeaders? = null,
    val headers: Map<String, String>? = null,
    val notWebReady: Boolean? = null,
    val filename: String? = null,
    val videoSize: Long? = null
)

internal data class Stream(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String> = emptyList(),
    val behaviorHints: BehaviorHints? = null,
    val subtitles: List<Subtitle> = emptyList()
) {
    fun stableKey(): String = when {
        !url.isNullOrBlank() -> "url:${url.trim()}"
        !infoHash.isNullOrBlank() -> "hash:${infoHash.lowercase(Locale.ROOT)}:${fileIdx ?: -1}"
        !ytId.isNullOrBlank() -> "yt:$ytId"
        !externalUrl.isNullOrBlank() -> "ext:$externalUrl"
        else -> "meta:${name.orEmpty()}|${title.orEmpty()}|${description.orEmpty()}"
    }

    suspend fun emit(
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        emitSubtitles(subtitleCallback)

        val cleanName = cleanStreamName(name, title, behaviorHints?.filename)
        val quality = inferQuality(name, title, description, behaviorHints?.filename)

        // Stremio stream pointers are alternatives. Prefer a direct/debrid URL when present.
        if (!url.isNullOrBlank()) {
            val requestHeaders = linkedMapOf<String, String>().apply {
                behaviorHints?.headers?.let { putAll(it) }
                behaviorHints?.proxyHeaders?.request?.let { putAll(it) }
            }

            callback(
                newExtractorLink(
                    source = cleanStreamSource(name),
                    name = cleanName,
                    url = url,
                    type = INFER_TYPE
                ) {
                    this.quality = quality
                    headers = requestHeaders
                    val ref = requestHeaders.entries
                        .firstOrNull { it.key.equals("referer", true) }
                        ?.value
                    if (!ref.isNullOrBlank()) referer = ref
                }
            )
            return true
        }

        // TPB returns infoHash + sources for P2P fallback when debrid is unavailable/hidden.
        if (!infoHash.isNullOrBlank()) {
            val magnet = buildMagnet(infoHash, behaviorHints?.filename ?: title, sources)
            callback(
                newExtractorLink(
                    source = cleanStreamSource(name).let { if (it == "TPB") "TPB P2P" else it },
                    name = cleanName,
                    url = magnet,
                    type = ExtractorLinkType.MAGNET
                ) {
                    this.quality = quality
                }
            )
            return true
        }

        if (!ytId.isNullOrBlank()) {
            loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            return true
        }

        if (!externalUrl.isNullOrBlank()) {
            loadExtractor(externalUrl, subtitleCallback, callback)
            return true
        }

        return false
    }

    internal fun emitSubtitles(subtitleCallback: (SubtitleFile) -> Unit) {
        subtitles.distinctBy { it.url }.forEach { sub ->
            val subUrl = sub.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val label = SubtitleHelper.fromTagToEnglishLanguageName(sub.lang ?: "")
                ?: sub.lang?.takeIf { it.isNotBlank() }
                ?: "Subtitle"
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }
}

internal data class StreamsResponse(
    val streams: List<Stream> = emptyList()
)

internal data class SearchRoute(
    val baseUrl: String,
    val catalogId: String,
    val type: String,
    val supportsSkip: Boolean = false
)

internal data class SearchRouteGroup(
    val sourceName: String,
    val routes: List<SearchRoute>
)
